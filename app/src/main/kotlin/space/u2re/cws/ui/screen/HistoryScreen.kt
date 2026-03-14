package space.u2re.cws.ui.screen

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.Serializable
import space.u2re.cws.history.ClipboardHistoryItem
import space.u2re.cws.history.HistoryChannel
import space.u2re.cws.history.NotificationHistoryItem
import space.u2re.cws.history.PendingRemoteQuery
import space.u2re.cws.history.SmsHistoryItem
import space.u2re.cws.network.EndpointIdentity
import space.u2re.cws.settings.SettingsStore
import space.u2re.cws.settings.resolve
import space.u2re.cws.ui.viewmodel.HistoryViewModel
import java.text.DateFormat
import java.util.Date

@Serializable
object HistoryRoute

private enum class HistoryTab(val title: String, val channel: HistoryChannel) {
    CLIPBOARD("Clipboard", HistoryChannel.CLIPBOARD),
    SMS("SMS", HistoryChannel.SMS),
    NOTIFICATIONS("Notifications", HistoryChannel.NOTIFICATIONS)
}

@Composable
fun HistoryScreen(
    navigateBack: () -> Unit
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as Application }
    val settings = remember { SettingsStore.load(app).resolve() }
    val localIps = remember { loadLocalIpAddresses() }
    val suggestions = remember(settings.destinations, settings.hubDispatchUrl, localIps) {
        buildDiscoveredTargets(
            destinationText = settings.destinations.joinToString("\n"),
            localIps = localIps,
            hubDispatchUrl = settings.hubDispatchUrl
        )
    }
    val viewModel = viewModel<HistoryViewModel>()
    val clipboardItems by viewModel.clipboardItems.collectAsState()
    val smsItems by viewModel.smsItems.collectAsState()
    val notificationItems by viewModel.notificationItems.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val selectedTarget by viewModel.selectedTarget.collectAsState()
    val pendingQueries by viewModel.pendingQueries.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(HistoryTab.CLIPBOARD.ordinal) }
    var detailDialog by remember { mutableStateOf<HistoryDetailDialog?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshLocal(context as? Activity)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("History", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Manual copy/view for clipboard, SMS, and notifications",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = navigateBack) {
                Text("Close")
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = selectedTarget,
            onValueChange = viewModel::setSelectedTarget,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Remote target or URL") },
            placeholder = { Text("192.168.0.110 or https://192.168.0.110:8443") },
            singleLine = true
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Bare device IDs/IPs use mesh queries. Use http(s):// only for direct endpoint snapshots.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        if (suggestions.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions.take(12), key = { "${it.kind}:${it.value}" }) { item ->
                    AssistChip(
                        onClick = { viewModel.setSelectedTarget(item.value) },
                        label = { Text(item.label, maxLines = 1) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.refreshLocal(context as? Activity) }) {
                Text("Refresh Local")
            }
            Button(
                onClick = {
                    when (HistoryTab.entries[selectedTab]) {
                        HistoryTab.CLIPBOARD -> viewModel.refreshRemoteClipboard()
                        HistoryTab.SMS -> viewModel.refreshRemoteSms()
                        HistoryTab.NOTIFICATIONS -> viewModel.refreshRemoteNotifications()
                    }
                }
            ) {
                Text("Refresh Remote")
            }
        }

        Spacer(Modifier.height(8.dp))

        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            HistoryTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(tab.title) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        val activeTab = HistoryTab.entries[selectedTab]
        val activePending = remember(activeTab, pendingQueries, selectedTarget) {
            pendingQueries.any { query ->
                query.channel == activeTab.channel && matchesTarget(selectedTarget, query.targetId, null)
            }
        }

        Text(
            text = if (activePending) "$statusMessage (waiting for result)" else statusMessage,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(8.dp))

        when (activeTab) {
            HistoryTab.CLIPBOARD -> ClipboardTab(
                items = clipboardItems.filter { item -> matchesTarget(selectedTarget, item.sourceId, item.targetId) },
                onCopy = { item -> copyText(context, item.text) },
                onOpen = { item ->
                    detailDialog = HistoryDetailDialog(
                        title = "Clipboard from ${item.sourceId}",
                        body = item.text,
                        onCopy = { copyText(context, item.text) }
                    )
                }
            )

            HistoryTab.SMS -> SmsTab(
                items = smsItems.filter { item -> matchesTarget(selectedTarget, item.sourceId, null) },
                onOpen = { item ->
                    detailDialog = HistoryDetailDialog(
                        title = "SMS from ${item.sourceId}",
                        body = item.body,
                        onCopy = { copyText(context, item.body) }
                    )
                }
            )

            HistoryTab.NOTIFICATIONS -> NotificationsTab(
                items = notificationItems.filter { item -> matchesTarget(selectedTarget, item.sourceId, null) },
                onOpen = { item ->
                    val body = listOfNotNull(item.title, item.text).joinToString("\n\n").ifBlank { "(empty)" }
                    detailDialog = HistoryDetailDialog(
                        title = "Notification from ${item.sourceId}",
                        body = body,
                        onCopy = { copyText(context, body) }
                    )
                }
            )
        }
    }

    detailDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = { detailDialog = null },
            title = { Text(dialog.title) },
            text = {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(dialog.body, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = dialog.onCopy) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { detailDialog = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ClipboardTab(
    items: List<ClipboardHistoryItem>,
    onCopy: (ClipboardHistoryItem) -> Unit,
    onOpen: (ClipboardHistoryItem) -> Unit
) {
    if (items.isEmpty()) {
        EmptyHistoryState("No clipboard items yet")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(item.preview, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "${item.sourceId} • ${item.origin.name.lowercase()} • ${formatTimestamp(item.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onCopy(item) }) {
                            Text("Copy")
                        }
                        OutlinedButton(onClick = { onOpen(item) }) {
                            Text("View")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmsTab(
    items: List<SmsHistoryItem>,
    onOpen: (SmsHistoryItem) -> Unit
) {
    if (items.isEmpty()) {
        EmptyHistoryState("No SMS items yet")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        items(items, key = { "${it.sourceId}:${it.id}:${it.timestamp}" }) { item ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(item.address.ifBlank { "(unknown number)" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(4.dp))
                    Text(item.body, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "${item.sourceId} • ${formatTimestamp(item.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = { onOpen(item) }) {
                        Text("View")
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsTab(
    items: List<NotificationHistoryItem>,
    onOpen: (NotificationHistoryItem) -> Unit
) {
    if (items.isEmpty()) {
        EmptyHistoryState("No notifications yet")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        items(items, key = { "${it.sourceId}:${it.id}:${it.timestamp}" }) { item ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(item.title ?: "(no title)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(4.dp))
                    Text(item.text ?: "(no text)", maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "${item.sourceId} • ${item.packageName.ifBlank { "-" }} • ${formatTimestamp(item.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = { onOpen(item) }) {
                        Text("View")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryState(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class HistoryDetailDialog(
    val title: String,
    val body: String,
    val onCopy: () -> Unit
)

private fun copyText(context: Context, text: String) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboardManager.setPrimaryClip(ClipData.newPlainText("history-copy", text))
}

private fun matchesTarget(selectedTarget: String, sourceId: String, targetId: String?): Boolean {
    val filter = selectedTarget.trim()
    if (filter.isBlank()) return true
    val normalizedFilter = EndpointIdentity.sourceIdFromTargetOrUrl(filter).ifBlank { filter }
    val aliases = EndpointIdentity.aliases(normalizedFilter)
    if (aliases.isEmpty()) return sourceId.contains(filter, ignoreCase = true) || (targetId?.contains(filter, ignoreCase = true) == true)
    return aliases.any { alias ->
        EndpointIdentity.aliases(sourceId).contains(alias) || (targetId != null && EndpointIdentity.aliases(targetId).contains(alias))
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return runCatching {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
    }.getOrElse { timestamp.toString() }
}
