import os
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import re
import sys

with open("app/src/main/kotlin/space/u2re/android/service/screen/SettingsTabContent.kt", "r") as f:
    content = f.read()

# 1. AccessTab
# remove listenPortHttp, listenPortHttps, tlsEnabled, tlsKeystoreType, tlsKeystorePath, tlsKeystorePassword
# add hubClientId, authToken
# In AccessTab signature:
access_tab_sig_old = """    listenPortHttp: String,
    onListenPortHttpChange: (String) -> Unit,
    listenPortHttps: String,
    onListenPortHttpsChange: (String) -> Unit,
    authToken: String,
    onAuthTokenChange: (String) -> Unit,
    tlsEnabled: Boolean,
    onTlsEnabledChange: (Boolean) -> Unit,
    tlsKeystoreType: String,
    onTlsKeystoreTypeChange: (String) -> Unit,
    tlsKeystorePath: String,
    onTlsKeystorePathChange: (String) -> Unit,
    tlsKeystorePassword: String,
    onTlsKeystorePasswordChange: (String) -> Unit,"""

access_tab_sig_new = """    hubClientId: String,
    onHubClientIdChange: (String) -> Unit,
    authToken: String,
    onAuthTokenChange: (String) -> Unit,"""

content = content.replace(access_tab_sig_old, access_tab_sig_new)

access_tab_body_old = """    Text(
        "Incoming / local server",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    OutlinedTextField(
        value = listenPortHttp,
        onValueChange = onListenPortHttpChange,
        label = { Text("HTTP port") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
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
        value = authToken,
        onValueChange = onAuthTokenChange,
        label = { Text("Auth token") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )

    Text("TLS enabled", color = MaterialTheme.colorScheme.onSurface)
    Switch(checked = tlsEnabled, onCheckedChange = onTlsEnabledChange, colors = switchColors)
    OutlinedTextField(
        value = tlsKeystoreType,
        onValueChange = onTlsKeystoreTypeChange,
        label = { Text("TLS keystore type") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    OutlinedTextField(
        value = tlsKeystorePath,
        onValueChange = onTlsKeystorePathChange,
        label = { Text("TLS keystore path") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    OutlinedTextField(
        value = tlsKeystorePassword,
        onValueChange = onTlsKeystorePasswordChange,
        label = { Text("TLS keystore password") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )

    Spacer(Modifier.size(16.dp))
    Text("Device URLs", color = MaterialTheme.colorScheme.onSurface)"""

access_tab_body_new = """    Text(
        "Identity & Access",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    OutlinedTextField(
        value = hubClientId,
        onValueChange = onHubClientIdChange,
        label = { Text("Client ID") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    Spacer(Modifier.size(8.dp))
    OutlinedTextField(
        value = authToken,
        onValueChange = onAuthTokenChange,
        label = { Text("Auth Token") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )

    Spacer(Modifier.size(16.dp))
    Text("Device URLs", color = MaterialTheme.colorScheme.onSurface)"""

content = content.replace(access_tab_body_old, access_tab_body_new)

# 2. HubTab and PeersTab -> GatewayTab
# Remove HubTab and PeersTab
hub_peers_regex = re.compile(r"@Composable\nfun HubTab\(.*?\n}\n\n@Composable\nfun PeersTab\(.*?\n}", re.DOTALL)
gateway_tab_code = """
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon

@Composable
fun GatewayTab(
    gatewayUrls: String,
    onGatewayUrlsChange: (String) -> Unit,
    configPath: String,
    onConfigPathChange: (String) -> Unit,
    allowInsecure: Boolean,
    onAllowInsecureChange: (Boolean) -> Unit,
    testingHub: Boolean,
    onTestHub: () -> Unit,
    destinationText: String,
    onDestinationTextChange: (String) -> Unit,
    localIps: List<String>,
    onScanLocal: () -> Unit,
    onAppendLocalAsDestinations: () -> Unit,
    onSelectHubFromDestination: (String) -> Unit,
) {
    val switchColors = settingsSwitchColors()
    var showPeers by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Text(
        "Gateway & Configuration",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    OutlinedTextField(
        value = gatewayUrls,
        onValueChange = onGatewayUrlsChange,
        label = { Text("Gateway URLs (comma separated)") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors()
    )
    Spacer(Modifier.size(8.dp))
    OutlinedTextField(
        value = configPath,
        onValueChange = onConfigPathChange,
        label = { Text("Configuration Path (e.g. fs:clients.json)") },
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
        Text(if (testingHub) "Testing..." else "Test Gateway")
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
}

@Composable
fun ServerTab(
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
    tlsKeystorePassword: String,
    onTlsKeystorePasswordChange: (String) -> Unit,
) {
    val switchColors = settingsSwitchColors()
    
    Text(
        "Incoming / local server",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
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
"""

content = hub_peers_regex.sub(gateway_tab_code.strip(), content)

# I also need to add missing imports at the top
imports = """import androidx.compose.foundation.layout.Column
"""
if "import androidx.compose.animation.AnimatedVisibility" not in content:
    content = content.replace("import androidx.compose.foundation.layout.Column", "import androidx.compose.foundation.layout.Column\nimport androidx.compose.animation.AnimatedVisibility\nimport androidx.compose.foundation.clickable\nimport androidx.compose.material.icons.Icons\nimport androidx.compose.material.icons.filled.KeyboardArrowDown\nimport androidx.compose.material.icons.filled.KeyboardArrowUp\nimport androidx.compose.material3.Icon")

with open("app/src/main/kotlin/space/u2re/android/service/screen/SettingsTabContent.kt", "w") as f:
    f.write(content)
