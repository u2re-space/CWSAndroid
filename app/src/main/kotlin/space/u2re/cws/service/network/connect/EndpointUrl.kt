package space.u2re.cws.network

import java.net.URI

/**
 * URL-normalization helpers shared by Android network config and transport code.
 *
 * WHY: CWSP settings may contain bare hosts, host:port pairs, dispatch routes,
 * or destination-prefixed values; these helpers collapse them into consistent
 * HTTP endpoint URLs before socket/http clients expand candidates.
 */
fun normalizeEndpointUrl(raw: String, defaultPath: String): String? {
    val trimmed = raw.trim().trimStart('/')
    if (trimmed.isBlank()) return null

    var withProto = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "http://$trimmed"
    }
    
    // Fix double colons if user entered something like ":192.168.0.110"
    withProto = withProto.replace("http://:", "http://").replace("https://:", "https://")

    return try {
        val uri = URI(withProto)
        val authority = uri.rawAuthority ?: return null
        val incomingPath = uri.rawPath
        val normalizedPath = when {
            incomingPath.isNullOrBlank() -> defaultPath
            incomingPath == "/api" && defaultPath.startsWith("/api/") -> defaultPath
            else -> incomingPath
        }

        URI(
            uri.scheme ?: "http",
            authority,
            normalizedPath,
            uri.rawQuery,
            uri.rawFragment
        ).toString()
    } catch (_: Exception) {
        null
    }
}

private fun stripKnownDestinationPrefix(raw: String): String {
    val trimmed = raw.trim().trimStart('/')
    if (trimmed.isBlank()) return trimmed
    if (trimmed.contains("://")) return trimmed

    val rawLower = trimmed.lowercase()
    val knownPrefixes = listOf("hub:", "server:", "proxy:", "tunnel:", "device:", "local-device:", "id:")
    for (prefix in knownPrefixes) {
        if (rawLower.startsWith(prefix)) {
            return trimmed.substring(prefix.length).trimStart()
        }
    }
    return trimmed
}

private val SECURE_BROADCAST_PORTS = setOf("443", "8443")

private fun resolveDestinationProtocol(raw: String): String {
    val trimmed = raw.trim().trimStart('/')
    if (trimmed.contains("://") || trimmed.isBlank()) return "http"

    val authority = trimmed.substringBefore("/").substringBefore("?").substringBefore("#")
    val portCandidate = authority.substringAfterLast(":", "")
    val hasExplicitPort = portCandidate.isNotBlank() && portCandidate.all { ch -> ch.isDigit() }
    if (!hasExplicitPort) return "http"
    return if (SECURE_BROADCAST_PORTS.contains(portCandidate)) "https" else "http"
}

private fun normalizeDestinationWithProtocol(raw: String, defaultPath: String): String? {
    val normalized = raw.trim().trimStart('/')
    if (normalized.isBlank()) return null

    return if (normalized.contains("://")) {
        normalizeEndpointUrl(normalized, defaultPath)
    } else {
        val protocol = resolveDestinationProtocol(normalized)
        val prefixed = "$protocol://$normalized"
        normalizeEndpointUrl(prefixed, defaultPath)
    }
}

/** Normalize a destination-style setting into a concrete URL with the requested default path. */
fun normalizeDestinationUrl(raw: String, defaultPath: String): String? {
    return normalizeDestinationWithProtocol(stripKnownDestinationPrefix(raw), defaultPath)
}

/** Strip known routing prefixes and return the bare destination host/target text. */
fun normalizeDestinationHost(raw: String): String {
    return stripKnownDestinationPrefix(raw)
}

/** Normalize a server endpoint base for API/socket candidate generation. */
fun normalizeEndpointServerUrl(raw: String): String? =
    normalizeDestinationWithProtocol(stripKnownDestinationPrefix(raw), "/api")

/** Normalize the hub/dispatch endpoint used by legacy HTTP broadcast flows. */
fun normalizeHubDispatchUrl(raw: String): String? = normalizeDestinationWithProtocol(stripKnownDestinationPrefix(raw), "/api/broadcast")

/** Normalize the OpenAI-style responses endpoint while preserving any explicit path already present. */
fun normalizeResponsesEndpoint(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null

    val withProto = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }

    return try {
        val uri = URI(withProto)
        val authority = uri.rawAuthority ?: return null
        val rawPath = uri.rawPath?.trimEnd('/') ?: ""
        val normalizedPath = if (rawPath.isBlank() || rawPath == "/") {
            "/v1/responses"
        } else {
            rawPath
        }

        URI(
            uri.scheme ?: "https",
            authority,
            normalizedPath,
            uri.rawQuery,
            uri.rawFragment
        ).toString()
    } catch (_: Exception) {
        null
    }
}
