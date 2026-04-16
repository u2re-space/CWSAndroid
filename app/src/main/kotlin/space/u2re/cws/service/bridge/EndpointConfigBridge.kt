package space.u2re.cws.network

import space.u2re.cws.reverse.ReverseGatewayConfig
import space.u2re.cws.settings.Settings

fun Settings.toEndpointCoreConfig(reverseConfig: ReverseGatewayConfig? = null): EndpointCoreConfig {
    val endpointRaw = reverseConfig?.endpointUrl
        .orEmpty()
        .ifBlank { hubDispatchUrl }

    return buildEndpointCoreConfig(
        endpointUrlRaw = endpointRaw,
        hubClientId = hubClientId,
        authToken = hubToken.ifBlank { authToken },
        hubTokensRaw = hubTokens.ifBlank { hubToken },
        deviceId = reverseConfig?.deviceId.orEmpty().ifBlank { deviceId },
        userIdOverride = reverseConfig?.userId.orEmpty(),
        userKeyOverride = reverseConfig?.userKey.orEmpty().ifBlank { hubToken.ifBlank { authToken } },
        namespaceOverride = reverseConfig?.namespace.orEmpty(),
        rolesOverride = reverseConfig?.roles.orEmpty(),
        allowInsecureTls = allowInsecureTls || (reverseConfig?.allowInsecureTls == true),
        trustedCa = reverseConfig?.trustedCa.orEmpty().ifBlank { reverseTrustedCa },
        destinations = destinations,
        configPath = configPath,
        storagePath = storagePath,
        legacyBridgeEnabled = reverseConfig?.enabled == true
    )
}
