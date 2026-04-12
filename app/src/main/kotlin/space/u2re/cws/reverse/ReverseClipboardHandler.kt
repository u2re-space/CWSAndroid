package space.u2re.cws.reverse

import android.content.Context
import com.google.gson.JsonObject
import space.u2re.cws.data.ClipboardEnvelopeCodec
import space.u2re.cws.daemon.Daemon
import space.u2re.cws.daemon.DaemonLog
import space.u2re.cws.network.postText
import space.u2re.cws.settings.Settings

internal object ReverseClipboardHandler {
    @Suppress("UNUSED_PARAMETER")
    suspend fun handleDelivery(
        context: Context,
        payload: JsonObject,
        settings: Settings,
        callbacks: Daemon.SyncCallbacks?
    ): Boolean {
        val sourceId = extractString(payload["from"]) ?: extractString(payload["byId"])
        val targetId = extractTarget(payload)
        val uuid = extractString(payload["uuid"])
        val envelope = ClipboardEnvelopeCodec.fromJsonObject(payload, source = "network", defaultUuid = uuid)
        if (!envelope.hasContent()) {
            DaemonLog.debug("ReverseClipboardHandler", "skip clipboard delivery reason=empty-envelope source=${sourceId ?: "-"} target=${targetId ?: "-"} uuid=${uuid ?: "-"}")
            return false
        }
        DaemonLog.info(
            "ReverseClipboardHandler",
            "received clipboard delivery source=${sourceId ?: "-"} target=${targetId ?: "-"} uuid=${uuid ?: "-"} text=${previewClipboardText(envelope.bestText().orEmpty())}"
        )
        if (callbacks != null) {
            val applied = callbacks.applyClipboardEnvelope(envelope, uuid, sourceId, targetId)
            if (!applied) {
                DaemonLog.warn(
                    "ReverseClipboardHandler",
                    "clipboard delivery rejected source=${sourceId ?: "-"} target=${targetId ?: "-"} uuid=${uuid ?: "-"}"
                )
                return false
            }
            return sendResultPacket(
                requestPayload = payload,
                action = extractAction(payload, "clipboard:update"),
                callbacks = callbacks,
                result = mapOf(
                    "ok" to true,
                    "handled" to true,
                    "source" to "android-daemon",
                    "clipboard" to envelope.toMap()
                )
            )
        }
        val bestText = envelope.bestText()
        if (bestText.isNullOrBlank()) return false
        val contentType = envelope.mimeType?.takeIf { it.isNotBlank() } ?: "text/plain"
        val body = if (contentType.contains("json", ignoreCase = true) || envelope.assets.isNotEmpty() || !envelope.json.isNullOrBlank()) {
            reverseBridgeGson.toJson(envelope.toMap())
        } else {
            bestText
        }
        val headers = requestHeaders(settings) + mapOf("Content-Type" to "$contentType; charset=utf-8")
        val url = localBaseUrl(settings) + "/clipboard"
        return postText(url, body, headers, allowInsecureTls = true, timeoutMs = 8000).ok
    }

    private fun previewClipboardText(value: String): String {
        val normalized = value.replace("\n", "\\n").replace("\r", "\\r")
        return if (normalized.length <= 80) normalized else normalized.take(80) + "..."
    }

    suspend fun handleQuery(
        payload: JsonObject,
        action: String,
        callbacks: Daemon.SyncCallbacks?
    ): Boolean {
        val envelope = callbacks?.readClipboardEnvelope?.invoke()
        val isReady = envelope?.hasContent() == true
        return when {
            callbacks == null -> true
            !sendResultPacket(
                requestPayload = payload,
                action = action,
                callbacks = callbacks,
                result = when (action) {
                    "clipboard:isready" -> mapOf("ok" to true, "ready" to isReady)
                    else -> mapOf(
                        "ok" to isReady,
                        "ready" to isReady,
                        "clipboard" to (envelope?.toMap() ?: emptyMap<String, Any>())
                    )
                }
            ) -> isReady
            else -> true
        }
    }
}
