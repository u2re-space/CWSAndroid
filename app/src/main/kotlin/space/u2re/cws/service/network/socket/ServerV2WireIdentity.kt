package space.u2re.cws.network

import java.net.URI

data class ServerV2WireIdentity(
    val endpointUrl: String,
    val userId: String,
    val deviceId: String,
    val clientId: String,
    val userKey: String,
    val nodeId: String,
    val aliases: List<String> = emptyList(),
    val origins: List<String> = emptyList(),
    val connectionType: String = "exchanger-initiator",
    val archetype: String = "server-v2"
) {
    fun senderId(): String = nodeId.ifBlank { userId.ifBlank { deviceId }.ifBlank { clientId } }
}

object ServerV2WireContract {
    private fun sanitizeWireIdentity(value: String): String {
        return value
            .trim()
            .trim('|', ',', ';')
            .replace(Regex("\\s+"), "")
    }

    fun resolve(config: EndpointCoreConfig): ServerV2WireIdentity {
        val userId = normalizeNodeId(config.userId.ifBlank { config.deviceId }).ifBlank {
            sanitizeWireIdentity(config.userId.ifBlank { config.deviceId })
        }
        val deviceId = normalizeNodeId(config.deviceId.ifBlank { config.userId }).ifBlank {
            sanitizeWireIdentity(config.deviceId.ifBlank { config.userId })
        }
        val clientId = normalizeNodeId(userId.ifBlank { deviceId }).ifBlank { "android-client" }
        val socketEndpoint = resolveSocketEndpointBase(config)
        val nodeId = normalizeNodeId(userId.ifBlank { deviceId }.ifBlank { clientId })
        val endpointHost = runCatching { URI(normalizeSocketEndpoint(socketEndpoint)).host?.trim().orEmpty() }
            .getOrDefault("")
        val aliases = linkedSetOf<String>().apply {
            addAll(EndpointIdentity.aliases(nodeId))
            addAll(EndpointIdentity.aliases(userId))
            addAll(EndpointIdentity.aliases(deviceId))
            addAll(EndpointIdentity.aliases(clientId))
            if (endpointHost.isNotBlank()) addAll(EndpointIdentity.aliases(endpointHost))
        }.filter { it.isNotBlank() }.distinct()
        return ServerV2WireIdentity(
            endpointUrl = normalizeSocketEndpoint(socketEndpoint),
            userId = userId.ifBlank { clientId },
            deviceId = deviceId.ifBlank { clientId },
            clientId = clientId,
            userKey = config.userKey.trim(),
            nodeId = nodeId.ifBlank { clientId },
            aliases = aliases,
            origins = listOfNotNull(
                endpointHost.ifBlank { null },
                config.endpointUrl.split(Regex("[,;\n]")).firstOrNull()?.trim()?.ifBlank { null },
                config.dispatchUrl.trim().ifBlank { null }
            ).distinct()
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
            val cleanedQuery = sanitizeSocketEndpointQuery(uri.rawQuery)
            URI(
                uri.scheme,
                uri.rawAuthority,
                normalizedPath,
                cleanedQuery,
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
            val resolvedPort = resolveVariantPort(
                sourceScheme = scheme,
                sourcePort = uri.port,
                targetScheme = targetScheme,
                targetDefaultPort = defaultPort
            )
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

    private fun sanitizeSocketEndpointQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrBlank()) return null
        val blockedKeys = setOf("eio", "transport", "sid", "j", "t")
        val sanitized = rawQuery
            .split("&")
            .mapNotNull { token ->
                val key = token.substringBefore("=").trim()
                if (key.isBlank()) return@mapNotNull null
                if (blockedKeys.contains(key.lowercase())) return@mapNotNull null
                token
            }
        return sanitized.joinToString("&").ifBlank { null }
    }

    private fun isSecureScheme(scheme: String): Boolean = scheme == "https" || scheme == "wss"

    private fun resolveVariantPort(
        sourceScheme: String,
        sourcePort: Int,
        targetScheme: String,
        targetDefaultPort: Int
    ): Int {
        if (sourcePort <= 0) return targetDefaultPort
        val sourceSecure = isSecureScheme(sourceScheme)
        val targetSecure = isSecureScheme(targetScheme)
        if (sourceSecure == targetSecure) return sourcePort
        return when (sourcePort) {
            8443 -> 8080
            8080 -> 8443
            443 -> 80
            80 -> 443
            else -> sourcePort
        }
    }

    fun normalizeNodeId(value: String?): String {
        val sanitized = sanitizeWireIdentity(value.orEmpty())
        return EndpointIdentity.bestRouteTarget(sanitized).ifBlank {
            sanitized
        }
    }

    fun normalizeTargets(values: Iterable<String?>): List<String> {
        return values.mapNotNull { normalizeNodeId(it).ifBlank { null } }.distinct()
    }

    fun buildAuth(identity: ServerV2WireIdentity): Map<String, String> {
        return buildMap {
            if (identity.userKey.isNotBlank()) {
                put("token", identity.userKey)
            }
            if (identity.clientId.isNotBlank()) {
                put("clientId", identity.clientId)
                put("userId", identity.userId)
            }
        }
    }

    fun buildHeaders(identity: ServerV2WireIdentity): Map<String, String> {
        return buildMap {
            if (identity.userKey.isNotBlank()) {
                put("Authorization", "Bearer ${identity.userKey}")
                put("X-CWS-Token", identity.userKey)
                put("X-Auth-Token", identity.userKey)
            }
            if (identity.clientId.isNotBlank()) {
                put("X-CWS-Client-Id", identity.clientId)
            }
            if (identity.userId.isNotBlank()) {
                put("X-CWS-User-Id", identity.userId)
            }
            if (identity.nodeId.isNotBlank()) {
                put("X-CWS-Node-Id", identity.nodeId)
            }
            if (identity.aliases.isNotEmpty()) {
                put("X-CWS-Node-Aliases", identity.aliases.joinToString(","))
            }
            if (identity.origins.isNotEmpty()) {
                put("X-CWS-Origin-Aliases", identity.origins.joinToString(","))
            }
            put("X-CWS-Connection-Type", identity.connectionType)
            put("X-CWS-Archetype", identity.archetype)
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
            if (identity.clientId.isNotBlank()) {
                put("clientId", identity.clientId)
                put("userId", identity.userId)
            }
            if (identity.nodeId.isNotBlank()) {
                put("byId", identity.nodeId)
                put("nodeId", identity.nodeId)
            }
            if (identity.aliases.isNotEmpty()) {
                put("aliases", identity.aliases.joinToString(","))
            }
            put("connectionType", wireConnectionType)
            put("archetype", identity.archetype)
        }
    }
}
