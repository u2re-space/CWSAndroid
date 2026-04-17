package space.u2re.cws.ui.viewmodel

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.u2re.cws.daemon.DaemonLog
import space.u2re.cws.daemon.DaemonController
import space.u2re.cws.daemon.AndroidNetworkCoordinator
import space.u2re.cws.daemon.SmsItem
import space.u2re.cws.daemon.readSmsInbox
import space.u2re.cws.data.ClipboardEnvelopeCodec
import space.u2re.cws.history.ClipboardHistoryItem
import space.u2re.cws.history.HistoryOrigin
import space.u2re.cws.history.HistoryRepository
import space.u2re.cws.history.NotificationHistoryItem
import space.u2re.cws.history.PendingRemoteQuery
import space.u2re.cws.history.SmsHistoryItem
import space.u2re.cws.network.EndpointIdentity
import space.u2re.cws.network.getText
import space.u2re.cws.network.normalizeDestinationUrl
import space.u2re.cws.notifications.NotificationEventStore
import space.u2re.cws.settings.SettingsStore
import space.u2re.cws.settings.resolve

class HistoryViewModel(
    application: Application
) : AndroidViewModel(application) {
    private data class RefreshOutcome(
        val ok: Boolean,
        val detail: String? = null
    )

    private val gson = Gson()
    private val _selectedTarget = MutableStateFlow("")
    private val _statusMessage = MutableStateFlow("History ready")

    val clipboardItems: StateFlow<List<ClipboardHistoryItem>> = HistoryRepository.clipboardItems
    val smsItems: StateFlow<List<SmsHistoryItem>> = HistoryRepository.smsItems
    val notificationItems: StateFlow<List<NotificationHistoryItem>> = HistoryRepository.notificationItems
    val pendingQueries: StateFlow<List<PendingRemoteQuery>> = HistoryRepository.pendingQueries
    val selectedTarget: StateFlow<String> = _selectedTarget.asStateFlow()
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    fun setSelectedTarget(value: String) {
        _selectedTarget.value = value
    }

    fun refreshLocal(activity: Activity?) {
        viewModelScope.launch {
            try {
                val localSource = currentLocalSourceId()
                val daemon = DaemonController.current() ?: DaemonController.start(getApplication())
                daemon.snapshotClipboardEnvelope()?.takeIf { it.hasContent() }?.let { envelope ->
                    HistoryRepository.recordClipboard(
                        envelope = envelope,
                        sourceId = localSource,
                        origin = HistoryOrigin.LOCAL
                    )
                }
                val localNotifications = NotificationEventStore.snapshot(100).map { event ->
                    NotificationHistoryItem(
                        id = event.id,
                        sourceId = localSource,
                        packageName = event.packageName,
                        title = event.title,
                        text = event.text,
                        timestamp = event.timestamp,
                        origin = HistoryOrigin.LOCAL
                    )
                }
                HistoryRepository.replaceNotificationsSnapshot(localSource, localNotifications, HistoryOrigin.LOCAL)
                if (activity != null) {
                    val localSms = readSmsInbox(activity, 100).map { it.toHistoryItem(localSource, HistoryOrigin.LOCAL) }
                    HistoryRepository.replaceSmsSnapshot(localSource, localSms, HistoryOrigin.LOCAL)
                    _statusMessage.value = "Local history refreshed"
                } else {
                    _statusMessage.value = "Local clipboard and notifications refreshed"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DaemonLog.warn("HistoryViewModel", "local history refresh failed", error)
                _statusMessage.value = "Local history refresh failed: ${error.message ?: "unexpected error"}"
            }
        }
    }

    fun refreshRemoteClipboard(target: String = _selectedTarget.value) {
        refreshRemote(target, RemoteRefreshType.CLIPBOARD)
    }

    fun refreshRemoteSms(target: String = _selectedTarget.value) {
        refreshRemote(target, RemoteRefreshType.SMS)
    }

    fun refreshRemoteNotifications(target: String = _selectedTarget.value) {
        refreshRemote(target, RemoteRefreshType.NOTIFICATIONS)
    }

    private fun refreshRemote(target: String, type: RemoteRefreshType) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) {
            _statusMessage.value = "Select a target first"
            return
        }
        AndroidNetworkCoordinator.start(getApplication())
        viewModelScope.launch {
            val isUrlTarget = isExplicitHttpTarget(trimmed)
            val outcome = try {
                if (isUrlTarget) {
                    refreshViaHttp(trimmed, type)
                } else {
                    refreshViaDaemon(trimmed, type)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DaemonLog.warn("HistoryViewModel", "remote history refresh failed type=${type.label} target=$trimmed", error)
                RefreshOutcome(ok = false, detail = error.message ?: "unexpected error")
            }
            _statusMessage.value = when {
                outcome.ok && isUrlTarget -> "${type.label} refreshed from $trimmed"
                outcome.ok -> "${type.label} query sent to $trimmed"
                outcome.detail.isNullOrBlank() -> "${type.label} refresh failed for $trimmed"
                else -> "${type.label} refresh failed for $trimmed: ${outcome.detail}"
            }
        }
    }

    private suspend fun refreshViaDaemon(target: String, type: RemoteRefreshType): RefreshOutcome {
        val daemon = DaemonController.current() ?: DaemonController.start(getApplication())
        val normalizedTarget = normalizeMeshTarget(target)
        if (normalizedTarget.isBlank()) {
            return RefreshOutcome(ok = false, detail = "target is empty")
        }
        val sent = when (type) {
            RemoteRefreshType.CLIPBOARD -> AndroidNetworkCoordinator.requestClipboardHistory(normalizedTarget)
            RemoteRefreshType.SMS -> daemon.requestSmsHistory(normalizedTarget)
            RemoteRefreshType.NOTIFICATIONS -> daemon.requestNotificationHistory(normalizedTarget)
        }
        return if (sent) {
            RefreshOutcome(ok = true)
        } else {
            RefreshOutcome(ok = false, detail = "request was not accepted")
        }
    }

    private suspend fun refreshViaHttp(target: String, type: RemoteRefreshType): RefreshOutcome {
        val settings = SettingsStore.load(getApplication()).resolve()
        val url = normalizeDestinationUrl(target, type.path)
            ?: return RefreshOutcome(ok = false, detail = "invalid URL")
        val headers = buildMap {
            if (settings.authToken.isNotBlank()) {
                put("x-auth-token", settings.authToken)
            }
        }
        val response = getText(
            url = url,
            allowInsecureTls = settings.allowInsecureTls,
            timeoutMs = 12_000,
            headers = headers
        )
        if (!response.ok) {
            return RefreshOutcome(ok = false, detail = "HTTP ${response.status}")
        }
        val rawBody = response.body.ifBlank { "{}" }
        val rawMap = try {
            gson.fromJson<Map<String, Any?>>(
                rawBody,
                object : TypeToken<Map<String, Any?>>() {}.type
            ) ?: emptyMap()
        } catch (error: Exception) {
            DaemonLog.warn("HistoryViewModel", "history response is not valid JSON url=$url", error)
            return RefreshOutcome(ok = false, detail = "invalid JSON response")
        }
        val sourceId = normalizeHistorySourceId(target)
        when (type) {
            RemoteRefreshType.CLIPBOARD -> {
                val envelope = ClipboardEnvelopeCodec.fromAny(rawMap["clipboard"], source = "history-http")
                if (envelope.hasContent()) {
                    HistoryRepository.recordClipboard(
                        envelope = envelope,
                        sourceId = sourceId,
                        origin = HistoryOrigin.REMOTE_REFRESH
                    )
                }
            }

            RemoteRefreshType.SMS -> {
                val items = (rawMap["items"] as? List<*>).orEmpty().mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    val body = map["body"]?.toString().orEmpty()
                    if (body.isBlank()) return@mapNotNull null
                    SmsHistoryItem(
                        id = map["id"]?.toString().orEmpty(),
                        sourceId = sourceId,
                        address = map["address"]?.toString().orEmpty(),
                        body = body,
                        timestamp = (map["date"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                        type = (map["type"] as? Number)?.toInt() ?: 0,
                        origin = HistoryOrigin.REMOTE_REFRESH
                    )
                }
                HistoryRepository.replaceSmsSnapshot(sourceId, items, HistoryOrigin.REMOTE_REFRESH)
            }

            RemoteRefreshType.NOTIFICATIONS -> {
                val items = (rawMap["items"] as? List<*>).orEmpty().mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    val title = map["title"]?.toString()?.trim()?.ifBlank { null }
                    val text = map["text"]?.toString()?.trim()?.ifBlank { null }
                    if (title == null && text == null) return@mapNotNull null
                    NotificationHistoryItem(
                        id = map["id"]?.toString().orEmpty(),
                        sourceId = sourceId,
                        packageName = map["packageName"]?.toString().orEmpty(),
                        title = title,
                        text = text,
                        timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                        origin = HistoryOrigin.REMOTE_REFRESH
                    )
                }
                HistoryRepository.replaceNotificationsSnapshot(sourceId, items, HistoryOrigin.REMOTE_REFRESH)
            }
        }
        return RefreshOutcome(ok = true)
    }

    private fun currentLocalSourceId(): String {
        val settings = SettingsStore.load(getApplication()).resolve()
        return EndpointIdentity.bestRouteTarget(
            settings.hubClientId.ifBlank { settings.deviceId }
        ).ifBlank { "local-device" }
    }

    private fun isExplicitHttpTarget(raw: String): Boolean {
        return EndpointIdentity.isExplicitHttpUrl(raw)
    }

    private fun normalizeMeshTarget(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        if (!EndpointIdentity.isLikelyNodeTarget(trimmed)) {
            return EndpointIdentity.bestRouteTarget(trimmed).ifBlank { trimmed }
        }
        return EndpointIdentity.bestRouteTarget(trimmed).ifBlank { trimmed }
    }

    private fun normalizeHistorySourceId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        return EndpointIdentity.sourceIdFromTargetOrUrl(trimmed).ifBlank { trimmed }
    }

    private fun SmsItem.toHistoryItem(sourceId: String, origin: HistoryOrigin): SmsHistoryItem {
        return SmsHistoryItem(
            id = id,
            sourceId = sourceId,
            address = address,
            body = body,
            timestamp = date,
            type = type,
            origin = origin
        )
    }

    private enum class RemoteRefreshType(val path: String, val label: String) {
        CLIPBOARD("/clipboard", "Clipboard"),
        SMS("/sms", "SMS"),
        NOTIFICATIONS("/notifications", "Notifications")
    }
}
