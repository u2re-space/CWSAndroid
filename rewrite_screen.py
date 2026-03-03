import re
import sys

with open("app/src/main/kotlin/space/u2re/android/service/screen/SettingsScreen.kt", "r") as f:
    content = f.read()

# Replace the `when` block contents:

old_when = """        when (tabs[selectedTab]) {
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
                        Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    overlayPermissionGranted = isFloatingOverlayEnabled(context)
                },
                accessibilityServiceEnabled = accessibilityServiceEnabled,
                onOpenAccessibilitySettings = {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    accessibilityServiceEnabled = isClipboardAccessibilityEnabled(context)
                },
                contactsSync = contactsSync,
                onContactsSyncChange = { contactsSync = it },
                smsSync = smsSync,
                onSmsSyncChange = { smsSync = it },
                listenPortHttp = listenPortHttp,
                onListenPortHttpChange = { listenPortHttp = it.filter { c -> c.isDigit() } },
                listenPortHttps = listenPortHttps,
                onListenPortHttpsChange = { listenPortHttps = it.filter { c -> c.isDigit() } },
                authToken = authToken,
                onAuthTokenChange = { authToken = it },
                tlsEnabled = tlsEnabled,
                onTlsEnabledChange = { tlsEnabled = it },
                tlsKeystoreType = tlsKeystoreType,
                onTlsKeystoreTypeChange = { tlsKeystoreType = it },
                tlsKeystorePath = tlsKeystorePath,
                onTlsKeystorePathChange = { tlsKeystorePath = it },
                tlsKeystorePassword = tlsKeystorePassword,
                onTlsKeystorePasswordChange = { tlsKeystorePassword = it },
                localIps = localIps,
                onCopyBaseUrl = { ip ->
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val base = "http://$ip:${listenPortHttp.ifBlank { "8080" }}"
                    clip?.setPrimaryClip(ClipData.newPlainText("base-url", base))
                    message = "Copied $base"
                }
            )

            SettingsTab.HUB -> HubTab(
                hubDispatchUrl = hubDispatchUrl,
                onHubDispatchUrlChange = { hubDispatchUrl = it },
                hubClientId = hubClientId,
                onHubClientIdChange = { hubClientId = it },
                hubToken = hubToken,
                onHubTokenChange = { hubToken = it },
                allowInsecure = allowInsecure,
                onAllowInsecureChange = { allowInsecure = it },
                testingHub = testingHub,
                onTestHub = {
                    val normalizedHubUrl = normalizeHubDispatchUrl(hubDispatchUrl)
                    if (normalizedHubUrl.isNullOrBlank()) {
                        message = "Set a valid Hub dispatch URL (for example http://192.168.0.200/api/broadcast)"
                        return@HubTab
                    }
                    testingHub = true
                    message = "Testing hub…"
                    scope.launch {
                        try {
                            val response = withContext(Dispatchers.IO) {
                                postJson(
                                    url = normalizedHubUrl,
                                json = buildMap<String, Any> {
                                    put("requests", emptyList<Any>())
                                    val trimmedClientId = hubClientId.ifBlank { settings.deviceId }
                                    val trimmedToken = hubToken.ifBlank { settings.authToken }
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
                }
            )

            SettingsTab.PEERS -> PeersTab(
                destinationText = destinationText,
                onDestinationTextChange = { destinationText = it },
                hubDispatchUrl = hubDispatchUrl,
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
            )"""

new_when = """        when (tabs[selectedTab]) {
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
                        Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    overlayPermissionGranted = isFloatingOverlayEnabled(context)
                },
                accessibilityServiceEnabled = accessibilityServiceEnabled,
                onOpenAccessibilitySettings = {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    accessibilityServiceEnabled = isClipboardAccessibilityEnabled(context)
                },
                contactsSync = contactsSync,
                onContactsSyncChange = { contactsSync = it },
                smsSync = smsSync,
                onSmsSyncChange = { smsSync = it },
                hubClientId = hubClientId,
                onHubClientIdChange = { hubClientId = it },
                authToken = authToken,
                onAuthTokenChange = { authToken = it },
                localIps = localIps,
                onCopyBaseUrl = { ip ->
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val base = "http://$ip:${listenPortHttp.ifBlank { "8080" }}"
                    clip?.setPrimaryClip(ClipData.newPlainText("base-url", base))
                    message = "Copied $base"
                }
            )

            SettingsTab.GATEWAY -> GatewayTab(
                gatewayUrls = hubDispatchUrl,
                onGatewayUrlsChange = { hubDispatchUrl = it },
                configPath = configPath,
                onConfigPathChange = { configPath = it },
                allowInsecure = allowInsecure,
                onAllowInsecureChange = { allowInsecure = it },
                testingHub = testingHub,
                onTestHub = {
                    val normalizedHubUrl = normalizeHubDispatchUrl(hubDispatchUrl)
                    if (normalizedHubUrl.isNullOrBlank()) {
                        message = "Set a valid Hub dispatch URL (for example http://192.168.0.200/api/broadcast)"
                        return@GatewayTab
                    }
                    testingHub = true
                    message = "Testing hub…"
                    scope.launch {
                        try {
                            val response = withContext(Dispatchers.IO) {
                                postJson(
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
                tlsKeystorePassword = tlsKeystorePassword,
                onTlsKeystorePasswordChange = { tlsKeystorePassword = it },
            )"""

content = content.replace(old_when, new_when)

# Make sure configPath state exists
if "var configPath by rememberSaveable" not in content:
    content = content.replace("var hubDispatchUrl by rememberSaveable { mutableStateOf(settings.hubDispatchUrl) }", "var hubDispatchUrl by rememberSaveable { mutableStateOf(settings.hubDispatchUrl) }\n    var configPath by rememberSaveable { mutableStateOf(settings.configPath) }")

with open("app/src/main/kotlin/space/u2re/android/service/screen/SettingsScreen.kt", "w") as f:
    f.write(content)
