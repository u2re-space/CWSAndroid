package space.u2re.cws.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import space.u2re.cws.data.ClipboardEnvelope
import space.u2re.cws.data.ClipboardEnvelopeCodec
import space.u2re.cws.network.EndpointIdentity
import space.u2re.cws.network.ServerV2Packet
import space.u2re.cws.notifications.NotificationEvent

enum class HistoryChannel {
    CLIPBOARD,
    SMS,
    NOTIFICATIONS
}

enum class HistoryOrigin {
    LOCAL,
    OUTBOUND,
    INBOUND,
    REMOTE_REFRESH
}

data class ClipboardHistoryItem(
    val id: String,
    val sourceId: String,
    val targetId: String? = null,
    val text: String,
    val preview: String,
    val mimeType: String? = null,
    val timestamp: Long,
    val origin: HistoryOrigin
)

data class SmsHistoryItem(
    val id: String,
    val sourceId: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val type: Int,
    val origin: HistoryOrigin
)

data class NotificationHistoryItem(
    val id: String,
    val sourceId: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val timestamp: Long,
    val origin: HistoryOrigin
)

data class PendingRemoteQuery(
    val uuid: String,
    val channel: HistoryChannel,
    val targetId: String,
    val startedAtMs: Long
)

object HistoryRepository {
    private const val MAX_CLIPBOARD_ITEMS = 120
    private const val MAX_SMS_ITEMS = 250
    private const val MAX_NOTIFICATION_ITEMS = 250

    private val _clipboardItems = MutableStateFlow<List<ClipboardHistoryItem>>(emptyList())
    private val _smsItems = MutableStateFlow<List<SmsHistoryItem>>(emptyList())
    private val _notificationItems = MutableStateFlow<List<NotificationHistoryItem>>(emptyList())
    private val _pendingQueries = MutableStateFlow<List<PendingRemoteQuery>>(emptyList())
    private val pendingByUuid = linkedMapOf<String, PendingRemoteQuery>()

    val clipboardItems: StateFlow<List<ClipboardHistoryItem>> = _clipboardItems.asStateFlow()
    val smsItems: StateFlow<List<SmsHistoryItem>> = _smsItems.asStateFlow()
    val notificationItems: StateFlow<List<NotificationHistoryItem>> = _notificationItems.asStateFlow()
    val pendingQueries: StateFlow<List<PendingRemoteQuery>> = _pendingQueries.asStateFlow()

    fun recordClipboard(
        envelope: ClipboardEnvelope,
        sourceId: String?,
        targetId: String? = null,
        origin: HistoryOrigin,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val bestText = envelope.bestText()?.trim().orEmpty()
        if (bestText.isBlank()) return
        val normalizedSource = normalizeId(sourceId, "local-device")
        val normalizedTarget = normalizeId(targetId, null)
        val item = ClipboardHistoryItem(
            id = envelope.uuid?.ifBlank { null }
                ?: "$normalizedSource|${normalizedTarget.orEmpty()}|${bestText.hashCode()}|${timestamp / 1000L}",
            sourceId = normalizedSource,
            targetId = normalizedTarget,
            text = bestText,
            preview = previewText(bestText),
            mimeType = envelope.mimeType?.trim()?.ifBlank { null },
            timestamp = timestamp,
            origin = origin
        )
        _clipboardItems.update { current ->
            listOf(item)
                .plus(current)
                .distinctBy { "${it.sourceId}|${it.targetId.orEmpty()}|${it.text}|${it.origin}" }
                .sortedByDescending { it.timestamp }
                .take(MAX_CLIPBOARD_ITEMS)
        }
    }

    fun replaceSmsSnapshot(
        sourceId: String?,
        items: List<SmsHistoryItem>,
        origin: HistoryOrigin
    ) {
        val normalizedSource = normalizeId(sourceId, "local-device")
        val normalizedItems = items.map { item ->
            item.copy(
                id = item.id.ifBlank { "$normalizedSource|${item.address}|${item.timestamp}|${item.type}" },
                sourceId = normalizedSource,
                origin = origin
            )
        }
        _smsItems.update { current ->
            normalizedItems
                .plus(current.filterNot { it.sourceId == normalizedSource && it.origin == origin })
                .distinctBy { "${it.sourceId}|${it.id}|${it.address}|${it.timestamp}" }
                .sortedByDescending { it.timestamp }
                .take(MAX_SMS_ITEMS)
        }
    }

    fun replaceNotificationsSnapshot(
        sourceId: String?,
        items: List<NotificationHistoryItem>,
        origin: HistoryOrigin
    ) {
        val normalizedSource = normalizeId(sourceId, "local-device")
        val normalizedItems = items.map { item ->
            item.copy(
                id = item.id.ifBlank { "$normalizedSource|${item.packageName}|${item.timestamp}" },
                sourceId = normalizedSource,
                origin = origin
            )
        }
        _notificationItems.update { current ->
            normalizedItems
                .plus(current.filterNot { it.sourceId == normalizedSource && it.origin == origin })
                .distinctBy { "${it.sourceId}|${it.id}|${it.packageName}|${it.timestamp}|${it.text.orEmpty()}" }
                .sortedByDescending { it.timestamp }
                .take(MAX_NOTIFICATION_ITEMS)
        }
    }

    fun recordNotificationEvent(
        event: NotificationEvent,
        sourceId: String? = null,
        origin: HistoryOrigin = HistoryOrigin.LOCAL
    ) {
        val item = NotificationHistoryItem(
            id = event.id,
            sourceId = normalizeId(sourceId, "local-device"),
            packageName = event.packageName,
            title = event.title,
            text = event.text,
            timestamp = event.timestamp,
            origin = origin
        )
        _notificationItems.update { current ->
            listOf(item)
                .plus(current)
                .distinctBy { "${it.sourceId}|${it.id}|${it.timestamp}" }
                .sortedByDescending { it.timestamp }
                .take(MAX_NOTIFICATION_ITEMS)
        }
    }

    fun registerPendingQuery(uuid: String, channel: HistoryChannel, targetId: String) {
        synchronized(pendingByUuid) {
            pendingByUuid[uuid] = PendingRemoteQuery(
                uuid = uuid,
                channel = channel,
                targetId = normalizeId(targetId, targetId),
                startedAtMs = System.currentTimeMillis()
            )
            _pendingQueries.value = pendingByUuid.values.toList()
        }
    }

    fun clearPendingQuery(uuid: String?) {
        if (uuid.isNullOrBlank()) return
        synchronized(pendingByUuid) {
            pendingByUuid.remove(uuid)
            _pendingQueries.value = pendingByUuid.values.toList()
        }
    }

    fun handleRemotePacket(packet: ServerV2Packet): Boolean {
        val uuid = packet.uuid?.trim().orEmpty()
        if (uuid.isBlank()) return false
        val pending = synchronized(pendingByUuid) {
            pendingByUuid.remove(uuid)?.also {
                _pendingQueries.value = pendingByUuid.values.toList()
            }
        } ?: return false
        if (packet.op.equals("error", ignoreCase = true)) return true

        val sourceId = normalizeId(packet.byId ?: packet.from ?: pending.targetId, pending.targetId)
        return when (pending.channel) {
            HistoryChannel.CLIPBOARD -> {
                val envelope = extractClipboardEnvelope(packet.result, uuid)
                if (envelope.hasContent()) {
                    recordClipboard(
                        envelope = envelope,
                        sourceId = sourceId,
                        targetId = null,
                        origin = HistoryOrigin.REMOTE_REFRESH,
                        timestamp = packet.timestamp
                    )
                }
                true
            }

            HistoryChannel.SMS -> {
                val items = extractItemsList(packet.result).mapNotNull { item ->
                    val body = item["body"]?.toString().orEmpty()
                    if (body.isBlank()) return@mapNotNull null
                    SmsHistoryItem(
                        id = item["id"]?.toString().orEmpty(),
                        sourceId = sourceId,
                        address = item["address"]?.toString().orEmpty(),
                        body = body,
                        timestamp = asLong(item["date"]) ?: packet.timestamp,
                        type = asInt(item["type"]) ?: 0,
                        origin = HistoryOrigin.REMOTE_REFRESH
                    )
                }
                replaceSmsSnapshot(sourceId, items, HistoryOrigin.REMOTE_REFRESH)
                true
            }

            HistoryChannel.NOTIFICATIONS -> {
                val items = extractItemsList(packet.result).mapNotNull { item ->
                    val title = item["title"]?.toString()?.trim()?.ifBlank { null }
                    val text = item["text"]?.toString()?.trim()?.ifBlank { null }
                    if (title == null && text == null) return@mapNotNull null
                    NotificationHistoryItem(
                        id = item["id"]?.toString().orEmpty(),
                        sourceId = sourceId,
                        packageName = item["packageName"]?.toString().orEmpty(),
                        title = title,
                        text = text,
                        timestamp = asLong(item["timestamp"]) ?: packet.timestamp,
                        origin = HistoryOrigin.REMOTE_REFRESH
                    )
                }
                replaceNotificationsSnapshot(sourceId, items, HistoryOrigin.REMOTE_REFRESH)
                true
            }
        }
    }

    private fun extractClipboardEnvelope(raw: Any?, uuid: String?): ClipboardEnvelope {
        if (raw == null) return ClipboardEnvelope(uuid = uuid)
        val map = raw as? Map<*, *>
        val envelopeRaw = map?.get("clipboard") ?: map?.get("payload") ?: raw
        return ClipboardEnvelopeCodec.fromAny(envelopeRaw, source = "history-query", defaultUuid = uuid)
    }

    private fun extractItemsList(raw: Any?): List<Map<*, *>> {
        val root = raw as? Map<*, *>
        val list = when (val items = root?.get("items")) {
            is List<*> -> items
            else -> raw as? List<*> ?: emptyList<Any>()
        }
        return list.mapNotNull { it as? Map<*, *> }
    }

    private fun previewText(value: String): String {
        val normalized = value.replace("\n", " ").replace("\r", " ").trim()
        return if (normalized.length <= 140) normalized else normalized.take(140) + "..."
    }

    private fun normalizeId(raw: String?, fallback: String?): String {
        val normalized = EndpointIdentity.bestRouteTarget(raw)
        if (normalized.isNotBlank()) return normalized
        return raw?.trim()?.takeIf { it.isNotBlank() }
            ?: fallback?.trim()?.takeIf { it.isNotBlank() }
            ?: "unknown"
    }

    private fun asLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun asInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
