package space.u2re.cws.network

import space.u2re.cws.reverse.ReverseGatewayConfig
import space.u2re.cws.settings.Settings

/**
 * Bridge from persisted app settings into the canonical network-core config.
 *
 * WHY: this is where UI-facing settings and optional reverse-gateway overrides
 * collapse into the exact endpoint/token/device/TLS inputs used by transport.
 */
fun Settings.toEndpointCoreConfig(reverseConfig: ReverseGatewayConfig? = null): EndpointCoreConfig {
    val endpointRaw = reverseConfig?.endpointUrl
        .orEmpty()
        .ifBlank { hubDispatchUrl }

    return buildEndpointCoreConfig(
        endpointUrlRaw = endpointRaw,
        hubClientId = hubClientId,
        authToken = hubToken.ifBlank { authToken },
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
