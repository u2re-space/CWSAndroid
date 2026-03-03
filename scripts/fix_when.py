import os
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

file = "app/src/main/kotlin/space/u2re/android/service/screen/SettingsScreen.kt"
with open(file, "r") as f:
    lines = f.readlines()

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
                allowInsecure = allowInsecure,
                onAllowInsecureChange = { allowInsecure = it },
                testingHub = testingHub,
                onTestHub = {
                    val normalizedHubUrl = space.u2re.service.network.normalizeHubDispatchUrl(hubDispatchUrl)
                    if (normalizedHubUrl.isNullOrBlank()) {
                        message = "Set a valid Hub dispatch URL (for example http://192.168.0.200/api/broadcast)"
                        return@GatewayTab
                    }
                    testingHub = true
                    message = "Testing hub…"
                    scope.launch {
                        try {
                            val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                space.u2re.service.network.postJson(
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
                    destinationText = existing.joinToString("\\n")
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
            )

            SettingsTab.CONTROL_CENTER -> ControlCenterTab(
"""

start_idx = -1
end_idx = -1
for i, line in enumerate(lines):
    if "when (tabs[selectedTab]) {" in line:
        start_idx = i
    if "SettingsTab.CONTROL_CENTER -> ControlCenterTab(" in line:
        end_idx = i

if start_idx != -1 and end_idx != -1:
    lines = lines[:start_idx] + [new_when] + lines[end_idx+1:]
    with open(file, "w") as f:
        f.writelines(lines)
else:
    print(f"Could not find start/end idx: {start_idx} {end_idx}")

# Also replace mutableStateOf imports in SettingsTabContent.kt
file2 = "app/src/main/kotlin/space/u2re/android/service/screen/SettingsTabContent.kt"
with open(file2, "r") as f:
    content2 = f.read()

# add getValue/setValue imports
if "import androidx.compose.runtime.getValue" not in content2:
    content2 = "import androidx.compose.runtime.getValue\nimport androidx.compose.runtime.setValue\n" + content2

with open(file2, "w") as f:
    f.write(content2)

