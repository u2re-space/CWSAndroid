package io.livekit.android.example.voiceassistant.reverse

import android.app.Application
import android.content.Context
import java.util.UUID

data class ReverseGatewayConfig(
    val enabled: Boolean,
    val endpointUrl: String,
    val userId: String,
    val userKey: String,
    val deviceId: String,
    val masterKey: String,
    val signingPrivateKeyPem: String,
    val peerPublicKeyPem: String
)

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

    fun load(application: Application): ReverseGatewayConfig {
        val prefs = application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val deviceId = prefs.getString(PREF_DEVICE_ID, null) ?: generateDeviceId().also { generated ->
            prefs.edit().putString(PREF_DEVICE_ID, generated).apply()
        }

        return ReverseGatewayConfig(
            enabled = prefs.getBoolean(PREF_ENABLED, false),
            endpointUrl = prefs.getString(PREF_ENDPOINT, "") ?: "",
            userId = prefs.getString(PREF_USER_ID, "") ?: "",
            userKey = prefs.getString(PREF_USER_KEY, "") ?: "",
            deviceId = deviceId,
            masterKey = prefs.getString(PREF_MASTER_KEY, "") ?: "",
            signingPrivateKeyPem = prefs.getString(PREF_SIGNING_KEY, "") ?: "",
            peerPublicKeyPem = prefs.getString(PREF_PEER_PUBLIC_KEY, "") ?: ""
        )
    }

    fun saveDeviceId(application: Application, deviceId: String) {
        application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DEVICE_ID, deviceId)
            .apply()
    }

    private fun generateDeviceId(): String = "android-${UUID.randomUUID()}"
}
