package space.u2re.cws.settings

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class Settings(
    val listenPortHttps: Int,
    val listenPortHttp: Int,
    val destinations: List<String>,
    val deviceId: String,
    val authToken: String,
    val hubClientId: String,
    val hubToken: String,
    val hubTokens: String,
    val tlsEnabled: Boolean,
    val tlsKeystoreAssetPath: String,
    val tlsKeystoreType: String,
    val tlsKeystorePassword: String,
    val hubDispatchUrl: String,
    val configPath: String,
    val allowInsecureTls: Boolean,
    val apiEndpoint: String,
    val apiKey: String,
    val clipboardSync: Boolean,
    val reverseTrustedCa: String,
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
    val enableLocalServer: Boolean,
    val storagePath: String,
)

@Serializable
data class SettingsPatch(
    val listenPortHttps: Int? = null,
    val listenPortHttp: Int? = null,
    val destinations: List<String>? = null,
    val deviceId: String? = null,
    val authToken: String? = null,
    val hubClientId: String? = null,
    val hubToken: String? = null,
    val hubTokens: String? = null,
    val tlsEnabled: Boolean? = null,
    val tlsKeystoreAssetPath: String? = null,
    val tlsKeystoreType: String? = null,
    val tlsKeystorePassword: String? = null,
    val hubDispatchUrl: String? = null,
    val configPath: String? = null,
    val apiEndpoint: String? = null,
    val apiKey: String? = null,
    val allowInsecureTls: Boolean? = null,
    val reverseTrustedCa: String? = null,
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
    val enableLocalServer: Boolean? = null,
    val storagePath: String? = null,
)

private const val PREF_NAME = "settings_v1"
private const val PREF_NAME_LEGACY = "settings_v1_legacy"
private const val DEFAULT_APP_CONFIG_ROOT = "/storage/emulated/0/AppConfig"
private val settingsJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private fun randomId(): String = "ns-${UUID.randomUUID().toString().replace("-", "").take(8)}"

private fun normalizePort(value: Int, fallback: Int): Int = if (value > 0) value else fallback
private fun appConfigPath(root: String): String = "fs:${root.trimEnd('/')}/config"
private fun appStoragePath(root: String): String = root.trimEnd('/')
private fun appTrustedCaPath(root: String): String = "fs:${root.trimEnd('/')}/https/rootCA.crt"
private fun splitMultiValueText(raw: String): List<String> =
    raw
        .split(Regex("[,;\n]"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

private fun normalizeMultiValueText(raw: String): String = splitMultiValueText(raw).joinToString("\n")

private fun jsonElementToAny(value: JsonElement): Any? {
    return when (value) {
        JsonNull -> null
        is JsonObject -> value.entries.associate { (key, nested) -> key to jsonElementToAny(nested) }
        is JsonArray -> value.map { nested -> jsonElementToAny(nested) }
        is JsonPrimitive -> {
            value.booleanOrNull
                ?: value.longOrNull
                ?: value.doubleOrNull
                ?: value.content
        }
    }
}

private fun parseUntypedMap(raw: String): Map<*, *> {
    return runCatching {
        when (val parsed = settingsJson.parseToJsonElement(raw)) {
            is JsonObject -> jsonElementToAny(parsed) as? Map<*, *> ?: emptyMap<String, Any>()
            else -> emptyMap<String, Any>()
        }
    }.getOrDefault(emptyMap<String, Any>())
}

private fun applyDefaultAppConfigPaths(settings: Settings, rootPath: String = DEFAULT_APP_CONFIG_ROOT): Settings {
    val normalizedRoot = rootPath.trim().ifBlank { DEFAULT_APP_CONFIG_ROOT }
    val nextConfigPath = settings.configPath.trim().ifBlank { appConfigPath(normalizedRoot) }
    val nextStoragePath = settings.storagePath.trim().ifBlank { appStoragePath(normalizedRoot) }
    val nextTrustedCa = settings.reverseTrustedCa.trim().ifBlank { appTrustedCaPath(normalizedRoot) }
    return settings.copy(configPath = nextConfigPath, storagePath = nextStoragePath, reverseTrustedCa = nextTrustedCa)
}

private fun defaultSettings(): Settings = Settings(
    listenPortHttps = 8443,
    listenPortHttp = 8080,
    destinations = emptyList(),
    deviceId = randomId(),
    authToken = "",
    hubClientId = "",
    hubToken = "",
    hubTokens = "",
    tlsEnabled = false,
    tlsKeystoreAssetPath = "",
    tlsKeystoreType = "PKCS12",
    tlsKeystorePassword = "",
    hubDispatchUrl = "",
    configPath = appConfigPath(DEFAULT_APP_CONFIG_ROOT),
    apiEndpoint = "",
    apiKey = "",
    allowInsecureTls = true,
    reverseTrustedCa = appTrustedCaPath(DEFAULT_APP_CONFIG_ROOT),
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
    quickActionHandleImage = false,
    enableLocalServer = true,
    storagePath = appStoragePath(DEFAULT_APP_CONFIG_ROOT)
)


fun Settings.resolve(): Settings {
    val resolveBasePath = if (this.storagePath.isNotBlank()) this.storagePath else this.configPath
    val context = ResolveContext(deviceId = this.deviceId, hubClientId = this.hubClientId, basePath = resolveBasePath)
    return this.copy(
        authToken = ConfigResolver.resolve(this.authToken, context),
        hubToken = ConfigResolver.resolve(this.hubToken, context),
        hubTokens = ConfigResolver.resolve(this.hubTokens, context),
        hubClientId = ConfigResolver.resolve(this.hubClientId, context),
        tlsKeystoreAssetPath = ConfigResolver.resolve(this.tlsKeystoreAssetPath, context),
        tlsKeystorePassword = ConfigResolver.resolve(this.tlsKeystorePassword, context),
        hubDispatchUrl = ConfigResolver.resolve(this.hubDispatchUrl, context),
        apiEndpoint = ConfigResolver.resolve(this.apiEndpoint, context),
        apiKey = ConfigResolver.resolve(this.apiKey, context),
            reverseTrustedCa = ConfigResolver.resolve(this.reverseTrustedCa, context),
        destinations = this.destinations.map { ConfigResolver.resolve(it, context) }
    )
}

object SettingsStore {
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Settings {
        val bootstrapRoot = AppConfigBootstrap.ensureStockConfig(context)
        val defaults = applyDefaultAppConfigPaths(defaultSettings(), bootstrapRoot)
        return try {
            val raw = prefs(context).getString(PREF_NAME, null)
                ?: prefs(context).getString(PREF_NAME_LEGACY, null)
            val parsed = raw?.let(::parseUntypedMap) ?: emptyMap<String, Any>()
            var merged = defaults.mergeFromMap(parsed)
            if (merged.hubTokens.isBlank() && merged.hubToken.isNotBlank()) {
                merged = merged.copy(hubTokens = merged.hubToken)
            }
            
            merged = applyDefaultAppConfigPaths(merged, bootstrapRoot)

            // Merge from config files if configPath is provided
            if (merged.configPath.isNotBlank()) {
                val basePath = merged.configPath.trim().removePrefix("fs:").removePrefix("file:")
                if (basePath.isNotBlank()) {
                    try {
                        val baseFile = ConfigResolver.resolveFile(basePath)
                        
                        val fileCandidates = if (baseFile.isDirectory) {
                            val names = listOf(
                                "clients.json",
                                "gateways.json",
                                "network.json",
                                "portable-core.json",
                                "portable-endpoint.json",
                                "portable.config.json",
                                "portable.config.110.json",
                                "portable.config.vds.json",
                                "config.json",
                                "package.json"
                            )
                            buildList {
                                addAll(names.map { java.io.File(baseFile, it) })
                                val nestedConfig = java.io.File(baseFile, "config")
                                if (nestedConfig.exists() && nestedConfig.isDirectory) {
                                    addAll(names.map { java.io.File(nestedConfig, it) })
                                }
                            }
                        } else {
                            listOf(baseFile)
                        }
                        
                        for (fileCandidate in fileCandidates) {
                            if (fileCandidate.exists() && fileCandidate.isFile) {
                                val raw = fileCandidate.readText()
                                val parsed = parseUntypedMap(raw)
                                val fromClients = defaults.mergeFromMap(parsed)
                                
                                merged = merged.copy(
                                    hubDispatchUrl = merged.hubDispatchUrl.ifBlank { fromClients.hubDispatchUrl },
                                    authToken = merged.authToken.ifBlank { fromClients.authToken },
                                    hubToken = merged.hubToken.ifBlank { fromClients.hubToken },
                                    hubTokens = merged.hubTokens.ifBlank { fromClients.hubTokens.ifBlank { fromClients.hubToken } },
                                    hubClientId = merged.hubClientId.ifBlank { fromClients.hubClientId },
                                    apiEndpoint = merged.apiEndpoint.ifBlank { fromClients.apiEndpoint },
                                    apiKey = merged.apiKey.ifBlank { fromClients.apiKey },
                                    reverseTrustedCa = merged.reverseTrustedCa.ifBlank { fromClients.reverseTrustedCa },
                                    destinations = if (merged.destinations.isEmpty()) fromClients.destinations else merged.destinations
                                )
                            }
                        }

                        val gatewaysFile = if (baseFile.isDirectory) java.io.File(baseFile, "gateways.json") else null
                        if (gatewaysFile?.exists() == true && gatewaysFile.isFile) {
                            val gatewaysRaw = gatewaysFile.readText()
                            val gatewaysParsed = parseUntypedMap(gatewaysRaw)
                            val gatewaysList = (gatewaysParsed?.get("gateways") as? List<*>)?.mapNotNull { it?.toString() }
                                ?: (gatewaysParsed?.get("destinations") as? List<*>)?.mapNotNull { it?.toString() }
                                ?: emptyList()
                            if (gatewaysList.isNotEmpty() && merged.destinations.isEmpty()) {
                                merged = merged.copy(destinations = gatewaysList)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore errors reading configs
                    }
                }
            }
            
            save(context, merged)
            merged
        } catch (_: Exception) {
            defaults
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
            reverseTrustedCa = next.reverseTrustedCa.trim(),
            enableLocalServer = next.enableLocalServer,
            storagePath = next.storagePath,
            tlsEnabled = next.tlsEnabled,
            tlsKeystoreAssetPath = next.tlsKeystoreAssetPath.ifBlank { defaultSettings().tlsKeystoreAssetPath },
            tlsKeystoreType = next.tlsKeystoreType.ifBlank { defaultSettings().tlsKeystoreType },
            tlsKeystorePassword = next.tlsKeystorePassword,
            hubToken = splitMultiValueText(next.hubTokens).firstOrNull().orEmpty().ifBlank { next.hubToken.trim() },
            hubTokens = normalizeMultiValueText(next.hubTokens.ifBlank { next.hubToken }),
            destinations = next.destinations.filter { it.isNotBlank() },
            configPath = next.configPath,
            logLevel = when (next.logLevel.lowercase()) {
                "debug", "info", "warn", "error" -> next.logLevel.lowercase()
                else -> defaultSettings().logLevel
            }
        )
        prefs(context).edit().putString(PREF_NAME, settingsJson.encodeToString(normalized)).apply()
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
            hubTokens = patch.hubTokens ?: current.hubTokens,
            tlsEnabled = patch.tlsEnabled ?: current.tlsEnabled,
            tlsKeystoreAssetPath = patch.tlsKeystoreAssetPath ?: current.tlsKeystoreAssetPath,
            tlsKeystoreType = patch.tlsKeystoreType ?: current.tlsKeystoreType,
            tlsKeystorePassword = patch.tlsKeystorePassword ?: current.tlsKeystorePassword,
            hubDispatchUrl = patch.hubDispatchUrl ?: current.hubDispatchUrl,
            configPath = patch.configPath ?: current.configPath,
            apiEndpoint = patch.apiEndpoint ?: current.apiEndpoint,
            apiKey = patch.apiKey ?: current.apiKey,
            allowInsecureTls = patch.allowInsecureTls ?: current.allowInsecureTls,
            reverseTrustedCa = patch.reverseTrustedCa ?: current.reverseTrustedCa,
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
            enableLocalServer = patch.enableLocalServer ?: current.enableLocalServer,
            storagePath = patch.storagePath ?: current.storagePath,
        )
        return save(context, merged)
    }
}

private fun Settings.mergeFromMap(raw: Map<*, *>): Settings {
    val defaults = defaultSettings()
    val mergedSource = HashMap<Any?, Any?>(raw.size + 8)
    for (entry in raw.entries) {
        mergedSource[entry.key] = entry.value
    }
    val cwsAlias = raw["cws"] as? Map<*, *>
    if (cwsAlias != null) {
        for (entry in cwsAlias.entries) {
            if (entry.key is String && !mergedSource.containsKey(entry.key)) {
                mergedSource[entry.key] = entry.value
            }
        }
    }
    val coreAlias = raw["core"] as? Map<*, *>
    if (coreAlias != null) {
        val coreAliases = mapOf(
            "endpointUrl" to "endpointUrl",
            "userId" to "userId",
            "userKey" to "userKey",
            "preferBackendSync" to "preferBackendSync",
            "encrypt" to "encrypt"
        )
        for ((from, to) in coreAliases) {
            if (!mergedSource.containsKey(to) && coreAlias.containsKey(from)) {
                mergedSource[to] = coreAlias[from]
            }
        }
        val coreOpsAlias = coreAlias["ops"] as? Map<*, *>
        if (coreOpsAlias != null) {
            if (!mergedSource.containsKey("allowInsecureTls") && coreOpsAlias.containsKey("allowInsecureTls")) {
                mergedSource["allowInsecureTls"] = coreOpsAlias["allowInsecureTls"]
            }
        }
    }
    val launcherEnvAlias = raw["launcherEnv"] as? Map<*, *>
    if (launcherEnvAlias != null) {
        for (entry in launcherEnvAlias.entries) {
            val key = entry.key as? String ?: continue
            if (!mergedSource.containsKey(key)) {
                mergedSource[key] = entry.value
            }
        }
    }
    val aiAlias = raw["ai"] as? Map<*, *>
    if (aiAlias != null) {
        if (!mergedSource.containsKey("apiEndpoint") && aiAlias.containsKey("baseUrl")) {
            mergedSource["apiEndpoint"] = aiAlias["baseUrl"]
        }
        if (!mergedSource.containsKey("apiKey") && aiAlias.containsKey("apiKey")) {
            mergedSource["apiKey"] = aiAlias["apiKey"]
        }
    }

    val pickString: (Map<Any?, Any?>, Iterable<String>) -> String? = { source, keys ->
        var result: String? = null
        for (key in keys) {
            val value = source[key]
            val text = value?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                result = text
                break
            }
        }
        result
    }

    val pickStringList: (Map<Any?, Any?>, Iterable<String>) -> List<String> = { source, keys ->
        var result: List<String>? = null
        for (key in keys) {
            val value = source[key]
            if (value is List<*>) {
                val mapped = value.mapNotNull { it?.toString()?.trim()?.takeIf { it.isNotBlank() } }
                if (mapped.isNotEmpty()) {
                    result = mapped
                    break
                }
            }
        }
        result ?: defaults.destinations
    }

    val pickBoolean: (Map<Any?, Any?>, Iterable<String>) -> Boolean? = { source, keys ->
        var result: Boolean? = null
        for (key in keys) {
            result = when (val value = source[key]) {
                is Boolean -> value
                is String -> when (value.trim().lowercase()) {
                    "1", "true", "yes", "on" -> true
                    "0", "false", "no", "off" -> false
                    else -> null
                }
                else -> null
            }
            if (result != null) {
                break
            }
        }
        result
    }

    val extractRemoteTargetUrls: (Any?) -> List<String> = { value ->
        when (value) {
            is List<*> -> value.mapNotNull { entry ->
                when (entry) {
                    is String -> entry.trim().ifBlank { null }
                    is Map<*, *> -> entry["url"]?.toString()?.trim()?.ifBlank { null }
                    else -> null
                }
            }
            else -> emptyList()
        }
    }

    val extractEndpointCandidates: (Any?) -> List<String> = { value ->
        when (value) {
            is String -> value
                .split(Regex("[,;\n]"))
                .mapNotNull { it.trim().ifBlank { null } }
            is List<*> -> value.mapNotNull { entry ->
                when (entry) {
                    is String -> entry.trim().ifBlank { null }
                    is Map<*, *> -> entry["url"]?.toString()?.trim()?.ifBlank { null }
                        ?: entry["origin"]?.toString()?.trim()?.ifBlank { null }
                        ?: entry["endpointUrl"]?.toString()?.trim()?.ifBlank { null }
                    else -> null
                }
            }
            else -> emptyList()
        }
    }

    val opsTargets = buildList {
        val ops = coreAlias?.get("ops") as? Map<*, *>
        if (ops != null) {
            addAll(extractRemoteTargetUrls(ops["httpTargets"]))
            addAll(extractRemoteTargetUrls(ops["wsTargets"]))
            addAll(extractRemoteTargetUrls(ops["syncTargets"]))
        }
    }.distinct()

    val networkAlias = raw["network"] as? Map<*, *>
    val endpointAlias = raw["endpoint"] as? Map<*, *>
    val bridgeAlias = coreAlias?.get("bridge") as? Map<*, *>
    val endpointCandidatesText = buildList {
        addAll(extractEndpointCandidates(mergedSource["hubDispatchUrl"]))
        addAll(extractEndpointCandidates(mergedSource["endpointUrl"]))
        addAll(extractEndpointCandidates(mergedSource["CWS_BRIDGE_ENDPOINTS"]))
        addAll(extractEndpointCandidates(mergedSource["CWS_BRIDGE_ENDPOINT_URL"]))
        addAll(extractEndpointCandidates(raw["endpoints"]))
        addAll(extractEndpointCandidates(networkAlias?.get("endpoints")))
        addAll(extractEndpointCandidates(endpointAlias?.get("endpoints")))
        addAll(extractEndpointCandidates(bridgeAlias?.get("endpoints")))
    }.distinct().joinToString(",")

    val splitCandidateText: (String?) -> List<String> = { value ->
        value
            ?.split(Regex("[,;\n]"))
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    val explicitHubDispatchUrl = pickString(
        mergedSource,
        listOf(
            "hubDispatchUrl",
            "gatewayUrl",
            "dispatchUrl",
            "endpointUrl",
            "CWS_HUB_URL",
            "CWS_ENDPOINT_URL",
            "CWS_BRIDGE_ENDPOINT_URL"
        )
    )
    val explicitHubDispatchCandidates = splitCandidateText(explicitHubDispatchUrl)
    val shouldPromoteEndpointCandidates =
        explicitHubDispatchCandidates.size <= 1 &&
            endpointCandidatesText.isNotBlank() &&
            explicitHubDispatchUrl?.trim() != endpointCandidatesText

    return copy(
        listenPortHttps = (mergedSource["listenPortHttps"] as? Number)?.toInt() ?: defaults.listenPortHttps,
        listenPortHttp = (mergedSource["listenPortHttp"] as? Number)?.toInt() ?: defaults.listenPortHttp,
        destinations = pickStringList(mergedSource, listOf("destinations", "targets", "peers")).ifEmpty {
            if (opsTargets.isNotEmpty()) opsTargets else defaults.destinations
        },
        deviceId = pickString(mergedSource, listOf("deviceId", "device_id", "clientDeviceId", "CWS_DEVICE_ID")) ?: defaults.deviceId,
        authToken = pickString(
            mergedSource,
            listOf("authToken", "apiToken", "CWS_AUTH_TOKEN", "clientSecret")
        ) ?: defaults.authToken,
        hubClientId = pickString(
            mergedSource,
            listOf(
                "hubClientId",
                "CWS_ASSOCIATED_ID",
                "clientId",
                "associatedId",
                "userId",
                "nodeId",
                "deviceName",
                "CWS_BRIDGE_USER_ID",
                "user-id"
            )
        ) ?: defaults.hubClientId,
        hubToken = pickString(
            mergedSource,
            listOf("hubToken", "CWS_ASSOCIATED_TOKEN", "userKey", "CWS_BRIDGE_USER_KEY", "token", "clientToken")
        ) ?: defaults.hubToken,
        hubTokens = normalizeMultiValueText(
            pickString(
                mergedSource,
                listOf("hubTokens", "associatedClientTokens", "CWS_ASSOCIATED_TOKENS", "CWS_BRIDGE_USER_KEYS")
            )
                ?: ((mergedSource["tokens"] as? List<*>)?.joinToString("\n") { it?.toString()?.trim().orEmpty() })
                ?: pickString(
                    mergedSource,
                    listOf("hubToken", "CWS_ASSOCIATED_TOKEN", "userKey", "CWS_BRIDGE_USER_KEY", "token", "clientToken")
                ).orEmpty()
        ),
        tlsEnabled = mergedSource["tlsEnabled"] as? Boolean ?: defaults.tlsEnabled,
        tlsKeystoreAssetPath = (mergedSource["tlsKeystoreAssetPath"] as? String) ?: defaults.tlsKeystoreAssetPath,
        tlsKeystoreType = (mergedSource["tlsKeystoreType"] as? String) ?: defaults.tlsKeystoreType,
        tlsKeystorePassword = (mergedSource["tlsKeystorePassword"] as? String) ?: defaults.tlsKeystorePassword,
        hubDispatchUrl = if (explicitHubDispatchUrl == null || shouldPromoteEndpointCandidates) {
            endpointCandidatesText.ifBlank { defaults.hubDispatchUrl }
        } else {
            explicitHubDispatchUrl
        },
        apiEndpoint = pickString(mergedSource, listOf("apiEndpoint", "apiUrl", "llmEndpoint")) ?: defaults.apiEndpoint,
        apiKey = pickString(mergedSource, listOf("apiKey", "api_token", "apiToken")) ?: defaults.apiKey,
        allowInsecureTls = pickBoolean(mergedSource, listOf("allowInsecureTls")) ?: defaults.allowInsecureTls,
        reverseTrustedCa = pickString(
            mergedSource,
            listOf("reverseTrustedCa", "reverseTrustedCertificate", "reverseTrustedCA", "reverseCa", "reverseCaPem", "CWS_REVERSE_CA", "CWS_HTTPS_CA")
        ) ?: defaults.reverseTrustedCa,
        clipboardSync = mergedSource["clipboardSync"] as? Boolean ?: defaults.clipboardSync,
        contactsSync = mergedSource["contactsSync"] as? Boolean ?: defaults.contactsSync,
        smsSync = mergedSource["smsSync"] as? Boolean ?: defaults.smsSync,
        shareTarget = mergedSource["shareTarget"] as? Boolean ?: defaults.shareTarget,
        logLevel = mergedSource["logLevel"] as? String ?: defaults.logLevel,
        syncIntervalSec = (mergedSource["syncIntervalSec"] as? Number)?.toInt() ?: defaults.syncIntervalSec,
        clipboardSyncIntervalSec = (mergedSource["clipboardSyncIntervalSec"] as? Number)?.toInt() ?: defaults.clipboardSyncIntervalSec,
        runDaemonForeground = mergedSource["runDaemonForeground"] as? Boolean ?: defaults.runDaemonForeground,
        runDaemonOnBoot = mergedSource["runDaemonOnBoot"] as? Boolean ?: defaults.runDaemonOnBoot,
        useAccessibilityService = mergedSource["useAccessibilityService"] as? Boolean ?: defaults.useAccessibilityService,
        showFloatingButton = mergedSource["showFloatingButton"] as? Boolean ?: defaults.showFloatingButton,
        quickActionCopyOnly = mergedSource["quickActionCopyOnly"] as? Boolean ?: defaults.quickActionCopyOnly,
        quickActionHandleImage = mergedSource["quickActionHandleImage"] as? Boolean ?: defaults.quickActionHandleImage,
        enableLocalServer = mergedSource["enableLocalServer"] as? Boolean ?: defaults.enableLocalServer,
        storagePath = (mergedSource["storagePath"] as? String) ?: defaults.storagePath,
    )
}
