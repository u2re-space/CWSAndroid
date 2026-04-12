package space.u2re.cws.network

import space.u2re.cws.settings.Settings
import space.u2re.cws.reverse.ReverseGatewayConfig

private const val DEFAULT_ENDPOINT_NAMESPACE = "default"
private const val DEFAULT_ENDPOINT_ROLES = "endpoint,peer,node,app"

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
    fun hasRemoteEndpoint(): Boolean = endpointCandidates.isNotEmpty() || endpointUrl.isNotBlank()

    fun hasCredentials(): Boolean = userId.isNotBlank() && userKey.isNotBlank()

    fun isRemoteReady(): Boolean = hasRemoteEndpoint() && hasCredentials()
}

private fun splitEndpointCandidates(raw: String): List<String> {
    return raw
        .split(Regex("[,;\n]"))
        .mapNotNull { normalizeEndpointServerUrl(it)?.ifBlank { null } ?: it.trim().ifBlank { null } }
        .distinct()
}

private fun looksGeneratedDeviceId(value: String): Boolean {
    val trimmed = value.trim().lowercase()
    return trimmed.startsWith("android-") || trimmed.startsWith("ns-")
}

fun Settings.toEndpointCoreConfig(reverseConfig: ReverseGatewayConfig? = null): EndpointCoreConfig {
    val preferredHubRouteId = EndpointIdentity.bestRouteTarget(hubClientId).ifBlank {
        hubClientId.trim()
    }
    val derivedUserId = EndpointIdentity.bestRouteTarget(
        reverseConfig?.userId
            .orEmpty()
            .ifBlank {
                hubClientId.ifBlank {
                    authToken.ifBlank { deviceId }
                }
            }
    ).ifBlank {
        reverseConfig?.userId
            .orEmpty()
            .ifBlank {
                hubClientId.ifBlank {
                    authToken.ifBlank { deviceId }
                }
            }
    }

    val effectiveDeviceSource = reverseConfig?.deviceId
        .orEmpty()
        .trim()
        .takeUnless { it.isBlank() || (looksGeneratedDeviceId(it) && preferredHubRouteId.isNotBlank()) }
        ?: preferredHubRouteId.ifBlank { deviceId }

    val derivedDeviceId = EndpointIdentity.bestRouteTarget(
        effectiveDeviceSource
    ).ifBlank {
        effectiveDeviceSource
    }

    val endpointRaw = reverseConfig?.endpointUrl
        .orEmpty()
        .ifBlank { hubDispatchUrl }
    val endpointCandidates = splitEndpointCandidates(endpointRaw)
    val normalizedEndpointUrl = endpointCandidates.joinToString(",").ifBlank {
        normalizeEndpointServerUrl(endpointRaw).orEmpty()
    }
    val normalizedDispatchUrl = normalizeHubDispatchUrl(endpointCandidates.firstOrNull().orEmpty().ifBlank { endpointRaw }).orEmpty()
    val normalizedDestinations = destinations.mapNotNull { normalizeDestinationUrl(it, "/clipboard") ?: it.trim().ifBlank { null } }

    return EndpointCoreConfig(
        endpointUrl = normalizedEndpointUrl,
        endpointCandidates = endpointCandidates,
        dispatchUrl = normalizedDispatchUrl,
        userId = derivedUserId,
        userKey = reverseConfig?.userKey.orEmpty().ifBlank { hubToken.ifBlank { authToken } },
        deviceId = derivedDeviceId,
        namespace = reverseConfig?.namespace.orEmpty().ifBlank { DEFAULT_ENDPOINT_NAMESPACE },
        roles = reverseConfig?.roles.orEmpty().ifBlank { DEFAULT_ENDPOINT_ROLES },
        allowInsecureTls = allowInsecureTls || (reverseConfig?.allowInsecureTls == true),
        trustedCa = reverseConfig?.trustedCa.orEmpty().ifBlank { reverseTrustedCa },
        destinations = normalizedDestinations,
        configPath = configPath,
        storagePath = storagePath,
        legacyBridgeEnabled = (reverseConfig?.enabled == true) || normalizedDispatchUrl.isNotBlank()
    )
}
