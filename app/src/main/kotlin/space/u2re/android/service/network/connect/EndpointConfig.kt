package space.u2re.cws.network

import space.u2re.cws.daemon.Settings
import space.u2re.cws.reverse.ReverseGatewayConfig

private const val DEFAULT_ENDPOINT_NAMESPACE = "default"
private const val DEFAULT_ENDPOINT_ROLES = "endpoint,peer,node,app"

data class EndpointCoreConfig(
    val endpointUrl: String,
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
    fun hasRemoteEndpoint(): Boolean = endpointUrl.isNotBlank()

    fun hasCredentials(): Boolean = userId.isNotBlank() && userKey.isNotBlank()

    fun isRemoteReady(): Boolean = hasRemoteEndpoint() && hasCredentials()
}

fun Settings.toEndpointCoreConfig(reverseConfig: ReverseGatewayConfig? = null): EndpointCoreConfig {
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

    val derivedDeviceId = EndpointIdentity.bestRouteTarget(
        reverseConfig?.deviceId
            .orEmpty()
            .ifBlank {
                hubClientId.ifBlank { deviceId }
            }
    ).ifBlank {
        reverseConfig?.deviceId
            .orEmpty()
            .ifBlank {
                hubClientId.ifBlank { deviceId }
            }
    }

    val endpointRaw = reverseConfig?.endpointUrl
        .orEmpty()
        .ifBlank { hubDispatchUrl }
    val normalizedEndpointUrl = normalizeEndpointServerUrl(endpointRaw).orEmpty()
    val normalizedDispatchUrl = normalizeHubDispatchUrl(endpointRaw).orEmpty()
    val normalizedDestinations = destinations.mapNotNull { normalizeDestinationUrl(it, "/clipboard") ?: it.trim().ifBlank { null } }

    return EndpointCoreConfig(
        endpointUrl = normalizedEndpointUrl,
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
