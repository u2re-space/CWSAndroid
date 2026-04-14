package space.u2re.cws.network

import java.net.URI

data class ServerV2WireIdentity(
    val endpointUrl: String,
    val userId: String,
    val deviceId: String,
    val clientId: String,
    val userKey: String,
    val connectionType: String = "exchanger-initiator",
    val archetype: String = "server-v2"
) {
    fun senderId(): String = userId.ifBlank { deviceId }.ifBlank { clientId }
}

object ServerV2WireContract {
    fun resolve(config: EndpointCoreConfig): ServerV2WireIdentity {
        val userId = normalizeNodeId(config.userId.ifBlank { config.deviceId }).ifBlank {
            config.userId.ifBlank { config.deviceId }.trim()
        }
        val deviceId = normalizeNodeId(config.deviceId.ifBlank { config.userId }).ifBlank {
            config.deviceId.ifBlank { config.userId }.trim()
        }
        val clientId = normalizeNodeId(userId.ifBlank { deviceId }).ifBlank { "android-client" }
        val socketEndpoint = resolveSocketEndpointBase(config)
        return ServerV2WireIdentity(
            endpointUrl = normalizeSocketEndpoint(socketEndpoint),
            userId = userId.ifBlank { clientId },
            deviceId = deviceId.ifBlank { clientId },
            clientId = clientId,
            userKey = config.userKey.trim()
        )
    }

    fun resolveSocketEndpointCandidates(config: EndpointCoreConfig): List<String> {
        val out = linkedSetOf<String>()
        val directCandidates = config.endpointCandidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap(::expandSocketEndpointCandidates)
        out.addAll(directCandidates)
        val splitFromRaw = config.endpointUrl
            .ifBlank { config.dispatchUrl }
            .split(Regex("[,;\n]"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap(::expandSocketEndpointCandidates)
        out.addAll(splitFromRaw)
        return out.toList()
    }

    private fun resolveSocketEndpointBase(config: EndpointCoreConfig): String {
        val candidates = resolveSocketEndpointCandidates(config)
        if (candidates.isNotEmpty()) return candidates.first()
        return config.endpointUrl
            .ifBlank { config.dispatchUrl }
            .split(Regex("[,;\n]"))
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun normalizeSocketEndpoint(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        return try {
            val uri = URI(trimmed)
            val rawPath = uri.rawPath ?: "/"
            val normalizedPath = when {
                rawPath.equals("/api", ignoreCase = true) -> "/"
                rawPath.startsWith("/api/", ignoreCase = true) -> "/"
                rawPath.equals("/socket.io", ignoreCase = true) -> "/"
                rawPath.startsWith("/socket.io/", ignoreCase = true) -> "/"
                else -> rawPath.ifBlank { "/" }
            }
            URI(
                uri.scheme,
                uri.rawAuthority,
                normalizedPath,
                uri.rawQuery,
                uri.rawFragment
            ).toString()
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun expandSocketEndpointCandidates(raw: String): List<String> {
        val base = normalizeSocketEndpoint(raw)
        if (base.isBlank()) return emptyList()
        val out = linkedSetOf(base)
        val uri = try {
            URI(base)
        } catch (_: Exception) {
            null
        }
        if (uri == null) return out.toList()

        val scheme = uri.scheme?.lowercase() ?: return out.toList()
        val host = uri.host?.trim().orEmpty()
        if (host.isBlank()) return out.toList()
        val path = uri.rawPath ?: "/"
        val query = uri.rawQuery
        val fragment = uri.rawFragment

        fun normalizedVariant(targetScheme: String, defaultPort: Int): String {
            val resolvedPort = if (uri.port > 0) uri.port else defaultPort
            return normalizeSocketEndpoint(
                URI(targetScheme, null, host, resolvedPort, path, query, fragment).toString()
            )
        }

        val wsAlt = normalizedVariant("ws", 8080)
        val wssAlt = normalizedVariant("wss", 8443)
        val httpAlt = normalizedVariant("http", 8080)
        val httpsAlt = normalizedVariant("https", 8443)

        when (scheme) {
            "https" -> {
                out.add(wssAlt)
                if (uri.port == 8443 || uri.port == -1) {
                    out.add(httpAlt)
                    out.add(wsAlt)
                }
            }
            "http" -> {
                out.add(wsAlt)
                if (uri.port == 8080 || uri.port == -1) {
                    out.add(httpsAlt)
                    out.add(wssAlt)
                }
            }
            "wss" -> {
                out.add(httpsAlt)
                if (uri.port == 8443 || uri.port == -1) {
                    out.add(wsAlt)
                    out.add(httpAlt)
                }
            }
            "ws" -> {
                out.add(httpAlt)
                if (uri.port == 8080 || uri.port == -1) {
                    out.add(wssAlt)
                    out.add(httpsAlt)
                }
            }
        }
        return out.filter { it.isNotBlank() }
    }

    fun normalizeNodeId(value: String?): String {
        return EndpointIdentity.bestRouteTarget(value).ifBlank {
            value?.trim().orEmpty()
        }
    }

    fun normalizeTargets(values: Iterable<String?>): List<String> {
        return values.mapNotNull { normalizeNodeId(it).ifBlank { null } }.distinct()
    }

    fun buildAuth(identity: ServerV2WireIdentity): Map<String, String> {
        return buildMap {
            if (identity.userKey.isNotBlank()) {
                put("token", identity.userKey)
                put("airpadToken", identity.userKey)
            }
            if (identity.clientId.isNotBlank()) {
                put("clientId", identity.clientId)
                put("userId", identity.userId)
            }
        }
    }

    fun buildQuery(identity: ServerV2WireIdentity): Map<String, String> {
        // Dual-wire compatibility:
        // some nodes/gateways only understand `connectionType=first-order`.
        // Keep the local identity semantics (`exchanger-*`) but adapt the wire value.
        val rawConnectionType = identity.connectionType.trim().lowercase()
        val wireConnectionType = if (rawConnectionType.contains("exchanger")) {
            "first-order"
        } else {
            identity.connectionType
        }
        return buildMap {
            if (identity.userKey.isNotBlank()) {
                put("token", identity.userKey)
                put("airpadToken", identity.userKey)
                put("userKey", identity.userKey)
            }
            if (identity.clientId.isNotBlank()) {
                put("clientId", identity.clientId)
                put("__airpad_client", identity.clientId)
                put("userId", identity.userId)
            }
            put("connectionType", wireConnectionType)
            put("archetype", identity.archetype)
        }
    }
}
