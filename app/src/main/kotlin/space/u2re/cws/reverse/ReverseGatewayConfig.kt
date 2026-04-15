package space.u2re.cws.reverse

import android.app.Application
import android.content.Context
import java.util.UUID

/**
 * Persisted configuration for the legacy/reverse gateway path.
 *
 * NOTE: this remains separate from `EndpointCoreConfig` because the daemon can
 * merge these values with newer v2 settings rather than replacing them outright.
 */
data class ReverseGatewayConfig(
    val enabled: Boolean,
    val endpointUrl: String,
    val userId: String,
    val userKey: String,
    val deviceId: String,
    val masterKey: String,
    val signingPrivateKeyPem: String,
    val peerPublicKeyPem: String,
    val namespace: String = "default",
    val roles: String = "endpoint,peer,node,app",
    val keepAliveIntervalMs: Long = 20_000L,
    val reconnectDelayMs: Long = 1_000L,
    val allowInsecureTls: Boolean = false,
    val trustedCa: String = ""
)

/** SharedPreferences-backed loader/saver for legacy bridge configuration. */
object ReverseGatewayConfigProvider {
    private const val PREF_NAME = "ioClientGateway"
    private const val PREF_ENABLED = "reverse_enabled"
    private const val PREF_ENDPOINT = "reverse_endpoint"
    private const val PREF_USER_ID = "reverse_user_id"
    private const val PREF_USER_KEY = "reverse_user_key"
    private const val PREF_DEVICE_ID = "reverse_device_id"
    private const val PREF_MASTER_KEY = "reverse_master_key"
    private const val PREF_SIGNING_KEY = "reverse_signing_private_key"
    private const val PREF_PEER_PUBLIC_KEY = "reverse_peer_public_key"
    private const val PREF_NAMESPACE = "reverse_namespace"
    private const val PREF_ROLES = "reverse_roles"
    private const val PREF_KEEPALIVE_MS = "reverse_keepalive_ms"
    private const val PREF_RECONNECT_MS = "reverse_reconnect_ms"
    private const val PREF_TRUSTED_CA = "reverse_trusted_ca"

    /** Load reverse-gateway preferences, generating a stable device id if needed. */
    fun load(application: Application): ReverseGatewayConfig {
        val prefs = application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val deviceId = prefs.getString(PREF_DEVICE_ID, null) ?: generateDeviceId().also { generated ->
            prefs.edit().putString(PREF_DEVICE_ID, generated).apply()
        }
        val keepAlive = prefs.getString(PREF_KEEPALIVE_MS, null)?.toLongOrNull() ?: 20_000L
        val reconnect = prefs.getString(PREF_RECONNECT_MS, null)?.toLongOrNull() ?: 1_000L

        return ReverseGatewayConfig(
            enabled = prefs.getBoolean(PREF_ENABLED, false),
            endpointUrl = prefs.getString(PREF_ENDPOINT, "") ?: "",
            userId = prefs.getString(PREF_USER_ID, "") ?: "",
            userKey = prefs.getString(PREF_USER_KEY, "") ?: "",
            deviceId = deviceId,
            masterKey = prefs.getString(PREF_MASTER_KEY, "") ?: "",
            signingPrivateKeyPem = prefs.getString(PREF_SIGNING_KEY, "") ?: "",
            peerPublicKeyPem = prefs.getString(PREF_PEER_PUBLIC_KEY, "") ?: "",
            namespace = prefs.getString(PREF_NAMESPACE, "default") ?: "default",
            roles = prefs.getString(PREF_ROLES, "endpoint,peer,node,app") ?: "endpoint,peer,node,app",
            keepAliveIntervalMs = if (keepAlive > 0L) keepAlive else 20_000L,
            reconnectDelayMs = if (reconnect > 0L) reconnect else 1_000L,
            trustedCa = prefs.getString(PREF_TRUSTED_CA, "") ?: ""
        )
    }

    /** Persist only the generated/updated device id without rewriting the rest of the config. */
    fun saveDeviceId(application: Application, deviceId: String) {
        application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DEVICE_ID, deviceId)
            .apply()
    }

    /** Persist the full reverse-gateway config back to SharedPreferences. */
    fun save(application: Application, config: ReverseGatewayConfig) {
        application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_ENABLED, config.enabled)
            .putString(PREF_ENDPOINT, config.endpointUrl.trim())
            .putString(PREF_USER_ID, config.userId.trim())
            .putString(PREF_USER_KEY, config.userKey.trim())
            .putString(PREF_MASTER_KEY, config.masterKey.trim())
            .putString(PREF_SIGNING_KEY, config.signingPrivateKeyPem.trim())
            .putString(PREF_PEER_PUBLIC_KEY, config.peerPublicKeyPem.trim())
            .putString(PREF_NAMESPACE, config.namespace.ifBlank { "default" })
            .putString(PREF_ROLES, config.roles.ifBlank { "endpoint,peer,node,app" })
            .putString(PREF_KEEPALIVE_MS, config.keepAliveIntervalMs.coerceAtLeast(1_000L).toString())
            .putString(PREF_RECONNECT_MS, config.reconnectDelayMs.coerceAtLeast(500L).toString())
            .putString(PREF_TRUSTED_CA, config.trustedCa.trim())
            .apply()
    }

    private fun generateDeviceId(): String = "android-${UUID.randomUUID()}"
}
