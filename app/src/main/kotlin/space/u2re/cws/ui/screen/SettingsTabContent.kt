package space.u2re.cws.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import space.u2re.cws.daemon.Daemon.DaemonConnectionSnapshot

@Composable
fun GeneralSettingsTab(
    apiEndpoint: String,
    onApiEndpointChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    testingAi: Boolean,
    onTestAi: () -> Unit,
    onOpenCapacitorWeb: () -> Unit
) {
    Text(
        "General",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        "AI /responses (GPT)",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    OutlinedTextField(
        value = apiEndpoint,
        onValueChange = onApiEndpointChange,
        label = { Text("AI endpoint URL") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    Spacer(Modifier.size(8.dp))
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text("AI API key") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    Spacer(Modifier.size(8.dp))
    Button(
        onClick = onTestAi,
        enabled = !testingAi,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(if (testingAi) "Testing..." else "Test /responses")
    }
    Spacer(Modifier.size(12.dp))
    Text(
        "CrossWord web (CWSP Capacitor)",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        "Opens the same bundled HTML/JS UI as runtime/cwsp Capacitor after you run build:capacitor:web (or full build:capacitor) there. " +
            "The Capacitor Android package id is space.u2re.cwsp (cwsp product flavor); standalone Kotlin builds use space.u2re.cws.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.size(8.dp))
    Button(
        onClick = onOpenCapacitorWeb,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Text("Open web shell")
    }
    Spacer(Modifier.size(16.dp))
    Text(
        "Use Access for app/API/ports and device URL access.",
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        "Use Gateway for endpoint URL and remote connectivity tests.",
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        "Use Peers for destinations/devices/ids/apps/computers and hub selection from discovered targets.",
        color = MaterialTheme.colorScheme.onSurface
    )
    if (testingAi) {
        Spacer(Modifier.size(8.dp))
        Text("AI endpoint test is running...", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AccessTab(
    shareTarget: Boolean,
    onShareTargetChange: (Boolean) -> Unit,
    clipboardSync: Boolean,
    onClipboardSyncChange: (Boolean) -> Unit,
    quickActionCopyOnly: Boolean,
    onQuickActionCopyOnlyChange: (Boolean) -> Unit,
    quickActionHandleImage: Boolean,
    onQuickActionHandleImageChange: (Boolean) -> Unit,
    useAccessibilityService: Boolean,
    onUseAccessibilityServiceChange: (Boolean) -> Unit,
    runDaemonOnBoot: Boolean,
    onRunDaemonOnBootChange: (Boolean) -> Unit,
    showFloatingButton: Boolean,
    onShowFloatingButtonChange: (Boolean) -> Unit,
    overlayPermissionGranted: Boolean,
    onOpenOverlaySettings: () -> Unit,
    accessibilityServiceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    allFilesAccessGranted: Boolean,
    onOpenAllFilesAccessSettings: () -> Unit,
    contactsSync: Boolean,
    onContactsSyncChange: (Boolean) -> Unit,
    smsSync: Boolean,
    onSmsSyncChange: (Boolean) -> Unit,
    hubClientId: String,
    onHubClientIdChange: (String) -> Unit,
    hubTokens: String,
    onHubTokensChange: (String) -> Unit,
    listenPortHttp: String,
    localIps: List<String>,
    onCopyBaseUrl: (String) -> Unit
) {
    val switchColors = settingsSwitchColors()

    Text(
        "Identity & Access",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    OutlinedTextField(
        value = hubClientId,
        onValueChange = onHubClientIdChange,
        label = { Text("Associated Client ID") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    Spacer(Modifier.size(8.dp))
    Text(
        "How this Android device identifies itself when it initiates a connection to CWSP. Usually this is a stable peer/node id such as L-192.168.0.208.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.size(8.dp))
    OutlinedTextField(
        value = hubTokens,
        onValueChange = onHubTokensChange,
        label = { Text("Associated Client Token(s)") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    Spacer(Modifier.size(8.dp))
    Text(
        "Optional. If the endpoint requires auth, Android will try these tokens as fallbacks when it connects as initiator. Use one token per line or comma-separated, especially for NAT/gateway routes.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.size(16.dp))
    Text("Device URLs", color = MaterialTheme.colorScheme.onSurface)
    if (localIps.isNotEmpty()) {
        localIps.forEach { ip ->
            val base = "http://$ip:${listenPortHttp.ifBlank { "8080" }}"
            Text("$ip")
            Text("- $base/clipboard")
            Text("- $base/sms")
            Text("- $base/api/dispatch")
            TextButton(onClick = { onCopyBaseUrl(ip) }) {
                Text("Copy base URL")
            }
            Spacer(Modifier.size(8.dp))
        }
    }

    Spacer(Modifier.size(16.dp))
    Text(
        "App/API access",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text("Share target", color = MaterialTheme.colorScheme.onSurface)
    Switch(checked = shareTarget, onCheckedChange = onShareTargetChange, colors = switchColors)
    Text("Clipboard sync", color = MaterialTheme.colorScheme.onSurface)
    Switch(checked = clipboardSync, onCheckedChange = onClipboardSyncChange, colors = switchColors)
    Text(
        "Quick action mode: ${if (quickActionCopyOnly) "close-only" else "copy + sync"}",
        color = MaterialTheme.colorScheme.onSurface
    )
    Switch(checked = !quickActionCopyOnly, onCheckedChange = { onQuickActionCopyOnlyChange(!it) }, colors = switchColors)
    Text("Handle image payloads (future pipeline)", color = MaterialTheme.colorScheme.onSurface)
    Switch(checked = quickActionHandleImage, onCheckedChange = onQuickActionHandleImageChange, colors = switchColors)
    Spacer(Modifier.size(12.dp))
    Text("Use AccessibilityService for clipboard boost", color = MaterialTheme.colorScheme.onSurface)
    Switch(
        checked = useAccessibilityService,
        onCheckedChange = onUseAccessibilityServiceChange,
        colors = switchColors
    )
    Text(
        "Service status in system: ${if (accessibilityServiceEnabled) "enabled" else "disabled"}",
        color = MaterialTheme.colorScheme.onSurface
    )
    TextButton(onClick = onOpenAccessibilitySettings) {
        Text("Open accessibility settings")
    }
    Spacer(Modifier.size(12.dp))
    Text("Show floating control button", color = MaterialTheme.colorScheme.onSurface)
    Switch(checked = showFloatingButton, onCheckedChange = onShowFloatingButtonChange, colors = switchColors)
    Text(
        "Overlay permission: ${if (overlayPermissionGranted) "granted" else "not granted"}",
        color = MaterialTheme.colorScheme.onSurface
    )
    TextButton(onClick = onOpenOverlaySettings) {
        Text("Open overlay settings")
    }
    Text(
        "All files access: ${if (allFilesAccessGranted) "granted" else "not granted"}",
        color = MaterialTheme.colorScheme.onSurface
    )
    TextButton(onClick = onOpenAllFilesAccessSettings) {
        Text("Open all files access settings")
    }
    Spacer(Modifier.size(12.dp))
    Text("Start daemon on boot", color = MaterialTheme.colorScheme.onSurface)
    Switch(checked = runDaemonOnBoot, onCheckedChange = onRunDaemonOnBootChange, colors = switchColors)
    Text("Contacts sync", color = MaterialTheme.colorScheme.onSurface)
    Switch(checked = contactsSync, onCheckedChange = onContactsSyncChange, colors = switchColors)
    Text("SMS sync", color = MaterialTheme.colorScheme.onSurface)
    Switch(checked = smsSync, onCheckedChange = onSmsSyncChange, colors = switchColors)

}

@Composable
fun GatewayTab(
    gatewayUrls: String,
    onGatewayUrlsChange: (String) -> Unit,
    configPath: String,
    onConfigPathChange: (String) -> Unit,
    onPickConfigPath: () -> Unit,
    storagePath: String,
    onStoragePathChange: (String) -> Unit,
    onPickStoragePath: () -> Unit,
    allowInsecure: Boolean,
    onAllowInsecureChange: (Boolean) -> Unit,
    testingHub: Boolean,
    onTestHub: () -> Unit,
    destinationText: String,
    onDestinationTextChange: (String) -> Unit,
    localIps: List<String>,
    onScanLocal: () -> Unit,
    onAppendLocalAsDestinations: () -> Unit,
    authToken: String,
    onAuthTokenChange: (String) -> Unit,
    endpointSummary: String,
    endpointWarning: String?,
    onSelectHubFromDestination: (String) -> Unit,
    daemonSnapshot: DaemonConnectionSnapshot,
    onRefreshDaemonStatus: () -> Unit
) {
    val switchColors = settingsSwitchColors()
    var showPeers by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showAdvancedStatus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val userStatus = daemonSnapshot.userStatusSummary()
    val userHints = daemonSnapshot.userStatusHints()
    val daemonLines = daemonSnapshot.asStatusLines()

    Text(
        "Endpoint & Remote Access",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        endpointSummary,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!endpointWarning.isNullOrBlank()) {
        Spacer(Modifier.size(6.dp))
        Text(
            endpointWarning,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.size(8.dp))
    OutlinedTextField(
        value = gatewayUrls,
        onValueChange = onGatewayUrlsChange,
        label = { Text("Endpoint URL(s) / gateway URL(s)") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    Spacer(Modifier.size(8.dp))
    Text(
        "One or more CWSP endpoints to dial. You can enter direct LAN URLs, WAN/NAT relay URLs, or several fallback URLs separated by commas/new lines.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.size(8.dp))
    OutlinedTextField(
        value = authToken,
        onValueChange = onAuthTokenChange,
        label = { Text("Local Auth Token") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    Spacer(Modifier.size(8.dp))
    Text(
        "Used by the Android app's own local HTTP/legacy routes. This is not the same thing as the associated client token(s) above and can stay empty if you only use outbound endpoint auth.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.size(8.dp))
    OutlinedTextField(
        value = configPath,
        onValueChange = onConfigPathChange,
        label = { Text("Configuration Path (e.g. fs:/storage/emulated/0/AppConfig/config)") },
        trailingIcon = {
            androidx.compose.material3.IconButton(onClick = onPickConfigPath) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.MoreVert,
                    contentDescription = "Pick file"
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    Spacer(Modifier.size(8.dp))
    OutlinedTextField(
        value = storagePath,
        onValueChange = onStoragePathChange,
        label = { Text("Storage Base Path (e.g. /storage/emulated/0/AppConfig)") },
        trailingIcon = {
            androidx.compose.material3.IconButton(onClick = onPickStoragePath) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.MoreVert,
                    contentDescription = "Pick folder"
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    Spacer(Modifier.size(8.dp))
    Text("Allow insecure TLS", color = MaterialTheme.colorScheme.onSurface)
    Switch(checked = allowInsecure, onCheckedChange = onAllowInsecureChange, colors = switchColors)
    Spacer(Modifier.size(16.dp))
    Button(
        onClick = onTestHub,
        enabled = !testingHub,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(if (testingHub) "Testing..." else "Test Endpoint")
    }

    Spacer(Modifier.size(16.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPeers = !showPeers }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "Peers & Available Devices",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Icon(
            imageVector = if (showPeers) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = "Expand",
            tint = MaterialTheme.colorScheme.onSurface
        )
    }

    AnimatedVisibility(visible = showPeers) {
        Column {
            val discoveredTargets = androidx.compose.runtime.remember(destinationText, localIps, gatewayUrls) {
                buildDiscoveredTargets(destinationText, localIps, gatewayUrls)
            }

            Text(
                "Destinations",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedTextField(
                value = destinationText,
                onValueChange = onDestinationTextChange,
                label = { Text("Destinations: peer ID (id:...), URL, hub:/server:/proxy:/tunnel:") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors()
            )
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onScanLocal,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Scan local")
                }
                Button(
                    onClick = onAppendLocalAsDestinations,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Add local as destinations")
                }
            }

            Spacer(Modifier.size(16.dp))
            Text(
                "Available devices/apps/hubs",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (discoveredTargets.isEmpty()) {
                Text("No discovered targets yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                discoveredTargets.forEach { target ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("[${target.kind}] ${target.label}", color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = { onSelectHubFromDestination(target.value) }) {
                            Text("Use as hub")
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.size(16.dp))
    Text("Connection health", color = MaterialTheme.colorScheme.onSurface)
    Text(userStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
    userHints.forEach { hint ->
        Text("- $hint", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
    }
    Spacer(Modifier.size(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showAdvancedStatus = !showAdvancedStatus }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Advanced protocol diagnostics", color = MaterialTheme.colorScheme.onSurface)
        Icon(
            imageVector = if (showAdvancedStatus) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = "Expand advanced diagnostics",
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
    AnimatedVisibility(visible = showAdvancedStatus) {
        Column {
            daemonLines.forEach { line ->
                Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
            }
        }
    }
    Button(
        onClick = onRefreshDaemonStatus,
        enabled = true,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary
        )
    ) {
        Text("Refresh protocol status")
    }
}

@Composable
fun ServerTab(
    enableLocalServer: Boolean,
    onEnableLocalServerChange: (Boolean) -> Unit,
    listenPortHttp: String,
    onListenPortHttpChange: (String) -> Unit,
    listenPortHttps: String,
    onListenPortHttpsChange: (String) -> Unit,
    tlsEnabled: Boolean,
    onTlsEnabledChange: (Boolean) -> Unit,
    tlsKeystoreType: String,
    onTlsKeystoreTypeChange: (String) -> Unit,
    tlsKeystorePath: String,
    onTlsKeystorePathChange: (String) -> Unit,
    onPickTlsKeystorePath: () -> Unit,
    tlsKeystorePassword: String,
    onTlsKeystorePasswordChange: (String) -> Unit,
    onStartRestartServer: () -> Unit,
    onStopServer: () -> Unit,
    isRunning: Boolean
) {
    val switchColors = settingsSwitchColors()
    
    Text(
        "Incoming / local server",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    
    Text("Enable Server", color = MaterialTheme.colorScheme.onSurface)
    Switch(checked = enableLocalServer, onCheckedChange = onEnableLocalServerChange, colors = switchColors)
    
    Spacer(Modifier.size(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onStartRestartServer,
            enabled = isRunning,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Start/Restart Server")
        }
        Button(
            onClick = onStopServer,
            enabled = isRunning,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) {
            Text("Stop Server")
        }
    }
    Spacer(Modifier.size(16.dp))

    OutlinedTextField(
        value = listenPortHttp,
        onValueChange = onListenPortHttpChange,
        label = { Text("HTTP port") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )

    Spacer(Modifier.size(8.dp))
    Text("TLS enabled", color = MaterialTheme.colorScheme.onSurface)
    Switch(checked = tlsEnabled, onCheckedChange = onTlsEnabledChange, colors = switchColors)
    
    AnimatedVisibility(visible = tlsEnabled) {
        Column {
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = listenPortHttps,
                onValueChange = onListenPortHttpsChange,
                label = { Text("HTTPS port") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors()
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = tlsKeystoreType,
                onValueChange = onTlsKeystoreTypeChange,
                label = { Text("TLS keystore type") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors()
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = tlsKeystorePath,
                onValueChange = onTlsKeystorePathChange,
                label = { Text("TLS keystore path") },
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = onPickTlsKeystorePath) {
                        Icon(
                            androidx.compose.material.icons.Icons.Default.MoreVert,
                            contentDescription = "Pick file"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors()
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = tlsKeystorePassword,
                onValueChange = onTlsKeystorePasswordChange,
                label = { Text("TLS keystore password") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors()
            )
        }
    }
}

@Composable
fun ControlCenterTab(
    isRunning: Boolean,
    testingDaemon: Boolean,
    testingStop: Boolean,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onStart: () -> Unit,
    syncInterval: String,
    onSyncIntervalChange: (String) -> Unit,
    clipboardSyncInterval: String,
    onClipboardSyncIntervalChange: (String) -> Unit,
    runDaemonForeground: Boolean,
    onRunDaemonForegroundChange: (Boolean) -> Unit,
    onForceClipboardSync: () -> Unit,
) {
    Text(
        "Control Center",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        "Daemon state: ${if (isRunning) "running" else "stopped"}",
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.size(8.dp))

    OutlinedTextField(
        value = syncInterval,
        onValueChange = { onSyncIntervalChange(it.filter { c -> c.isDigit() }) },
        label = { Text("Background contacts/sms interval (sec)") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )

    Spacer(Modifier.size(12.dp))
    OutlinedTextField(
        value = clipboardSyncInterval,
        onValueChange = { onClipboardSyncIntervalChange(it.filter { c -> c.isDigit() }) },
        label = { Text("Clipboard polling interval (sec)") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    Spacer(Modifier.size(12.dp))
    Text("Keep daemon as foreground service", color = MaterialTheme.colorScheme.onSurface)
    Switch(
        checked = runDaemonForeground,
        onCheckedChange = onRunDaemonForegroundChange,
        colors = settingsSwitchColors()
    )

    Spacer(Modifier.size(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onRestart,
            enabled = !testingDaemon,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(if (testingDaemon) "Restarting..." else "Restart daemon")
        }
        Button(
            onClick = onStop,
            enabled = !testingStop && isRunning,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) {
            Text(if (testingStop) "Stopping..." else "Stop daemon")
        }
        Button(
            onClick = onStart,
            enabled = !isRunning,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Start daemon")
        }
    }
    Spacer(Modifier.size(12.dp))
    Button(
        onClick = onForceClipboardSync,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary
        )
    ) {
        Text("Force clipboard sync now")
    }
}

@Composable
private fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline
)

private fun DaemonConnectionSnapshot.userStatusSummary(): String {
    if (!daemonRunning) return "Daemon is stopped. Start daemon to enable clipboard sync."
    if (!reverseGatewayConfigured) return "Gateway is not configured yet. Set endpoint URL, user ID and user key."
    if (reverseGatewayConnected) {
        val endpoint = compactEndpointForUi(reverseGatewayActiveCandidate).ifBlank { "ws candidate" }
        return "Connected via $endpoint; relay success ${reverseRelaySuccess}/${reverseRelayAttempts}."
    }
    return "Gateway is configured but not connected (${reverseGatewayState})."
}

private fun DaemonConnectionSnapshot.userStatusHints(): List<String> {
    val hints = mutableListOf<String>()
    if (reverseGatewayLastError?.isNotBlank() == true) {
        hints += "Last error: ${compactErrorForUi(reverseGatewayLastError)}"
    }
    if (!reverseGatewayConnected && reverseGatewayStateDetail?.isNotBlank() == true) {
        hints += "State: ${compactErrorForUi(reverseGatewayStateDetail)}"
    }
    if (reverseRelayFailures > 0) {
        hints += "Relay failures: $reverseRelayFailures (check peer targets or auth headers)."
    }
    if (hints.isNotEmpty()) {
        hints += "More details are available in Advanced protocol diagnostics."
    }
    if (hints.isEmpty()) {
        hints += "No critical errors detected in current snapshot."
    }
    return hints
}

private fun compactEndpointForUi(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return ""
    return runCatching {
        val uri = java.net.URI(value)
        val scheme = uri.scheme?.ifBlank { "wss" } ?: "wss"
        val host = uri.host?.trim().orEmpty().ifBlank { value }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrElse {
        value.substringBefore("?").trim()
    }
}

private fun compactErrorForUi(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return "-"
    val lower = text.lowercase()
    val friendly = when {
        lower.contains("trust anchor") || lower.contains("certpath") || lower.contains("pkix") ->
            "TLS certificate is not trusted on this device."
        lower.contains("hostname") && lower.contains("verify") ->
            "TLS certificate hostname mismatch."
        lower.contains("handshake") && lower.contains("failed") ->
            "TLS handshake failed."
        lower.contains("timed out") || lower.contains("timeout") ->
            "Connection timeout."
        lower.contains("connection refused") ->
            "Connection refused by endpoint."
        lower.contains("unexpected end of stream") || lower.contains("eof") ->
            "Connection closed unexpectedly."
        else -> null
    }
    if (!friendly.isNullOrBlank()) return friendly
    val withoutQuery = text.replace(Regex("(wss?://[^\\s?]+)\\?[^\\s]+"), "$1?...")
    val compactWhitespace = withoutQuery.replace(Regex("\\s+"), " ").trim()
    return if (compactWhitespace.length <= 220) compactWhitespace else compactWhitespace.take(220) + "..."
}
