package space.u2re.service.screen

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import space.u2re.service.daemon.AutomataDaemonController
import space.u2re.service.daemon.AutomataSettings
import space.u2re.service.daemon.AutomataSettingsPatch
import space.u2re.service.daemon.AutomataSettingsStore
import space.u2re.service.daemon.postJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.NetworkInterface

@Serializable
object AutomataSettingsRoute

@Composable
fun AutomataSettingsScreen(
    navigateBack: () -> Unit
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as Application }
    val scope = rememberCoroutineScope()
    val settings: AutomataSettings = AutomataSettingsStore.load(app)

    var destinationText by rememberSaveable { mutableStateOf(settings.destinations.joinToString("\n")) }
    var hubDispatchUrl by rememberSaveable { mutableStateOf(settings.hubDispatchUrl) }
    var allowInsecure by rememberSaveable { mutableStateOf(settings.allowInsecureTls) }
    var shareTarget by rememberSaveable { mutableStateOf(settings.shareTarget) }
    var clipboardSync by rememberSaveable { mutableStateOf(settings.clipboardSync) }
    var contactsSync by rememberSaveable { mutableStateOf(settings.contactsSync) }
    var smsSync by rememberSaveable { mutableStateOf(settings.smsSync) }
    var syncInterval by rememberSaveable { mutableStateOf(settings.syncIntervalSec.toString()) }

    var listenPortHttp by rememberSaveable { mutableStateOf(settings.listenPortHttp.toString()) }
    var listenPortHttps by rememberSaveable { mutableStateOf(settings.listenPortHttps.toString()) }
    var authToken by rememberSaveable { mutableStateOf(settings.authToken) }
    var tlsEnabled by rememberSaveable { mutableStateOf(settings.tlsEnabled) }
    var tlsKeystorePath by rememberSaveable { mutableStateOf(settings.tlsKeystoreAssetPath) }
    var tlsKeystorePassword by rememberSaveable { mutableStateOf(settings.tlsKeystorePassword) }
    var tlsKeystoreType by rememberSaveable { mutableStateOf(settings.tlsKeystoreType) }

    var message by rememberSaveable { mutableStateOf("Ready") }
    var testingHub by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var localIps by remember { mutableStateOf(loadLocalIpAddresses()) }

    LaunchedEffect(Unit) {
        localIps = loadLocalIpAddresses()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Automata daemon", style = MaterialTheme.typography.titleLarge)
        Text("Status: running")
        Spacer(Modifier.size(8.dp))

        Text("Outgoing", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = destinationText,
            onValueChange = { destinationText = it },
            label = { Text("Destinations (one per line)") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = hubDispatchUrl,
            onValueChange = { hubDispatchUrl = it },
            label = { Text("Hub dispatch URL") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )
        Spacer(Modifier.size(8.dp))
        Text("Allow insecure TLS")
        Switch(
            checked = allowInsecure,
            onCheckedChange = { allowInsecure = it }
        )
        Spacer(Modifier.size(4.dp))
        Text("Share target")
        Switch(
            checked = shareTarget,
            onCheckedChange = { shareTarget = it }
        )
        Spacer(Modifier.size(4.dp))
        Text("Clipboard sync")
        Switch(
            checked = clipboardSync,
            onCheckedChange = { clipboardSync = it }
        )
        OutlinedTextField(
            value = syncInterval,
            onValueChange = { syncInterval = it.filter { c -> c.isDigit() } },
            label = { Text("Sync interval (sec)") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )

        Spacer(Modifier.size(16.dp))
        Text("Incoming / server", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = listenPortHttp,
            onValueChange = { listenPortHttp = it.filter { c -> c.isDigit() } },
            label = { Text("HTTP port") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = listenPortHttps,
            onValueChange = { listenPortHttps = it.filter { c -> c.isDigit() } },
            label = { Text("HTTPS port") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = authToken,
            onValueChange = { authToken = it },
            label = { Text("Auth token") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )
        Spacer(Modifier.size(4.dp))
        Text("TLS enabled")
        Switch(
            checked = tlsEnabled,
            onCheckedChange = { tlsEnabled = it }
        )
        OutlinedTextField(
            value = tlsKeystoreType,
            onValueChange = { tlsKeystoreType = it },
            label = { Text("TLS keystore type") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )
        OutlinedTextField(
            value = tlsKeystorePath,
            onValueChange = { tlsKeystorePath = it },
            label = { Text("TLS keystore path") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )
        OutlinedTextField(
            value = tlsKeystorePassword,
            onValueChange = { tlsKeystorePassword = it },
            label = { Text("TLS keystore password") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )
        Spacer(Modifier.size(4.dp))
        Text("Contacts sync")
        Switch(checked = contactsSync, onCheckedChange = { contactsSync = it })
        Text("SMS sync")
        Switch(checked = smsSync, onCheckedChange = { smsSync = it })

        if (localIps.isNotEmpty()) {
            Text("Device URLs")
            localIps.forEach { ip ->
                val base = "http://$ip:${listenPortHttp.ifBlank { "8080" }}"
                val withDispatch = "$base/core/ops/http/dispatch"
                val line = "$ip\n- $base/clipboard\n- $base/sms\n- $withDispatch"
                Text(line)
                TextButton(
                    onClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clip?.setPrimaryClip(ClipData.newPlainText("base-url", base))
                    }
                ) {
                    Text("Copy base URL")
                }
            }
        }

        Spacer(Modifier.size(16.dp))
        Button(
            onClick = {
                val url = hubDispatchUrl.trim()
                if (url.isBlank()) {
                    message = "Set Hub dispatch URL first"
                    return@Button
                }
                testingHub = true
                message = "Testing hub…"
                scope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            postJson(url, mapOf("requests" to emptyList<Any>()), allowInsecure, 8000)
                        }
                        message = "Hub test status: ${response.status}"
                    } catch (e: Exception) {
                        message = "Hub test failed: ${e.message ?: "error"}"
                    } finally {
                        testingHub = false
                    }
                }
            },
            enabled = !testingHub
        ) {
            Text(if (testingHub) "Testing..." else "Test Hub")
        }

        Spacer(Modifier.size(8.dp))
        Button(
            onClick = {
                val nextHttp = listenPortHttp.toIntOrNull() ?: settings.listenPortHttp
                val nextHttps = listenPortHttps.toIntOrNull() ?: settings.listenPortHttps
                val nextSyncInterval = syncInterval.toIntOrNull() ?: settings.syncIntervalSec
                val nextDestinations = destinationText
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val patch = AutomataSettingsPatch(
                    listenPortHttp = nextHttp,
                    listenPortHttps = nextHttps,
                    destinations = nextDestinations,
                    hubDispatchUrl = hubDispatchUrl.trim(),
                    allowInsecureTls = allowInsecure,
                    shareTarget = shareTarget,
                    clipboardSync = clipboardSync,
                    contactsSync = contactsSync,
                    smsSync = smsSync,
                    syncIntervalSec = nextSyncInterval,
                    authToken = authToken.trim(),
                    tlsEnabled = tlsEnabled,
                    tlsKeystoreAssetPath = tlsKeystorePath.trim(),
                    tlsKeystoreType = tlsKeystoreType.ifBlank { settings.tlsKeystoreType },
                    tlsKeystorePassword = tlsKeystorePassword,
                    logLevel = settings.logLevel
                )
                saving = true
                message = "Saving..."
                scope.launch {
                    try {
                        AutomataSettingsStore.update(app, patch)
                        AutomataDaemonController.current()?.restart()
                        message = "Saved and restarted"
                    } catch (e: Exception) {
                        message = "Save failed: ${e.message ?: "error"}"
                    } finally {
                        saving = false
                    }
                }
            },
            enabled = !saving,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(if (saving) "Saving..." else "Save & Restart")
        }

        Spacer(Modifier.size(8.dp))
        TextButton(onClick = navigateBack) {
            Text("Close")
        }
        Spacer(Modifier.size(4.dp))
        Text(message)
    }
}

private fun loadLocalIpAddresses(): List<String> {
    return try {
        val list = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                if (host.contains(":")) continue
                list.add(host)
            }
        }
        list.distinct().sorted()
    } catch (_: Exception) {
        emptyList()
    }
}
