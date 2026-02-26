package space.u2re.service.daemon

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.TrustManagerFactory

data class TlsConfig(
    val enabled: Boolean,
    val keystoreAssetPath: String,
    val keystoreType: String,
    val keystorePassword: String
)

data class HttpServerOptions(
    val port: Int,
    val authToken: String,
    val tls: TlsConfig? = null,
    val context: Context,
    val setClipboardTextSync: (String) -> Unit,
    val setClipboardText: suspend (String) -> Unit,
    val dispatch: suspend (List<DispatchRequest>) -> List<DispatchResult>,
    val sendSms: suspend (String, String) -> Unit
)

@Keep
class LocalHttpServer(private val opts: HttpServerOptions) {
    private val gson = Gson()
    private var server: NanoHTTPD? = null

    fun start() {
        if (server != null) return

        val handler = object : NanoHTTPD(opts.port) {
            override fun serve(session: IHTTPSession): Response {
                return try {
                    if (!isAuthorized(session.headers, opts.authToken)) {
                        return text(401, "Unauthorized")
                    }

                    val method = session.method?.name?.uppercase() ?: "GET"
                    val uri = session.uri ?: "/"

                    if (method == "OPTIONS") {
                        return text(200, "OK")
                    }

                    if (uri == "/health") {
                        return json(200, mapOf("ok" to true, "port" to opts.port))
                    }

                    if (method != "POST") {
                        return text(405, "Method Not Allowed")
                    }

                    when (uri) {
                        "/clipboard" -> {
                            val bodyText = readBody(session)
                            val ct = getHeader(session.headers, "content-type")?.lowercase() ?: ""
                            var payload = bodyText
                            if (ct.contains("application/json")) {
                                try {
                                    val payloadMap = gson.fromJson<Map<String, Any>>(
                                        bodyText.ifBlank { "{}" },
                                        object : TypeToken<Map<String, Any>>() {}.type
                                    )
                                    val textValue = payloadMap["text"] as? String ?: ""
                                    if (textValue.isNotBlank()) payload = textValue
                                } catch (_: Exception) {
                                    // ignore parse errors; keep raw
                                }
                            }
                            if (payload.isBlank()) return text(400, "No text provided")
                            return try {
                                opts.setClipboardTextSync(payload)
                                json(200, mapOf("ok" to true))
                            } catch (e: Exception) {
                                json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                            }
                        }
                        "/core/ops/http/dispatch" -> {
                            val parsed = try {
                                readBody(session)
                            } catch (_: Exception) {
                                "{}"
                            }
                            val requests = parseDispatchRequests(parsed)
                            return try {
                                val result = runBlocking { opts.dispatch(requests) }
                                json(200, mapOf("ok" to true, "result" to result))
                            } catch (e: Exception) {
                                json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                            }
                        }
                        "/sms" -> {
                            val parsed = readBody(session)
                            val map = gson.fromJson(parsed.ifBlank { "{}" }, object : TypeToken<Map<String, Any>>() {}.type) as? Map<*, *>
                                ?: emptyMap<String, Any>()
                            val number = map["number"]?.toString()?.trim().orEmpty()
                            val content = map["content"]?.toString()?.trim().orEmpty()
                            if (number.isBlank() || content.isBlank()) {
                                return json(400, mapOf("ok" to false, "error" to "number/content required"))
                            }
                            return try {
                                runBlocking { opts.sendSms(number, content) }
                                json(200, mapOf("ok" to true))
                            } catch (e: Exception) {
                                json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                            }
                        }
                        else -> text(404, "Not Found")
                    }
                } catch (e: Exception) {
                    DaemonLog.warn("LocalHttpServer", "serve failed", e)
                    json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                }
            }

            private fun json(status: Int, obj: Any): Response {
                return try {
                    val body = gson.toJson(obj)
                    newResponse(status, "application/json; charset=utf-8", body)
                } catch (_: Exception) {
                    newResponse(500, "text/plain; charset=utf-8", "serialization error")
                }
            }

            private fun text(status: Int, body: String): Response = newResponse(status, "text/plain; charset=utf-8", body)

            private fun newResponse(status: Int, mimeType: String, body: String): Response {
                val code = Response.Status.lookup(status) ?: Response.Status.INTERNAL_ERROR
                val resp = NanoHTTPD.newFixedLengthResponse(code, mimeType, body)
                addCors(resp)
                return resp
            }

            private fun addCors(response: Response) {
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                response.addHeader("Access-Control-Allow-Headers", "*")
            }
        }

        try {
            opts.tls?.let { tls ->
                if (tls.enabled) {
                    handler.makeSecure(makeServerSocketFactory(tls), null)
                }
            }
            handler.start(5_000, false)
            server = handler
            DaemonLog.info("LocalHttpServer", "http server started port=${opts.port}")
        } catch (e: Exception) {
            DaemonLog.error("LocalHttpServer", "start failed", e)
            server = null
            throw e
        }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private fun makeServerSocketFactory(tls: TlsConfig): SSLServerSocketFactory {
        val path = tls.keystoreAssetPath.trim()
        val pwd = tls.keystorePassword
        val passChars = pwd.toCharArray()
        val ksType = tls.keystoreType.ifBlank { "PKCS12" }
        val keyStore = KeyStore.getInstance(ksType)
        val stream: InputStream = opts.context.assets.open(path)
        keyStore.load(stream, passChars)
        stream.close()

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, passChars)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        val context = SSLContext.getInstance("TLS")
        context.init(kmf.keyManagers, tmf.trustManagers, SecureRandom())
        return context.serverSocketFactory
    }

    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        return body
    }

    private fun parseDispatchRequests(body: String): List<DispatchRequest> {
        return try {
            val root = gson.fromJson(body.ifBlank { "{}" }, object : TypeToken<Map<String, Any>>() {}.type) as? Map<*, *>
                ?: return emptyList()
            val list = root["requests"] as? List<*> ?: return emptyList()
            list.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val rawUrl = map["url"]?.toString() ?: ""
                if (rawUrl.isBlank()) return@mapNotNull null
                DispatchRequest(
                    url = rawUrl,
                    method = map["method"]?.toString()?.ifBlank { "POST" } ?: "POST",
                    headers = (map["headers"] as? Map<*, *>)?.map { e -> e.key.toString() to e.value.toString() }?.toMap() ?: emptyMap(),
                    body = map["body"]?.toString() ?: "",
                    unencrypted = map["unencrypted"] as? Boolean ?: false
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getHeader(headers: Map<String, String>?, name: String): String? {
        if (headers == null) return null
        val direct = headers[name]
        if (!direct.isNullOrBlank()) return direct
        val lower = name.lowercase()
        headers.forEach { (k, v) ->
            if (k.lowercase() == lower) return v
        }
        return null
    }

    private fun isAuthorized(headers: Map<String, String>?, token: String): Boolean {
        val secret = token.trim()
        if (secret.isEmpty()) return true
        val auth = getHeader(headers, "authorization")
        if (!auth.isNullOrBlank()) {
            val m = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE).find(auth)
            if (m != null && m.groupValues.getOrNull(1)?.trim() == secret) return true
        }
        val header = getHeader(headers, "x-auth-token")
        if (header == secret) return true
        val alt = getHeader(headers, "x-auth_token")
        return alt == secret
    }
}
