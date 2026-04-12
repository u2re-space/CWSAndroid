import os
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import re

with open("app/src/main/kotlin/space/u2re/android/service/screen/SettingsScreen.kt", "r") as f:
    content = f.read()

# Replace HUB with GATEWAY and call GatewayTab
# We can find `SettingsTab.HUB -> HubTab(`
content = re.sub(r'SettingsTab\.HUB\s*->\s*HubTab\(', r'SettingsTab.GATEWAY -> GatewayTab(', content)
content = re.sub(r'return@HubTab', r'return@GatewayTab', content)

# GatewayTab has extra parameters compared to HubTab
# Let's replace the whole HUB and PEERS block.
# I will use a regex to match the HUB and PEERS blocks.
hub_peers_regex = re.compile(
    r"SettingsTab\.GATEWAY -> GatewayTab\([\s\S]*?testingHub = false\n\s*\}\n\s*\}\n\s*\)\n\n\s*SettingsTab\.PEERS -> PeersTab\([\s\S]*?onSelectHubFromDestination = \{ target -> hubDispatchUrl = target \}\n\s*\)",
    re.MULTILINE
)

gateway_server_code = """SettingsTab.GATEWAY -> GatewayTab(
                gatewayUrls = hubDispatchUrl,
                onGatewayUrlsChange = { hubDispatchUrl = it },
                configPath = configPath,
                onConfigPathChange = { configPath = it },
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
            )"""

content = hub_peers_regex.sub(gateway_server_code, content)

# Now fix AccessTab
access_tab_regex = re.compile(
    r"SettingsTab\.ACCESS -> AccessTab\([\s\S]*?onCopyBaseUrl = \{ ip ->[\s\S]*?message = \"Copied \$base\"\n\s*\}\n\s*\)",
    re.MULTILINE
)

access_tab_code = """SettingsTab.ACCESS -> AccessTab(
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
            )"""

content = access_tab_regex.sub(access_tab_code, content)

# Make sure configPath state exists
if "var configPath by rememberSaveable" not in content:
    content = content.replace("var hubDispatchUrl by rememberSaveable { mutableStateOf(settings.hubDispatchUrl) }", "var hubDispatchUrl by rememberSaveable { mutableStateOf(settings.hubDispatchUrl) }\n    var configPath by rememberSaveable { mutableStateOf(settings.configPath) }")

with open("app/src/main/kotlin/space/u2re/android/service/screen/SettingsScreen.kt", "w") as f:
    f.write(content)
