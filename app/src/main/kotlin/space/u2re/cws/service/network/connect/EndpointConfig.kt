package space.u2re.cws.network

private const val DEFAULT_ENDPOINT_NAMESPACE = "default"
private const val DEFAULT_ENDPOINT_ROLES = "endpoint,peer,node,app"

data class EndpointCoreConfig(
    val endpointUrl: String,
    val endpointCandidates: List<String>,
    val dispatchUrl: String,
    val userId: String,
    val userKey: String,
    val userKeys: List<String>,
    val airpadAuthToken: String,
    val deviceId: String,
    val namespace: String,
    val roles: String,
    val allowInsecureTls: Boolean,
    val trustedCa: String,
    val destinations: List<String>,
    val configPath: String,
    val storagePath: String,
    val legacyBridgeEnabled: Boolean
) {
    fun hasRemoteEndpoint(): Boolean = endpointCandidates.isNotEmpty() || endpointUrl.isNotBlank()

    fun hasCredentials(): Boolean = userId.isNotBlank()

    fun isRemoteReady(): Boolean = hasRemoteEndpoint() && hasCredentials()
}

private fun normalizeAndSortEndpointCandidates(rawCandidates: List<String>): List<String> {
    fun endpointPriority(candidate: String): Int {
        val host = runCatching { java.net.URI(candidate).host?.trim().orEmpty() }.getOrDefault("")
        if (host.isBlank()) return 3
        if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1") return 0
        val isIpv4 = host.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$"))
        if (!isIpv4) return 1
        return when {
            host.startsWith("10.") -> 0
            host.startsWith("192.168.") -> 0
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host) -> 0
            else -> 2
        }
    }

    return rawCandidates
        .mapIndexedNotNull { index, candidate ->
            val trimmed = candidate.trim()
            if (trimmed.isBlank()) return@mapIndexedNotNull null
            if (EndpointIdentity.isLikelyNodeTarget(trimmed) && !EndpointIdentity.isExplicitHttpUrl(trimmed)) {
                return@mapIndexedNotNull null
            }
            val normalized = normalizeEndpointServerUrl(trimmed)?.ifBlank { null } ?: trimmed.ifBlank { null }
            normalized?.let { Triple(endpointPriority(it), index, it) }
        }
        .distinctBy { it.third }
        .sortedWith(compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second })
        .map { it.third }
}

private fun splitEndpointCandidates(raw: String): List<String> = normalizeAndSortEndpointCandidates(
    raw
        .split(Regex("[,;\n]"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
)

private fun looksGeneratedDeviceId(value: String): Boolean {
    val trimmed = value.trim().lowercase()
    return trimmed.startsWith("android-") || trimmed.startsWith("ns-")
}

private fun parseAssociatedTokens(vararg rawValues: String): List<String> {
    return rawValues
        .flatMap { raw -> raw.split(Regex("[,;\n]")) }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

/**
 * Keep these helpers in network core so endpoint candidate normalization and ordering
 * stay identical across native Android runtime and any future JVM clients.
 */
fun buildEndpointCoreConfig(
    endpointUrlRaw: String,
    hubClientId: String,
    authToken: String,
    hubTokensRaw: String,
    deviceId: String,
    userIdOverride: String,
    userKeyOverride: String,
    namespaceOverride: String,
    rolesOverride: String,
    allowInsecureTls: Boolean,
    trustedCa: String,
    destinations: List<String>,
    configPath: String,
    storagePath: String,
    legacyBridgeEnabled: Boolean
): EndpointCoreConfig {
    val preferredHubRouteId = EndpointIdentity.bestRouteTarget(hubClientId).ifBlank {
        hubClientId.trim()
    }
    val derivedUserId = EndpointIdentity.bestRouteTarget(
        userIdOverride.ifBlank {
            hubClientId.ifBlank {
                deviceId
            }
        }
    ).ifBlank {
        userIdOverride.ifBlank {
            hubClientId.ifBlank {
                deviceId
            }
        }
    }

    val effectiveDeviceSource = deviceId
        .trim()
        .takeUnless { it.isBlank() || (looksGeneratedDeviceId(it) && preferredHubRouteId.isNotBlank()) }
        ?: preferredHubRouteId.ifBlank { deviceId }

    val derivedDeviceId = EndpointIdentity.bestRouteTarget(
        effectiveDeviceSource
    ).ifBlank {
        effectiveDeviceSource
    }

    val destinationEndpointCandidates = destinations.mapNotNull { normalizeEndpointServerUrl(it) }
    val endpointCandidates = normalizeAndSortEndpointCandidates(
        splitEndpointCandidates(endpointUrlRaw) + destinationEndpointCandidates
    )
    val normalizedEndpointUrl = endpointCandidates.joinToString(",").ifBlank {
        normalizeEndpointServerUrl(endpointUrlRaw).orEmpty()
    }
    val normalizedDispatchUrl = normalizeHubDispatchUrl(
        endpointCandidates.firstOrNull().orEmpty().ifBlank { endpointUrlRaw }
    ).orEmpty()
    val normalizedDestinations = destinations.mapNotNull {
        normalizeDestinationUrl(it, "/clipboard") ?: it.trim().ifBlank { null }
    }
    val tokenCandidates = parseAssociatedTokens(userKeyOverride, hubTokensRaw)

    return EndpointCoreConfig(
        endpointUrl = normalizedEndpointUrl,
        endpointCandidates = endpointCandidates,
        dispatchUrl = normalizedDispatchUrl,
        userId = derivedUserId,
        userKey = tokenCandidates.firstOrNull().orEmpty(),
        userKeys = tokenCandidates,
        airpadAuthToken = authToken.trim(),
        deviceId = derivedDeviceId,
        namespace = namespaceOverride.ifBlank { DEFAULT_ENDPOINT_NAMESPACE },
        roles = rolesOverride.ifBlank { DEFAULT_ENDPOINT_ROLES },
        allowInsecureTls = allowInsecureTls,
        trustedCa = trustedCa,
        destinations = normalizedDestinations,
        configPath = configPath,
        storagePath = storagePath,
        legacyBridgeEnabled = legacyBridgeEnabled || normalizedDispatchUrl.isNotBlank()
    )
}
