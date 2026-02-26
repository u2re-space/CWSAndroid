package space.u2re.service.daemon

import java.net.URI

fun normalizeEndpointUrl(raw: String, defaultPath: String): String? {
    val trimmed = raw.trim()
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

fun normalizeHubDispatchUrl(raw: String): String? = normalizeEndpointUrl(raw, "/api/broadcast")

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
