package space.u2re.cws.screen

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.u2re.cws.daemon.DaemonController
import space.u2re.cws.daemon.Settings
import space.u2re.cws.daemon.SettingsPatch
import space.u2re.cws.daemon.SettingsStore
import space.u2re.cws.agent.sendResponsesRequest
import space.u2re.cws.daemon.DaemonForegroundService
import space.u2re.cws.overlay.FloatingButtonService
import space.u2re.cws.accessibility.ClipboardAccessibilityService
import space.u2re.cws.network.normalizeHubDispatchUrl
import space.u2re.cws.network.normalizeResponsesEndpoint
import space.u2re.cws.network.postJson

@Composable
fun SettingsScreen(
    navigateBack: () -> Unit
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as Application }
    val scope = rememberCoroutineScope()
    val settings: Settings = SettingsStore.load(app)

    var destinationText by rememberSaveable { mutableStateOf(settings.destinations.joinToString("\n")) }
    var hubDispatchUrl by rememberSaveable { mutableStateOf(settings.hubDispatchUrl) }
    var configPath by rememberSaveable { mutableStateOf(settings.configPath) }
    var storagePath by rememberSaveable { mutableStateOf(settings.storagePath) }
    var hubClientId by rememberSaveable { mutableStateOf(settings.hubClientId.ifBlank { settings.deviceId }) }
    var allowInsecure by rememberSaveable { mutableStateOf(settings.allowInsecureTls) }
    var apiEndpoint by rememberSaveable { mutableStateOf(settings.apiEndpoint) }
    var apiKey by rememberSaveable { mutableStateOf(settings.apiKey) }
    var shareTarget by rememberSaveable { mutableStateOf(settings.shareTarget) }
    var clipboardSync by rememberSaveable { mutableStateOf(settings.clipboardSync) }
    var contactsSync by rememberSaveable { mutableStateOf(settings.contactsSync) }
    var smsSync by rememberSaveable { mutableStateOf(settings.smsSync) }
    var syncInterval by rememberSaveable { mutableStateOf(settings.syncIntervalSec.toString()) }
    var clipboardSyncInterval by rememberSaveable { mutableStateOf(settings.clipboardSyncIntervalSec.toString()) }
    var runDaemonForeground by rememberSaveable { mutableStateOf(settings.runDaemonForeground) }
    var runDaemonOnBoot by rememberSaveable { mutableStateOf(settings.runDaemonOnBoot) }
    var useAccessibilityService by rememberSaveable { mutableStateOf(settings.useAccessibilityService) }
    var accessibilityServiceEnabled by rememberSaveable { mutableStateOf(isClipboardAccessibilityEnabled(context)) }
    var showFloatingButton by rememberSaveable { mutableStateOf(settings.showFloatingButton) }
    var overlayPermissionGranted by rememberSaveable { mutableStateOf(isFloatingOverlayEnabled(context)) }
    var allFilesAccessGranted by rememberSaveable { mutableStateOf(isAllFilesAccessGranted(context)) }
    var quickActionCopyOnly by rememberSaveable { mutableStateOf(settings.quickActionCopyOnly) }
    var quickActionHandleImage by rememberSaveable { mutableStateOf(settings.quickActionHandleImage) }

    var listenPortHttp by rememberSaveable { mutableStateOf(settings.listenPortHttp.toString()) }
    var listenPortHttps by rememberSaveable { mutableStateOf(settings.listenPortHttps.toString()) }
    var enableLocalServer by rememberSaveable { mutableStateOf(settings.enableLocalServer) }
    var authToken by rememberSaveable { mutableStateOf(settings.authToken) }
    var tlsEnabled by rememberSaveable { mutableStateOf(settings.tlsEnabled) }
    var tlsKeystorePath by rememberSaveable { mutableStateOf(settings.tlsKeystoreAssetPath) }
    var tlsKeystorePassword by rememberSaveable { mutableStateOf(settings.tlsKeystorePassword) }
    var tlsKeystoreType by rememberSaveable { mutableStateOf(settings.tlsKeystoreType) }

    var message by rememberSaveable { mutableStateOf("Ready") }
    var testingHub by rememberSaveable { mutableStateOf(false) }
    var testingAi by rememberSaveable { mutableStateOf(false) }
    var testingDaemon by rememberSaveable { mutableStateOf(false) }
    var testingStop by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var localIps by rememberSaveable { mutableStateOf(loadLocalIpAddresses()) }
    var selectedTab by rememberSaveable { mutableIntStateOf(SettingsTab.GENERAL.ordinal) }

    LaunchedEffect(Unit) {
        localIps = loadLocalIpAddresses()
    }

    val configPathLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        handleFilePicked(context, uri)?.let { path ->
            configPath = path
        }
    }

    val tlsKeystorePathLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        handleFilePicked(context, uri)?.let { path ->
            tlsKeystorePath = path
        }
    }

    val isRunning = DaemonController.current() != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "Control point: ${if (isRunning) "daemon running" else "daemon stopped"}",
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.size(8.dp))

        val tabs = SettingsTab.entries
        val testAi: () -> Unit = {
            val endpoint = normalizeResponsesEndpoint(apiEndpoint)
            if (!endpoint.isNullOrBlank() && apiKey.isNotBlank()) {
                testingAi = true
                message = "Testing /responses endpoint…"
                scope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            sendResponsesRequest(
                                endpoint = endpoint,
                                apiKey = apiKey,
                                prompt = "Reply with one short word: ready",
                                allowInsecureTls = allowInsecure,
                                timeoutMs = 12_000
                            )
                        }
                        message = if (response.ok) {
                            "AI endpoint test status: ${response.status}"
                        } else {
                            "AI endpoint test failed: ${response.status}"
                        }
                    } catch (e: Exception) {
                        message = "AI endpoint test failed: ${e.message ?: "error"}"
                    } finally {
                        testingAi = false
                    }
                }
            } else if (endpoint.isNullOrBlank()) {
                message = "Set AI endpoint URL first"
            } else if (apiKey.isBlank()) {
                message = "Set AI API key first"
            }
        }
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            edgePadding = 0.dp
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(tab.title, color = MaterialTheme.colorScheme.onSurface) }
                )
            }
        }

        Spacer(Modifier.size(12.dp))

        when (tabs[selectedTab]) {
            SettingsTab.GENERAL -> GeneralSettingsTab(
                apiEndpoint = apiEndpoint,
                onApiEndpointChange = { apiEndpoint = it },
                apiKey = apiKey,
                onApiKeyChange = { apiKey = it },
                testingAi = testingAi,
                onTestAi = testAi
            )

            SettingsTab.ACCESS -> AccessTab(
                shareTarget = shareTarget,
                onShareTargetChange = { shareTarget = it },
                clipboardSync = clipboardSync,
                onClipboardSyncChange = { clipboardSync = it },
                quickActionCopyOnly = quickActionCopyOnly,
                onQuickActionCopyOnlyChange = { quickActionCopyOnly = it },
                quickActionHandleImage = quickActionHandleImage,
                onQuickActionHandleImageChange = { quickActionHandleImage = it },
                useAccessibilityService = useAccessibilityService,
                onUseAccessibilityServiceChange = { useAccessibilityService = it },
                runDaemonOnBoot = runDaemonOnBoot,
                onRunDaemonOnBootChange = { runDaemonOnBoot = it },
                showFloatingButton = showFloatingButton,
                onShowFloatingButtonChange = { showFloatingButton = it },
                overlayPermissionGranted = overlayPermissionGranted,
                onOpenOverlaySettings = {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    overlayPermissionGranted = isFloatingOverlayEnabled(context)
                },
                accessibilityServiceEnabled = accessibilityServiceEnabled,
                onOpenAccessibilitySettings = {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    accessibilityServiceEnabled = isClipboardAccessibilityEnabled(context)
                },
                allFilesAccessGranted = allFilesAccessGranted,
                onOpenAllFilesAccessSettings = {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        try {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (e: Exception) {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    } else {
                        // For Android < 11, typically request permissions via ActivityCompat,
                        // but here we just open App Settings as a fallback
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                    // Updating state later might be needed if they return
                    allFilesAccessGranted = isAllFilesAccessGranted(context)
                },
                contactsSync = contactsSync,
                onContactsSyncChange = { contactsSync = it },
                smsSync = smsSync,
                onSmsSyncChange = { smsSync = it },
                hubClientId = hubClientId,
                onHubClientIdChange = { hubClientId = it },
                authToken = authToken,
                onAuthTokenChange = { authToken = it },
                listenPortHttp = listenPortHttp,
                localIps = localIps,
                onCopyBaseUrl = { ip ->
                    val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    val base = "http://$ip:${listenPortHttp.ifBlank { "8080" }}"
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("base-url", base))
                    message = "Copied $base"
                }
            )

            SettingsTab.GATEWAY -> GatewayTab(
                gatewayUrls = hubDispatchUrl,
                onGatewayUrlsChange = { hubDispatchUrl = it },
                configPath = configPath,
                onConfigPathChange = { configPath = it },
                onPickConfigPath = { configPathLauncher.launch("*/*") },
                storagePath = storagePath,
                onStoragePathChange = { storagePath = it },
                allowInsecure = allowInsecure,
                onAllowInsecureChange = { allowInsecure = it },
                testingHub = testingHub,
                onTestHub = {
                    val normalizedHubUrl = space.u2re.cws.network.normalizeHubDispatchUrl(hubDispatchUrl)
                    if (normalizedHubUrl.isNullOrBlank()) {
                        message = "Set a valid Hub dispatch URL (for example http://192.168.0.200/api/broadcast)"
                        return@GatewayTab
                    }
                    testingHub = true
                    message = "Testing hub…"
                    scope.launch {
                        try {
                            val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                space.u2re.cws.network.postJson(
                                    url = normalizedHubUrl,
                                json = buildMap<String, Any> {
                                    put("requests", emptyList<Any>())
                                    val trimmedClientId = hubClientId.ifBlank { settings.deviceId }
                                    val trimmedToken = authToken.ifBlank { settings.authToken }
                                    if (trimmedClientId.isNotBlank()) put("clientId", trimmedClientId)
                                    if (trimmedToken.isNotBlank()) put("token", trimmedToken)
                                },
                                    allowInsecureTls = allowInsecure,
                                    timeoutMs = 8000
                                )
                            }
                            message = "Hub test status: ${response.status}"
                        } catch (e: Exception) {
                            message = "Hub test failed: ${e.message ?: "error"}"
                        } finally {
                            testingHub = false
                        }
                    }
                },
                destinationText = destinationText,
                onDestinationTextChange = { destinationText = it },
                localIps = localIps,
                onScanLocal = { localIps = loadLocalIpAddresses() },
                onAppendLocalAsDestinations = {
                    val existing = destinationText
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toMutableList()
                    localIps
                        .map { ip -> "id:$ip:${listenPortHttp.ifBlank { "8080" }}" }
                        .filterNot(existing::contains)
                        .forEach(existing::add)
                    destinationText = existing.joinToString("\n")
                    message = "Added ${localIps.size} local device targets"
                },
                onSelectHubFromDestination = { target -> hubDispatchUrl = target }
            )

            SettingsTab.SERVER -> ServerTab(
                enableLocalServer = enableLocalServer,
                onEnableLocalServerChange = { enableLocalServer = it },
                listenPortHttp = listenPortHttp,
                onListenPortHttpChange = { listenPortHttp = it.filter { c -> c.isDigit() } },
                listenPortHttps = listenPortHttps,
                onListenPortHttpsChange = { listenPortHttps = it.filter { c -> c.isDigit() } },
                tlsEnabled = tlsEnabled,
                onTlsEnabledChange = { tlsEnabled = it },
                tlsKeystoreType = tlsKeystoreType,
                onTlsKeystoreTypeChange = { tlsKeystoreType = it },
                tlsKeystorePath = tlsKeystorePath,
                onTlsKeystorePathChange = { tlsKeystorePath = it },
                onPickTlsKeystorePath = { tlsKeystorePathLauncher.launch("*/*") },
                tlsKeystorePassword = tlsKeystorePassword,
                onTlsKeystorePasswordChange = { tlsKeystorePassword = it },
                onStartRestartServer = {
                    scope.launch {
                        try {
                            message = "Restarting local server..."
                            DaemonController.current()?.restartServers()
                            message = "Local server restarted"
                        } catch (e: Exception) {
                            message = "Local server restart failed: ${e.message ?: "error"}"
                        }
                    }
                },
                onStopServer = {
                    scope.launch {
                        try {
                            message = "Stopping local server..."
                            DaemonController.current()?.stopServers()
                            message = "Local server stopped"
                        } catch (e: Exception) {
                            message = "Local server stop failed: ${e.message ?: "error"}"
                        }
                    }
                },
                isRunning = isRunning
            )

            SettingsTab.CONTROL_CENTER -> ControlCenterTab(
                isRunning = isRunning,
                testingDaemon = testingDaemon,
                testingStop = testingStop,
                onRestart = {
                    testingDaemon = true
                    message = "Restarting daemon"
                    scope.launch {
                        try {
                            DaemonController.current()?.restart() ?: DaemonController.start(app)
                            message = "Daemon restarted"
                        } catch (e: Exception) {
                            message = "Daemon control failed: ${e.message ?: "error"}"
                        } finally {
                            testingDaemon = false
                        }
                    }
                },
                onStop = {
                    testingStop = true
                    message = "Stopping daemon"
                    scope.launch {
                        try {
                            DaemonController.current()?.stop()
                            DaemonController.stop()
                            message = "Daemon stopped"
                        } catch (e: Exception) {
                            message = "Daemon control failed: ${e.message ?: "error"}"
                        } finally {
                            testingStop = false
                        }
                    }
                },
                onStart = {
                    testingDaemon = true
                    message = "Starting daemon"
                    try {
                        DaemonController.start(app)
                        message = "Daemon started"
                    } catch (e: Exception) {
                        message = "Daemon control failed: ${e.message ?: "error"}"
                    } finally {
                        testingDaemon = false
                    }
                },
                syncInterval = syncInterval,
                onSyncIntervalChange = { syncInterval = it },
                clipboardSyncInterval = clipboardSyncInterval,
                onClipboardSyncIntervalChange = { clipboardSyncInterval = it },
                runDaemonForeground = runDaemonForeground,
                onRunDaemonForegroundChange = { runDaemonForeground = it },
                onForceClipboardSync = {
                    val daemon = DaemonController.current()
                    if (daemon == null) {
                        DaemonController.start(app)
                    } else {
                        daemon.forceClipboardSyncNow()
                    }
                }
            )
        }

        Spacer(Modifier.size(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.size(16.dp))

        Button(
            onClick = {
                val nextHttp = listenPortHttp.toIntOrNull() ?: settings.listenPortHttp
                val nextHttps = listenPortHttps.toIntOrNull() ?: settings.listenPortHttps
                val nextSyncInterval = syncInterval.toIntOrNull() ?: settings.syncIntervalSec
                val nextClipboardSyncInterval = clipboardSyncInterval.toIntOrNull() ?: settings.clipboardSyncIntervalSec
                val nextRunDaemonForeground = runDaemonForeground
                val nextDestinations = destinationText
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val patch = SettingsPatch(
                    listenPortHttp = nextHttp,
                    listenPortHttps = nextHttps,
                    enableLocalServer = enableLocalServer,
                    destinations = nextDestinations,
                    hubDispatchUrl = normalizeHubDispatchUrl(hubDispatchUrl) ?: hubDispatchUrl.trim(),
                    allowInsecureTls = allowInsecure,
                    shareTarget = shareTarget,
                    clipboardSync = clipboardSync,
                    contactsSync = contactsSync,
                    smsSync = smsSync,
                    syncIntervalSec = nextSyncInterval,
                    clipboardSyncIntervalSec = nextClipboardSyncInterval,
                    runDaemonForeground = nextRunDaemonForeground,
                    runDaemonOnBoot = runDaemonOnBoot,
                    useAccessibilityService = useAccessibilityService,
                    showFloatingButton = showFloatingButton,
                    quickActionCopyOnly = quickActionCopyOnly,
                    quickActionHandleImage = quickActionHandleImage,
                    authToken = authToken.trim(),
                    tlsEnabled = tlsEnabled,
                    apiEndpoint = apiEndpoint.trim(),
                    apiKey = apiKey.trim(),
                    tlsKeystoreAssetPath = tlsKeystorePath.trim(),
                    tlsKeystoreType = tlsKeystoreType.ifBlank { settings.tlsKeystoreType },
                    tlsKeystorePassword = tlsKeystorePassword,
                    hubClientId = hubClientId.ifBlank { settings.deviceId },
                    hubToken = authToken.trim(),
                    configPath = configPath.trim(),
                    storagePath = storagePath.trim(),
                    logLevel = settings.logLevel
                )
                saving = true
                message = "Saving..."
                scope.launch {
                    try {
                        SettingsStore.update(app, patch)
                        if (nextRunDaemonForeground) {
                            DaemonForegroundService.start(app)
                        } else {
                            DaemonForegroundService.stop(app)
                        }
                        if (showFloatingButton && isFloatingOverlayEnabled(app)) {
                            FloatingButtonService.start(app)
                        } else {
                            FloatingButtonService.stop(app)
                        }
                        val current = DaemonController.current()
                        if (current == null) {
                            DaemonController.start(app)
                        } else {
                            current.restart()
                        }
                        message = "Saved and restarted"
                    } catch (e: Exception) {
                        message = "Save failed: ${e.message ?: "error"}"
                    } finally {
                        saving = false
                    }
                }
            },
            enabled = !saving,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(if (saving) "Saving..." else "Save changes")
        }

        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = navigateBack) {
                Text("Close")
            }
            if (selectedTab != SettingsTab.GENERAL.ordinal) {
                TextButton(onClick = { selectedTab = SettingsTab.GENERAL.ordinal }) {
                    Text("Go to General")
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun isClipboardAccessibilityEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    val serviceName = ComponentName(context, ClipboardAccessibilityService::class.java).let {
        "${it.packageName}/${it.className}"
    }
    val enabledServices = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { it.id == serviceName }
}

private fun isFloatingOverlayEnabled(context: Context): Boolean =
    AndroidSettings.canDrawOverlays(context)

private fun handleFilePicked(context: Context, uri: Uri?): String? {
    if (uri == null) return null
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        var fileName = "copied_file_${System.currentTimeMillis()}"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    val name = cursor.getString(nameIndex)
                    if (name != null) fileName = name
                }
            }
        }
        val file = java.io.File(context.filesDir, fileName)
        inputStream.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        "fs:${file.absolutePath}"
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun isAllFilesAccessGranted(context: Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
