package space.u2re.cws.network

private const val DEFAULT_ENDPOINT_NAMESPACE = "default"
private const val DEFAULT_ENDPOINT_ROLES = "endpoint,peer,node,app"

/**
 * Resolved network configuration consumed by the Android v2 transport stack.
 *
 * It combines endpoint candidates, credentials, device identity, TLS options,
 * storage/config roots, and the legacy-bridge toggle into one immutable view.
 */
data class EndpointCoreConfig(
    val endpointUrl: String,
    val endpointCandidates: List<String>,
    val dispatchUrl: String,
    val userId: String,
    val userKey: String,
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
    /** True when at least one remote endpoint candidate exists. */
    fun hasRemoteEndpoint(): Boolean = endpointCandidates.isNotEmpty() || endpointUrl.isNotBlank()

    /** True when user identity plus credential material is present. */
    fun hasCredentials(): Boolean = userId.isNotBlank() && userKey.isNotBlank()

    /** Minimum readiness gate used before socket/http clients attempt to dial the backend. */
    fun isRemoteReady(): Boolean = hasRemoteEndpoint() && hasCredentials()
}

private fun normalizeAndSortEndpointCandidates(rawCandidates: List<String>): List<String> {
    fun candidatePriority(url: String): Int {
        val host = runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        return when (host) {
            "192.168.0.200", "192.168.0.201" -> 0
            "192.168.0.110", "192.168.0.111" -> 1
            "192.168.0.196", "45.150.9.153", "100.99.178.6" -> 2
            "45.147.121.152" -> 3
            else -> 9
        }
    }
    fun candidateHost(url: String): String = runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
    return rawCandidates
        .mapNotNull { candidate ->
            val trimmed = candidate.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            if (EndpointIdentity.isLikelyNodeTarget(trimmed) && !EndpointIdentity.isExplicitHttpUrl(trimmed)) {
                return@mapNotNull null
            }
            normalizeEndpointServerUrl(trimmed)?.ifBlank { null } ?: trimmed.ifBlank { null }
        }
        .sortedWith(compareBy<String> { candidatePriority(it) }.thenBy { candidateHost(it) }.thenBy { it.lowercase() })
        .distinct()
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

/**
 * Build the canonical Android network config from settings/runtime inputs.
 *
 * WHY: candidate ordering, route-target normalization, TLS flags, and bridge
 * enablement must be resolved once here so every transport layer behaves
 * consistently during reconnect and fallback.
 */
fun buildEndpointCoreConfig(
    endpointUrlRaw: String,
    hubClientId: String,
    authToken: String,
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
                authToken.ifBlank { deviceId }
            }
        }
    ).ifBlank {
        userIdOverride.ifBlank {
            hubClientId.ifBlank {
                authToken.ifBlank { deviceId }
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

    return EndpointCoreConfig(
        endpointUrl = normalizedEndpointUrl,
        endpointCandidates = endpointCandidates,
        dispatchUrl = normalizedDispatchUrl,
        userId = derivedUserId,
        userKey = userKeyOverride.ifBlank { authToken },
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
