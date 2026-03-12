package space.u2re.cws.network

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ServerV2HttpClient(
    private val config: EndpointCoreConfig
) {
    suspend fun probe(): HttpResult {
        val helloUrl = buildUrl("/hello") ?: return HttpResult(false, 0, "")
        return space.u2re.cws.network.getText(helloUrl, config.allowInsecureTls, timeoutMs = 8_000)
    }

    suspend fun broadcastDispatch(requests: List<Map<String, Any>>): HttpResult {
        val url = buildDispatchUrl() ?: return HttpResult(false, 0, "")
        val body = buildMap<String, Any> {
            put("broadcastForceHttps", !config.allowInsecureTls)
            put("requests", requests)
            if (config.userId.isNotBlank()) put("clientId", config.userId)
            if (config.userKey.isNotBlank()) put("token", config.userKey)
        }
        return postJson(url, body, config.allowInsecureTls, timeoutMs = 10_000)
    }

    suspend fun networkDispatch(requests: List<Map<String, Any>>): HttpResult {
        val url = buildUrl("/api/network/dispatch") ?: return HttpResult(false, 0, "")
        val body = mapOf(
            "userId" to config.userId,
            "userKey" to config.userKey,
            "requests" to requests
        )
        return postJson(url, body, config.allowInsecureTls, timeoutMs = 10_000)
    }

    suspend fun sendClipboard(text: String, target: String? = null): HttpResult {
        val url = buildUrl("/clipboard") ?: return HttpResult(false, 0, "")
        val payload = buildMap<String, Any> {
            put("text", text)
            if (!target.isNullOrBlank()) {
                put("target", target)
                put("targetId", target)
                put("deviceId", target)
                put("to", target)
            }
        }
        return postJson(url, payload, config.allowInsecureTls, timeoutMs = 10_000)
    }

    suspend fun registerUser(userId: String? = null, userKey: String? = null, encrypt: Boolean = false): HttpResult {
        val url = buildUrl("/core/auth/register") ?: return HttpResult(false, 0, "")
        return postJson(
            url,
            buildMap<String, Any> {
                if (!userId.isNullOrBlank()) put("userId", userId)
                if (!userKey.isNullOrBlank()) put("userKey", userKey)
                put("encrypt", encrypt)
            },
            config.allowInsecureTls,
            timeoutMs = 10_000
        )
    }

    suspend fun rotateUserKey(encrypt: Boolean? = null): HttpResult {
        val url = buildUrl("/core/auth/rotate") ?: return HttpResult(false, 0, "")
        return postJson(
            url,
            buildMap<String, Any> {
                put("userId", config.userId)
                put("userKey", config.userKey)
                if (encrypt != null) put("encrypt", encrypt)
            },
            config.allowInsecureTls,
            timeoutMs = 10_000
        )
    }

    suspend fun listUsers(): HttpResult {
        val url = buildUrl("/core/auth/users", mapOf("userId" to config.userId, "userKey" to config.userKey))
            ?: return HttpResult(false, 0, "")
        return space.u2re.cws.network.getText(url, config.allowInsecureTls, timeoutMs = 10_000)
    }

    suspend fun deleteUser(targetId: String? = null): HttpResult {
        val url = buildUrl("/core/auth/delete") ?: return HttpResult(false, 0, "")
        return postJson(
            url,
            buildMap<String, Any> {
                put("userId", config.userId)
                put("userKey", config.userKey)
                if (!targetId.isNullOrBlank()) put("targetId", targetId)
            },
            config.allowInsecureTls,
            timeoutMs = 10_000
        )
    }

    suspend fun storageList(dir: String = "."): HttpResult = storagePost("/core/storage/list", mapOf("dir" to dir))
    suspend fun storageGet(path: String, encoding: String = "base64"): HttpResult = storagePost("/core/storage/get", mapOf("path" to path, "encoding" to encoding))
    suspend fun storagePut(path: String, data: String, encoding: String = "base64"): HttpResult = storagePost("/core/storage/put", mapOf("path" to path, "data" to data, "encoding" to encoding))
    suspend fun storageDelete(path: String): HttpResult = storagePost("/core/storage/delete", mapOf("path" to path))
    suspend fun storageSync(body: Map<String, Any?>): HttpResult = storagePost("/core/storage/sync", body)

    private suspend fun storagePost(path: String, body: Map<String, Any?>): HttpResult {
        val url = buildUrl(path) ?: return HttpResult(false, 0, "")
        val request = linkedMapOf<String, Any?>(
            "userId" to config.userId,
            "userKey" to config.userKey
        )
        request.putAll(body)
        return postJson(url, request, config.allowInsecureTls, timeoutMs = 12_000)
    }

    private fun buildDispatchUrl(): String? {
        return buildUrl("/api/broadcast") ?: buildUrl("/core/ops/http/dispatch")
    }

    private fun buildUrl(path: String, query: Map<String, String> = emptyMap()): String? {
        val base = config.endpointUrl.ifBlank { config.dispatchUrl }.trim()
        if (base.isBlank()) return null
        return try {
            val normalizedBase = normalizeEndpointServerUrl(base) ?: base
            val parsed = URI(normalizedBase)
            val finalPath = when {
                path.startsWith("/") -> path
                parsed.path.isNullOrBlank() || parsed.path == "/" -> "/$path"
                else -> "${parsed.path.trimEnd('/')}/${path.trimStart('/')}"
            }
            val queryText = query.entries
                .filter { it.key.isNotBlank() && it.value.isNotBlank() }
                .joinToString("&") { (key, value) ->
                    "${encodeQuery(key)}=${encodeQuery(value)}"
                }
                .ifBlank { null }
            URI(
                parsed.scheme,
                parsed.userInfo,
                parsed.host,
                parsed.port,
                finalPath,
                queryText,
                parsed.fragment
            ).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
