package space.u2re.cws.reverse

import com.google.gson.JsonObject
import space.u2re.cws.daemon.Daemon
import space.u2re.cws.daemon.SharedChannelRuntime
import space.u2re.cws.network.postJson
import space.u2re.cws.settings.Settings

internal object ReverseAuxiliaryHandler {
    private val smsRuntime = SharedChannelRuntime<Map<String, Any?>>("sms", fingerprintOf = { reverseBridgeGson.toJson(it) })
    private val contactsRuntime = SharedChannelRuntime<Map<String, Any?>>("contacts", fingerprintOf = { reverseBridgeGson.toJson(it) })
    private val notificationsRuntime = SharedChannelRuntime<Map<String, Any?>>("notifications", fingerprintOf = { reverseBridgeGson.toJson(it) })

    suspend fun handleSmsDelivery(
        payload: JsonObject,
        settings: Settings,
        callbacks: Daemon.SyncCallbacks?
    ): Boolean {
        val dataObj = nestedPayloadObject(payload)
        val number = extractString(payload["number"]) ?: extractString(dataObj?.get("number"))
        val content = extractString(payload["content"])
            ?: extractString(payload["text"])
            ?: extractString(dataObj?.get("content"))
            ?: extractString(dataObj?.get("text"))
        if (number.isNullOrBlank() || content.isNullOrBlank()) return false
        val request = mapOf("number" to number, "content" to content)
        val uuid = extractString(payload["uuid"])
        val decision = smsRuntime.evaluateInbound(
            payload = request,
            uuid = uuid,
            sourceId = extractString(payload["from"]) ?: extractString(payload["byId"]),
            targetId = extractTarget(payload)
        )
        if (!decision.accepted) return false
        if (callbacks != null) {
            callbacks.sendSms(number, content)
            return true
        }
        val url = localBaseUrl(settings) + "/sms"
        return postJson(url, request, allowInsecureTls = true, headers = requestHeaders(settings)).ok
    }

    suspend fun handleSmsQuery(
        payload: JsonObject,
        callbacks: Daemon.SyncCallbacks?
    ): Boolean {
        if (callbacks == null) return true
        val limit = extractString(payload["limit"])?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val items = callbacks.listSms(limit)
        val replyPayload = mapOf("ok" to true, "items" to items)
        val decision = smsRuntime.recordLocalRead(replyPayload)
        return decision.accepted && sendResultPacket(payload, extractAction(payload, "sms:list"), callbacks, replyPayload)
    }

    suspend fun handleContactsQuery(
        payload: JsonObject,
        callbacks: Daemon.SyncCallbacks?
    ): Boolean {
        if (callbacks == null) return true
        val limit = extractString(payload["limit"])?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        val items = callbacks.listContacts(limit)
        val replyPayload = mapOf("ok" to true, "items" to items)
        val decision = contactsRuntime.recordLocalRead(replyPayload)
        return decision.accepted && sendResultPacket(payload, extractAction(payload, "contacts:list"), callbacks, replyPayload)
    }

    suspend fun handleNotificationsDelivery(
        payload: JsonObject,
        settings: Settings,
        callbacks: Daemon.SyncCallbacks?
    ): Boolean {
        val dataObj = nestedPayloadObject(payload)
        val text = extractString(payload["text"])
            ?: extractString(dataObj?.get("text"))
            ?: extractString(dataObj?.get("content"))
            ?: extractString(payload["data"])
            ?: extractString(payload["payload"])
            ?: extractString(payload["body"])
            ?: return false
        val request = mapOf("text" to text)
        val uuid = extractString(payload["uuid"])
        val decision = notificationsRuntime.evaluateInbound(
            payload = request,
            uuid = uuid,
            sourceId = extractString(payload["from"]) ?: extractString(payload["byId"]),
            targetId = extractTarget(payload)
        )
        if (!decision.accepted) return false
        if (callbacks != null) {
            callbacks.speakNotificationText(text)
            return true
        }
        val url = localBaseUrl(settings) + "/notifications/speak"
        return postJson(url, request, allowInsecureTls = true, headers = requestHeaders(settings)).ok
    }

    suspend fun handleNotificationsQuery(
        payload: JsonObject,
        callbacks: Daemon.SyncCallbacks?
    ): Boolean {
        if (callbacks == null) return true
        val limit = extractString(payload["limit"])?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val items = callbacks.listNotifications(limit)
        val replyPayload = mapOf("ok" to true, "items" to items)
        val decision = notificationsRuntime.recordLocalRead(replyPayload)
        return decision.accepted && sendResultPacket(payload, extractAction(payload, "notifications:list"), callbacks, replyPayload)
    }
}
