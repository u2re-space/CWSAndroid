package space.u2re.service.daemon

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import java.util.UUID

data class AutomataSettings(
    val listenPortHttps: Int,
    val listenPortHttp: Int,
    val destinations: List<String>,
    val deviceId: String,
    val authToken: String,
    val tlsEnabled: Boolean,
    val tlsKeystoreAssetPath: String,
    val tlsKeystoreType: String,
    val tlsKeystorePassword: String,
    val hubDispatchUrl: String,
    val allowInsecureTls: Boolean,
    val clipboardSync: Boolean,
    val contactsSync: Boolean,
    val smsSync: Boolean,
    val shareTarget: Boolean,
    val logLevel: String,
    val syncIntervalSec: Int,
)

data class AutomataSettingsPatch(
    val listenPortHttps: Int? = null,
    val listenPortHttp: Int? = null,
    val destinations: List<String>? = null,
    val deviceId: String? = null,
    val authToken: String? = null,
    val tlsEnabled: Boolean? = null,
    val tlsKeystoreAssetPath: String? = null,
    val tlsKeystoreType: String? = null,
    val tlsKeystorePassword: String? = null,
    val hubDispatchUrl: String? = null,
    val allowInsecureTls: Boolean? = null,
    val clipboardSync: Boolean? = null,
    val contactsSync: Boolean? = null,
    val smsSync: Boolean? = null,
    val shareTarget: Boolean? = null,
    val logLevel: String? = null,
    val syncIntervalSec: Int? = null,
)

private const val PREF_NAME = "automata_settings_v1"

private fun randomId(): String = "ns-${UUID.randomUUID().toString().replace("-", "").take(8)}"

private fun normalizePort(value: Int, fallback: Int): Int = if (value > 0) value else fallback

private fun defaultSettings(): AutomataSettings = AutomataSettings(
    listenPortHttps = 8443,
    listenPortHttp = 8080,
    destinations = emptyList(),
    deviceId = randomId(),
    authToken = "",
    tlsEnabled = false,
    tlsKeystoreAssetPath = "",
    tlsKeystoreType = "PKCS12",
    tlsKeystorePassword = "",
    hubDispatchUrl = "",
    allowInsecureTls = false,
    clipboardSync = true,
    contactsSync = false,
    smsSync = false,
    shareTarget = true,
    logLevel = "debug",
    syncIntervalSec = 60
)

object AutomataSettingsStore {
    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): AutomataSettings {
        return try {
            val raw = prefs(context).getString(PREF_NAME, null) ?: return defaultSettings()
            val parsed = gson.fromJson(raw, Map::class.java) as? Map<*, *> ?: emptyMap<String, Any>()
            val merged = defaultSettings().mergeFromMap(parsed)
            // Migration: carry legacy userKey into authToken if needed.
            val migrated = if (merged.authToken.isBlank() && parsed["userKey"] is String) {
                merged.copy(authToken = (parsed["userKey"] as? String) ?: merged.authToken)
            } else {
                merged
            }
            save(context, migrated)
            migrated
        } catch (_: Exception) {
            defaultSettings()
        }
    }

    fun save(context: Context, next: AutomataSettings): AutomataSettings {
        val normalized = next.copy(
            listenPortHttp = normalizePort(next.listenPortHttp, defaultSettings().listenPortHttp),
            listenPortHttps = normalizePort(next.listenPortHttps, defaultSettings().listenPortHttps),
            syncIntervalSec = if (next.syncIntervalSec > 0) next.syncIntervalSec else defaultSettings().syncIntervalSec,
            tlsEnabled = next.tlsEnabled,
            tlsKeystoreAssetPath = next.tlsKeystoreAssetPath.ifBlank { defaultSettings().tlsKeystoreAssetPath },
            tlsKeystoreType = next.tlsKeystoreType.ifBlank { defaultSettings().tlsKeystoreType },
            tlsKeystorePassword = next.tlsKeystorePassword,
            destinations = next.destinations.filter { it.isNotBlank() },
            logLevel = when (next.logLevel.lowercase()) {
                "debug", "info", "warn", "error" -> next.logLevel.lowercase()
                else -> defaultSettings().logLevel
            }
        )
        prefs(context).edit().putString(PREF_NAME, gson.toJson(normalized)).apply()
        return normalized
    }

    fun update(context: Context, patch: AutomataSettingsPatch): AutomataSettings {
        val current = load(context)
        val merged = current.copy(
            listenPortHttps = patch.listenPortHttps ?: current.listenPortHttps,
            listenPortHttp = patch.listenPortHttp ?: current.listenPortHttp,
            destinations = patch.destinations ?: current.destinations,
            deviceId = patch.deviceId ?: current.deviceId,
            authToken = patch.authToken ?: current.authToken,
            tlsEnabled = patch.tlsEnabled ?: current.tlsEnabled,
            tlsKeystoreAssetPath = patch.tlsKeystoreAssetPath ?: current.tlsKeystoreAssetPath,
            tlsKeystoreType = patch.tlsKeystoreType ?: current.tlsKeystoreType,
            tlsKeystorePassword = patch.tlsKeystorePassword ?: current.tlsKeystorePassword,
            hubDispatchUrl = patch.hubDispatchUrl ?: current.hubDispatchUrl,
            allowInsecureTls = patch.allowInsecureTls ?: current.allowInsecureTls,
            clipboardSync = patch.clipboardSync ?: current.clipboardSync,
            contactsSync = patch.contactsSync ?: current.contactsSync,
            smsSync = patch.smsSync ?: current.smsSync,
            shareTarget = patch.shareTarget ?: current.shareTarget,
            logLevel = patch.logLevel ?: current.logLevel,
            syncIntervalSec = patch.syncIntervalSec ?: current.syncIntervalSec,
        )
        return save(context, merged)
    }
}

private fun AutomataSettings.mergeFromMap(raw: Map<*, *>): AutomataSettings {
    val defaults = defaultSettings()
    return copy(
        listenPortHttps = (raw["listenPortHttps"] as? Number)?.toInt() ?: defaults.listenPortHttps,
        listenPortHttp = (raw["listenPortHttp"] as? Number)?.toInt() ?: defaults.listenPortHttp,
        destinations = ((raw["destinations"] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf { it.isNotBlank() } } ?: defaults.destinations),
        deviceId = raw["deviceId"] as? String ?: defaults.deviceId,
        authToken = raw["authToken"] as? String ?: defaults.authToken,
        tlsEnabled = raw["tlsEnabled"] as? Boolean ?: defaults.tlsEnabled,
        tlsKeystoreAssetPath = raw["tlsKeystoreAssetPath"] as? String ?: defaults.tlsKeystoreAssetPath,
        tlsKeystoreType = raw["tlsKeystoreType"] as? String ?: defaults.tlsKeystoreType,
        tlsKeystorePassword = raw["tlsKeystorePassword"] as? String ?: defaults.tlsKeystorePassword,
        hubDispatchUrl = raw["hubDispatchUrl"] as? String ?: defaults.hubDispatchUrl,
        allowInsecureTls = raw["allowInsecureTls"] as? Boolean ?: defaults.allowInsecureTls,
        clipboardSync = raw["clipboardSync"] as? Boolean ?: defaults.clipboardSync,
        contactsSync = raw["contactsSync"] as? Boolean ?: defaults.contactsSync,
        smsSync = raw["smsSync"] as? Boolean ?: defaults.smsSync,
        shareTarget = raw["shareTarget"] as? Boolean ?: defaults.shareTarget,
        logLevel = raw["logLevel"] as? String ?: defaults.logLevel,
        syncIntervalSec = (raw["syncIntervalSec"] as? Number)?.toInt() ?: defaults.syncIntervalSec,
    )
}
