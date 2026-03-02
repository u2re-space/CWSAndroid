package space.u2re.service.daemon

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import java.util.UUID

data class Settings(
    val listenPortHttps: Int,
    val listenPortHttp: Int,
    val destinations: List<String>,
    val deviceId: String,
    val authToken: String,
    val hubClientId: String,
    val hubToken: String,
    val tlsEnabled: Boolean,
    val tlsKeystoreAssetPath: String,
    val tlsKeystoreType: String,
    val tlsKeystorePassword: String,
    val hubDispatchUrl: String,
    val allowInsecureTls: Boolean,
    val apiEndpoint: String,
    val apiKey: String,
    val clipboardSync: Boolean,
    val contactsSync: Boolean,
    val smsSync: Boolean,
    val shareTarget: Boolean,
    val logLevel: String,
    val syncIntervalSec: Int,
    val clipboardSyncIntervalSec: Int,
    val runDaemonForeground: Boolean,
    val runDaemonOnBoot: Boolean,
    val useAccessibilityService: Boolean,
    val showFloatingButton: Boolean,
    val quickActionCopyOnly: Boolean,
    val quickActionHandleImage: Boolean,
)

data class SettingsPatch(
    val listenPortHttps: Int? = null,
    val listenPortHttp: Int? = null,
    val destinations: List<String>? = null,
    val deviceId: String? = null,
    val authToken: String? = null,
    val hubClientId: String? = null,
    val hubToken: String? = null,
    val tlsEnabled: Boolean? = null,
    val tlsKeystoreAssetPath: String? = null,
    val tlsKeystoreType: String? = null,
    val tlsKeystorePassword: String? = null,
    val hubDispatchUrl: String? = null,
    val apiEndpoint: String? = null,
    val apiKey: String? = null,
    val allowInsecureTls: Boolean? = null,
    val clipboardSync: Boolean? = null,
    val contactsSync: Boolean? = null,
    val smsSync: Boolean? = null,
    val shareTarget: Boolean? = null,
    val logLevel: String? = null,
    val syncIntervalSec: Int? = null,
    val clipboardSyncIntervalSec: Int? = null,
    val runDaemonForeground: Boolean? = null,
    val runDaemonOnBoot: Boolean? = null,
    val useAccessibilityService: Boolean? = null,
    val showFloatingButton: Boolean? = null,
    val quickActionCopyOnly: Boolean? = null,
    val quickActionHandleImage: Boolean? = null,
)

private const val PREF_NAME = "settings_v1"
private const val PREF_NAME_LEGACY = "settings_v1_legacy"

private fun randomId(): String = "ns-${UUID.randomUUID().toString().replace("-", "").take(8)}"

private fun normalizePort(value: Int, fallback: Int): Int = if (value > 0) value else fallback

private fun defaultSettings(): Settings = Settings(
    listenPortHttps = 8443,
    listenPortHttp = 8080,
    destinations = emptyList(),
    deviceId = randomId(),
    authToken = "",
    hubClientId = "",
    hubToken = "",
    tlsEnabled = false,
    tlsKeystoreAssetPath = "",
    tlsKeystoreType = "PKCS12",
    tlsKeystorePassword = "",
    hubDispatchUrl = "",
    apiEndpoint = "",
    apiKey = "",
    allowInsecureTls = false,
    clipboardSync = true,
    contactsSync = false,
    smsSync = false,
    shareTarget = true,
    logLevel = "debug",
    syncIntervalSec = 60,
    clipboardSyncIntervalSec = 3,
    runDaemonForeground = true,
    runDaemonOnBoot = true,
    useAccessibilityService = false,
    showFloatingButton = false,
    quickActionCopyOnly = false,
    quickActionHandleImage = false
)

object SettingsStore {
    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Settings {
        return try {
            val raw = prefs(context).getString(PREF_NAME, null)
                ?: prefs(context).getString(PREF_NAME_LEGACY, null)
                ?: return defaultSettings()
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

    fun save(context: Context, next: Settings): Settings {
        val normalized = next.copy(
            listenPortHttp = normalizePort(next.listenPortHttp, defaultSettings().listenPortHttp),
            listenPortHttps = normalizePort(next.listenPortHttps, defaultSettings().listenPortHttps),
            syncIntervalSec = if (next.syncIntervalSec > 0) next.syncIntervalSec else defaultSettings().syncIntervalSec,
            clipboardSyncIntervalSec = if (next.clipboardSyncIntervalSec > 0) next.clipboardSyncIntervalSec else defaultSettings().clipboardSyncIntervalSec,
            runDaemonForeground = next.runDaemonForeground,
            runDaemonOnBoot = next.runDaemonOnBoot,
            useAccessibilityService = next.useAccessibilityService,
            showFloatingButton = next.showFloatingButton,
            quickActionCopyOnly = next.quickActionCopyOnly,
            quickActionHandleImage = next.quickActionHandleImage,
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

    fun update(context: Context, patch: SettingsPatch): Settings {
        val current = load(context)
        val merged = current.copy(
            listenPortHttps = patch.listenPortHttps ?: current.listenPortHttps,
            listenPortHttp = patch.listenPortHttp ?: current.listenPortHttp,
            destinations = patch.destinations ?: current.destinations,
            deviceId = patch.deviceId ?: current.deviceId,
            authToken = patch.authToken ?: current.authToken,
            hubClientId = patch.hubClientId ?: current.hubClientId,
            hubToken = patch.hubToken ?: current.hubToken,
            tlsEnabled = patch.tlsEnabled ?: current.tlsEnabled,
            tlsKeystoreAssetPath = patch.tlsKeystoreAssetPath ?: current.tlsKeystoreAssetPath,
            tlsKeystoreType = patch.tlsKeystoreType ?: current.tlsKeystoreType,
            tlsKeystorePassword = patch.tlsKeystorePassword ?: current.tlsKeystorePassword,
            hubDispatchUrl = patch.hubDispatchUrl ?: current.hubDispatchUrl,
            apiEndpoint = patch.apiEndpoint ?: current.apiEndpoint,
            apiKey = patch.apiKey ?: current.apiKey,
            allowInsecureTls = patch.allowInsecureTls ?: current.allowInsecureTls,
            clipboardSync = patch.clipboardSync ?: current.clipboardSync,
            contactsSync = patch.contactsSync ?: current.contactsSync,
            smsSync = patch.smsSync ?: current.smsSync,
            shareTarget = patch.shareTarget ?: current.shareTarget,
            logLevel = patch.logLevel ?: current.logLevel,
            syncIntervalSec = patch.syncIntervalSec ?: current.syncIntervalSec,
            clipboardSyncIntervalSec = patch.clipboardSyncIntervalSec ?: current.clipboardSyncIntervalSec,
            runDaemonForeground = patch.runDaemonForeground ?: current.runDaemonForeground,
            runDaemonOnBoot = patch.runDaemonOnBoot ?: current.runDaemonOnBoot,
            useAccessibilityService = patch.useAccessibilityService ?: current.useAccessibilityService,
            showFloatingButton = patch.showFloatingButton ?: current.showFloatingButton,
            quickActionCopyOnly = patch.quickActionCopyOnly ?: current.quickActionCopyOnly,
            quickActionHandleImage = patch.quickActionHandleImage ?: current.quickActionHandleImage,
        )
        return save(context, merged)
    }
}

private fun Settings.mergeFromMap(raw: Map<*, *>): Settings {
    val defaults = defaultSettings()
    return copy(
        listenPortHttps = (raw["listenPortHttps"] as? Number)?.toInt() ?: defaults.listenPortHttps,
        listenPortHttp = (raw["listenPortHttp"] as? Number)?.toInt() ?: defaults.listenPortHttp,
        destinations = ((raw["destinations"] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf { it.isNotBlank() } } ?: defaults.destinations),
        deviceId = raw["deviceId"] as? String ?: defaults.deviceId,
        authToken = raw["authToken"] as? String ?: defaults.authToken,
        hubClientId = raw["hubClientId"] as? String ?: defaults.hubClientId,
        hubToken = raw["hubToken"] as? String ?: defaults.hubToken,
        tlsEnabled = raw["tlsEnabled"] as? Boolean ?: defaults.tlsEnabled,
        tlsKeystoreAssetPath = raw["tlsKeystoreAssetPath"] as? String ?: defaults.tlsKeystoreAssetPath,
        tlsKeystoreType = raw["tlsKeystoreType"] as? String ?: defaults.tlsKeystoreType,
        tlsKeystorePassword = raw["tlsKeystorePassword"] as? String ?: defaults.tlsKeystorePassword,
        hubDispatchUrl = raw["hubDispatchUrl"] as? String ?: defaults.hubDispatchUrl,
        apiEndpoint = raw["apiEndpoint"] as? String ?: defaults.apiEndpoint,
        apiKey = raw["apiKey"] as? String ?: defaults.apiKey,
        allowInsecureTls = raw["allowInsecureTls"] as? Boolean ?: defaults.allowInsecureTls,
        clipboardSync = raw["clipboardSync"] as? Boolean ?: defaults.clipboardSync,
        contactsSync = raw["contactsSync"] as? Boolean ?: defaults.contactsSync,
        smsSync = raw["smsSync"] as? Boolean ?: defaults.smsSync,
        shareTarget = raw["shareTarget"] as? Boolean ?: defaults.shareTarget,
        logLevel = raw["logLevel"] as? String ?: defaults.logLevel,
        syncIntervalSec = (raw["syncIntervalSec"] as? Number)?.toInt() ?: defaults.syncIntervalSec,
        clipboardSyncIntervalSec = (raw["clipboardSyncIntervalSec"] as? Number)?.toInt() ?: defaults.clipboardSyncIntervalSec,
        runDaemonForeground = raw["runDaemonForeground"] as? Boolean ?: defaults.runDaemonForeground,
        runDaemonOnBoot = raw["runDaemonOnBoot"] as? Boolean ?: defaults.runDaemonOnBoot,
        useAccessibilityService = raw["useAccessibilityService"] as? Boolean ?: defaults.useAccessibilityService,
        showFloatingButton = raw["showFloatingButton"] as? Boolean ?: defaults.showFloatingButton,
        quickActionCopyOnly = raw["quickActionCopyOnly"] as? Boolean ?: defaults.quickActionCopyOnly,
        quickActionHandleImage = raw["quickActionHandleImage"] as? Boolean ?: defaults.quickActionHandleImage,
    )
}
