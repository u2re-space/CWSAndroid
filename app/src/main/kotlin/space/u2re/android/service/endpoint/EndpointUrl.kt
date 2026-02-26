package space.u2re.service.daemon

import java.net.URI

fun normalizeEndpointUrl(raw: String, defaultPath: String): String? {
    val trimmed = raw.trim().trimStart('/')
    if (trimmed.isBlank()) return null

    val withProto = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "http://$trimmed"
    }

    return try {
        val uri = URI(withProto)
        val authority = uri.rawAuthority ?: return null
        val incomingPath = uri.rawPath
        val normalizedPath = when {
            incomingPath.isNullOrBlank() || incomingPath == "/" -> defaultPath
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
    val knownPrefixes = listOf("hub:", "server:", "proxy:", "tunnel:", "device:", "local-device:")
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

fun normalizeDestinationUrl(raw: String, defaultPath: String): String? {
    return normalizeDestinationWithProtocol(stripKnownDestinationPrefix(raw), defaultPath)
}

fun normalizeDestinationHost(raw: String): String {
    return stripKnownDestinationPrefix(raw)
}

fun normalizeHubDispatchUrl(raw: String): String? = normalizeDestinationWithProtocol(stripKnownDestinationPrefix(raw), "/api/broadcast")

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
        } else if (rawPath.endsWith("/responses")) {
            rawPath
        } else {
            "$rawPath/responses"
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
