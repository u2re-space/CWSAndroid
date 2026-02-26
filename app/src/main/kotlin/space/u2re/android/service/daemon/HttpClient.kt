package space.u2re.service.daemon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.MediaType.Companion.toMediaType

data class HttpResult(val ok: Boolean, val status: Int, val body: String)

data class DispatchResult(
    val url: String,
    val ok: Boolean,
    val status: Int? = null,
    val body: String? = null,
    val error: String? = null
)

data class DispatchRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val unencrypted: Boolean = false
)

private fun trustAllManager(): X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

private fun insecureOkHttpClient(timeoutMs: Int): OkHttpClient {
    val trustAll = trustAllManager()
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAll), SecureRandom())
    }
    val sslSocketFactory: SSLSocketFactory = sslContext.socketFactory
    return OkHttpClient.Builder()
        .connectTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .sslSocketFactory(sslSocketFactory, trustAll)
        .hostnameVerifier(HostnameVerifier { _, _ -> true })
        .build()
}

private fun parseResponse(resp: Response): HttpResult {
    val body = resp.body?.string() ?: ""
    return HttpResult(ok = resp.isSuccessful, status = resp.code, body = body)
}

private suspend fun executeRequest(request: Request, allowInsecureTls: Boolean, timeoutMs: Int): HttpResult = withContext(Dispatchers.IO) {
    val client = if (allowInsecureTls) {
        insecureOkHttpClient(timeoutMs)
    } else {
        OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }
    client.newCall(request).execute().use { parseResponse(it) }
}

private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

suspend fun postText(
    url: String,
    body: String,
    headers: Map<String, String>,
    allowInsecureTls: Boolean,
    timeoutMs: Int = 8000
): HttpResult {
    val contentType = headers["Content-Type"] ?: headers["content-type"] ?: "text/plain; charset=utf-8"
    val request = Request.Builder()
        .url(url)
        .post(body.toRequestBody(contentType.toMediaType()))
        .apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }
        .build()
    return executeRequest(request, allowInsecureTls, timeoutMs)
}

suspend fun postJson(
    url: String,
    json: Any,
    allowInsecureTls: Boolean,
    timeoutMs: Int = 8000,
    headers: Map<String, String> = emptyMap()
): HttpResult {
    val body = if (json is String) json else DaemonJson.toJson(json)
    val request = Request.Builder()
        .url(url)
        .post(body.toRequestBody(mediaTypeJson))
        .header("Content-Type", "application/json; charset=utf-8")
        .apply {
            headers.forEach { (key, value) ->
                addHeader(key, value)
            }
        }
        .build()
    return executeRequest(request, allowInsecureTls, timeoutMs)
}

object DaemonJson {
    private val gson = com.google.gson.Gson()
    fun toJson(obj: Any): String = gson.toJson(obj)
}
