package space.u2re.cws.network

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ServerV2HttpClient(
    private val config: EndpointCoreConfig
) {
    suspend fun probe(): HttpResult {
        return getTextAcrossCandidates("/hello", timeoutMs = 8_000)
    }

    suspend fun broadcastDispatch(requests: List<Map<String, Any>>): HttpResult {
        val body = buildMap<String, Any> {
            put("broadcastForceHttps", !config.allowInsecureTls)
            put("requests", requests)
            if (config.userId.isNotBlank()) put("clientId", config.userId)
            if (config.userKey.isNotBlank()) put("token", config.userKey)
        }
        return postJsonAcrossCandidates(listOf("/api/broadcast", "/core/ops/http/dispatch"), body, timeoutMs = 10_000)
    }

    suspend fun networkDispatch(requests: List<Map<String, Any>>): HttpResult {
        val body = mapOf(
            "userId" to config.userId,
            "userKey" to config.userKey,
            "requests" to requests
        )
        return postJsonAcrossCandidates(listOf("/api/network/dispatch"), body, timeoutMs = 10_000)
    }

    suspend fun sendClipboard(text: String, target: String? = null): HttpResult {
        val payload = buildMap<String, Any> {
            put("text", text)
            if (!target.isNullOrBlank()) {
                put("target", target)
                put("targetId", target)
                put("deviceId", target)
                put("to", target)
            }
        }
        return postJsonAcrossCandidates(listOf("/clipboard"), payload, timeoutMs = 10_000)
    }

    suspend fun registerUser(userId: String? = null, userKey: String? = null, encrypt: Boolean = false): HttpResult {
        return postJsonAcrossCandidates(
            listOf("/core/auth/register"),
            buildMap<String, Any> {
                if (!userId.isNullOrBlank()) put("userId", userId)
                if (!userKey.isNullOrBlank()) put("userKey", userKey)
                put("encrypt", encrypt)
            },
            timeoutMs = 10_000
        )
    }

    suspend fun rotateUserKey(encrypt: Boolean? = null): HttpResult {
        return postJsonAcrossCandidates(
            listOf("/core/auth/rotate"),
            buildMap<String, Any> {
                put("userId", config.userId)
                put("userKey", config.userKey)
                if (encrypt != null) put("encrypt", encrypt)
            },
            timeoutMs = 10_000
        )
    }

    suspend fun listUsers(): HttpResult {
        return getTextAcrossCandidates(
            path = "/core/auth/users",
            query = mapOf("userId" to config.userId, "userKey" to config.userKey),
            timeoutMs = 10_000
        )
    }

    suspend fun deleteUser(targetId: String? = null): HttpResult {
        return postJsonAcrossCandidates(
            listOf("/core/auth/delete"),
            buildMap<String, Any> {
                put("userId", config.userId)
                put("userKey", config.userKey)
                if (!targetId.isNullOrBlank()) put("targetId", targetId)
            },
            timeoutMs = 10_000
        )
    }

    suspend fun storageList(dir: String = "."): HttpResult = storagePost("/core/storage/list", mapOf("dir" to dir))
    suspend fun storageGet(path: String, encoding: String = "base64"): HttpResult = storagePost("/core/storage/get", mapOf("path" to path, "encoding" to encoding))
    suspend fun storagePut(path: String, data: String, encoding: String = "base64"): HttpResult = storagePost("/core/storage/put", mapOf("path" to path, "data" to data, "encoding" to encoding))
    suspend fun storageDelete(path: String): HttpResult = storagePost("/core/storage/delete", mapOf("path" to path))
    suspend fun storageSync(body: Map<String, Any?>): HttpResult = storagePost("/core/storage/sync", body)

    private suspend fun storagePost(path: String, body: Map<String, Any?>): HttpResult {
        val request = linkedMapOf<String, Any?>(
            "userId" to config.userId,
            "userKey" to config.userKey
        )
        request.putAll(body)
        return postJsonAcrossCandidates(listOf(path), request, timeoutMs = 12_000)
    }

    private suspend fun getTextAcrossCandidates(
        path: String,
        query: Map<String, String> = emptyMap(),
        timeoutMs: Int
    ): HttpResult {
        val urls = buildUrlCandidates(path, query)
        if (urls.isEmpty()) return HttpResult(false, 0, "")
        var lastResult = HttpResult(false, 0, "")
        for (url in urls) {
            val result = space.u2re.cws.network.getText(url, config.allowInsecureTls, timeoutMs = timeoutMs)
            if (result.ok) return result
            lastResult = result
        }
        return lastResult
    }

    private suspend fun postJsonAcrossCandidates(
        paths: List<String>,
        body: Map<String, Any?>,
        timeoutMs: Int
    ): HttpResult {
        val urls = paths.flatMap { path -> buildUrlCandidates(path) }.distinct()
        if (urls.isEmpty()) return HttpResult(false, 0, "")
        var lastResult = HttpResult(false, 0, "")
        for (url in urls) {
            val result = postJson(url, body, config.allowInsecureTls, timeoutMs = timeoutMs)
            if (result.ok) return result
            lastResult = result
        }
        return lastResult
    }

    private fun buildUrlCandidates(path: String, query: Map<String, String> = emptyMap()): List<String> {
        val bases = config.endpointCandidates.ifEmpty {
            splitEndpointCandidates(config.endpointUrl.ifBlank { config.dispatchUrl })
        }
        if (bases.isEmpty()) return emptyList()
        return bases.mapNotNull { base -> buildUrl(base, path, query) }.distinct()
    }

    private fun splitEndpointCandidates(raw: String): List<String> {
        return raw
            .split(Regex("[,;\n]"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun buildUrl(base: String, path: String, query: Map<String, String> = emptyMap()): String? {
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
