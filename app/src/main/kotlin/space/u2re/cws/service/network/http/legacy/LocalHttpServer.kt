package space.u2re.cws.network

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
import space.u2re.cws.data.ClipboardEnvelope
import space.u2re.cws.data.ClipboardEnvelopeCodec
import space.u2re.cws.daemon.ContactItem
import space.u2re.cws.daemon.SmsItem
import space.u2re.cws.notifications.NotificationEvent
import space.u2re.cws.daemon.DaemonLog

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
    val applyClipboardEnvelope: suspend (ClipboardEnvelope, String?, String?, String?) -> Boolean,
    val readClipboardEnvelope: suspend () -> ClipboardEnvelope?,
    val dispatch: suspend (List<DispatchRequest>) -> List<DispatchResult>,
    val sendSms: suspend (String, String) -> Unit,
    val postClipboard: (suspend (String, List<String>) -> Unit)? = null,
    val postClipboardEnvelope: (suspend (ClipboardEnvelope, List<String>) -> Unit)? = null,
    val listSms: suspend (Int) -> List<SmsItem>,
    val listNotifications: suspend (Int) -> List<NotificationEvent>,
    val listContacts: suspend (Int) -> List<ContactItem>,
    val listDestinations: () -> List<String>,
    val speakNotificationText: suspend (String) -> Unit,
    val getConfigContent: (String) -> String?
)

@Keep
class LocalHttpServer(private val opts: HttpServerOptions) {
    private val gson = Gson()
    private var server: NanoHTTPD? = null

    private companion object {
        private const val PRIMARY_DISPATCH_ROUTE = "/core/ops/http/dispatch"
        private val LEGACY_DISPATCH_ROUTES = setOf(
            "/core/network/dispatch",
            "/api/network/dispatch",
            "/core/ops/ws/send",
            "/api/ws",
            "/core/reverse/send",
            "/api/reverse/send"
        )
    }

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
                    if (uri == "/hello") {
                        return json(200, mapOf("ok" to true, "message" to "hello from android-cws"))
                    }

                    if (method != "POST" && method != "GET") {
                        return text(405, "Method Not Allowed")
                    }

                    when {
                        uri.startsWith("/api/config/") -> {
                            if (method != "GET") return text(405, "Method Not Allowed")
                            val filename = uri.removePrefix("/api/config/")
                            if (filename.contains("/") || filename.contains("\\")) {
                                return text(400, "Bad Request")
                            }
                            val content = opts.getConfigContent(filename)
                            return if (content != null) {
                                val mime = if (filename.endsWith(".json")) "application/json" else "text/plain"
                                newFixedLengthResponse(Response.Status.OK, mime, content)
                            } else {
                                text(404, "Not Found")
                            }
                        }
                        uri == "/clipboard" || uri == "/clipboard/" -> {
                            if (method == "GET") {
                                return try {
                                    val payload = runBlocking { opts.readClipboardEnvelope() }
                                    json(
                                        200,
                                        mapOf(
                                            "ok" to (payload?.hasContent() == true),
                                            "clipboard" to (payload?.toMap() ?: emptyMap<String, Any>())
                                        )
                                    )
                                } catch (e: Exception) {
                                    json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                                }
                            }
                            if (method != "POST") return text(405, "Method Not Allowed")
                            val bodyText = readBody(session)
                            val ct = getHeader(session.headers, "content-type")?.lowercase() ?: ""
                            val payload = ClipboardEnvelopeCodec.fromHttpBody(bodyText, ct, source = "local-http")
                            val targets = extractClipboardTargets(payload.metadata["targets"])
                            DaemonLog.debug(
                                "LocalHttpServer",
                                "POST $uri contentType=$ct payloadLength=${payload.bestText()?.length ?: 0} targets=${targets.size}"
                            )
                            if (!payload.hasContent()) return text(400, "No clipboard payload provided")
                            val postClipboardEnvelope = opts.postClipboardEnvelope
                            val postClipboard = opts.postClipboard
                            if (targets.isNotEmpty() && postClipboardEnvelope != null) {
                                return try {
                                    runBlocking { postClipboardEnvelope(payload, targets) }
                                    DaemonLog.info("LocalHttpServer", "clipboard request routed to targets=$targets")
                                    json(200, mapOf("ok" to true, "targets" to targets, "payload" to payload.toMap()))
                                } catch (e: Exception) {
                                    json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                                }
                            }
                            if (targets.isNotEmpty() && postClipboard != null && !payload.bestText().isNullOrBlank()) {
                                return try {
                                    runBlocking { postClipboard(payload.bestText().orEmpty(), targets) }
                                    DaemonLog.info("LocalHttpServer", "clipboard text request routed to targets=$targets")
                                    json(200, mapOf("ok" to true, "targets" to targets))
                                } catch (e: Exception) {
                                    json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                                }
                            }
                            return try {
                                runBlocking { opts.applyClipboardEnvelope(payload, payload.uuid, "local-http", null) }
                                DaemonLog.info("LocalHttpServer", "clipboard request applied")
                                json(200, mapOf("ok" to true, "payload" to payload.toMap()))
                            } catch (e: Exception) {
                                json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                            }
                        }
                        uri == "/devices" -> {
                            val list = (opts.listDestinations() + "/sms" + "/notifications" + "/contacts").toSet().toList().filter { it.isNotBlank() }
                            return json(
                                200,
                                mapOf(
                                    "ok" to true,
                                    "destinations" to list,
                                    "deviceId" to "android-cws"
                                )
                            )
                        }
                        uri == "/sms" -> {
                            if (method == "GET") {
                                return try {
                                    val rawLimit = session.parameters["limit"]?.firstOrNull()
                                    val limit = rawLimit?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                                    val items = runBlocking { opts.listSms(limit) }
                                    json(200, mapOf("ok" to true, "items" to items))
                                } catch (e: Exception) {
                                    json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                                }
                            }
                            if (method == "POST") {
                                val parsed = readBody(session)
                                val map = gson.fromJson(parsed.ifBlank { "{}" }, object : TypeToken<Map<String, Any>>() {}.type) as? Map<*, *>
                                val number = map?.get("number")?.toString()?.trim().orEmpty()
                                val content = map?.get("content")?.toString()?.trim().orEmpty()
                                if (number.isBlank() || content.isBlank()) {
                                    return text(400, "number/content required")
                                }
                                return try {
                                    runBlocking { opts.sendSms(number, content) }
                                    json(200, mapOf("ok" to true))
                                } catch (e: Exception) {
                                    json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                                }
                            }
                            return text(405, "Method Not Allowed")
                        }
                        uri == "/contacts" -> {
                            if (method != "GET") {
                                return text(405, "Method Not Allowed")
                            }
                            return try {
                                val rawLimit = session.parameters["limit"]?.firstOrNull()
                                val limit = rawLimit?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                                val items = runBlocking { opts.listContacts(limit) }
                                json(200, mapOf("ok" to true, "items" to items))
                            } catch (e: Exception) {
                                json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                            }
                        }
                        uri == "/notifications" -> {
                            if (method != "GET") {
                                return text(405, "Method Not Allowed")
                            }
                            return try {
                                val rawLimit = session.parameters["limit"]?.firstOrNull()
                                val limit = rawLimit?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                                val items = runBlocking { opts.listNotifications(limit) }
                                json(200, mapOf("ok" to true, "items" to items))
                            } catch (e: Exception) {
                                json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                            }
                        }
                        uri == "/notifications/speak" -> {
                            if (method != "POST") {
                                return text(405, "Method Not Allowed")
                            }
                            return try {
                                val bodyText = readBody(session)
                                val parsed = gson.fromJson(bodyText.ifBlank { "{}" }, object : TypeToken<Map<String, Any>>() {}.type) as? Map<*, *>
                                val message = parsed?.get("text")?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: bodyText.trim()
                                if (message.isBlank()) {
                                    return json(400, mapOf("ok" to false, "error" to "text required"))
                                }
                                runBlocking { opts.speakNotificationText(message) }
                                json(200, mapOf("ok" to true))
                            } catch (e: Exception) {
                                json(500, mapOf("ok" to false, "error" to (e.message ?: e.toString())))
                            }
                        }
                        uri == PRIMARY_DISPATCH_ROUTE || LEGACY_DISPATCH_ROUTES.contains(uri) -> {
                            if (method != "POST") return text(405, "Method Not Allowed")
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
                if (rawUrl.isBlank()) {
                    DaemonLog.warn("LocalHttpServer", "skip dispatch request without url: $map")
                    return@mapNotNull null
                }
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

    private fun extractClipboardTargets(value: Any?): List<String> {
        return when (value) {
            null -> emptyList()
            is String -> value
                .split("[;,]".toRegex())
                .map { it.trim() }
                .filter { it.isNotBlank() }
            is List<*> -> value.flatMap { extractClipboardTargets(it) }
            is Map<*, *> -> {
                val nested = value["targets"] ?: value["target"] ?: value["targetId"] ?: value["deviceId"] ?: value["to"] ?: value["device"]
                extractClipboardTargets(nested)
            }
            else -> listOf(value.toString())
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
