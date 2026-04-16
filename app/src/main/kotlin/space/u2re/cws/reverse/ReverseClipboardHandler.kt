package space.u2re.cws.reverse

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import space.u2re.cws.data.ClipboardEnvelope
import space.u2re.cws.data.ClipboardEnvelopeCodec
import space.u2re.cws.daemon.Daemon
import space.u2re.cws.daemon.DaemonLog
import space.u2re.cws.network.postText
import space.u2re.cws.settings.Settings

internal object ReverseClipboardHandler {
    private val clipboardContentKeys = setOf(
        "text",
        "content",
        "body",
        "clipboard",
        "assets",
        "json",
        "value",
        "uri"
    )

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
        val envelope = extractClipboardEnvelope(payload, uuid)
        val bestText = envelope.bestText()
        if (looksLikeProtocolEnvelopeString(bestText)) {
            DaemonLog.debug(
                "ReverseClipboardHandler",
                "skip clipboard delivery reason=protocol-envelope source=${sourceId ?: "-"} target=${targetId ?: "-"} uuid=${uuid ?: "-"}"
            )
            return false
        }
        if (!envelope.hasContent()) {
            DaemonLog.debug("ReverseClipboardHandler", "skip clipboard delivery reason=empty-envelope source=${sourceId ?: "-"} target=${targetId ?: "-"} uuid=${uuid ?: "-"}")
            return false
        }
        DaemonLog.info(
            "ReverseClipboardHandler",
            "received clipboard delivery source=${sourceId ?: "-"} target=${targetId ?: "-"} uuid=${uuid ?: "-"} text=${previewClipboardText(bestText.orEmpty())}"
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

    /**
     * Result/dispatch wrappers can carry the real clipboard payload under
     * `result.clipboard`, `payload.clipboard`, or `data.clipboard`. Prefer those
     * structured envelopes before falling back to the raw packet body so Android
     * does not write protocol frames like `{op=result,...}` into the clipboard.
     */
    private fun extractClipboardEnvelope(payload: JsonObject, uuid: String?): ClipboardEnvelope {
        val resultObj = ensureObject(payload["result"])
        val payloadObj = ensureObject(payload["payload"])
        val dataObj = ensureObject(payload["data"])
        val candidates = linkedMapOf<String, JsonElement?>().apply {
            put("clipboard", payload["clipboard"])
            put("result.clipboard", resultObj?.get("clipboard"))
            put("data.clipboard", dataObj?.get("clipboard"))
            put("payload.clipboard", payloadObj?.get("clipboard"))
            if (hasDirectClipboardFields(payload)) put("root", payload)
            if (canContainClipboardEnvelope(resultObj)) put("result", payload["result"])
            if (canContainClipboardEnvelope(dataObj)) put("data", payload["data"])
            if (canContainClipboardEnvelope(payloadObj)) put("payload", payload["payload"])
        }
        for ((source, candidate) in candidates) {
            val envelope = decodeClipboardEnvelopeCandidate(candidate, uuid, source)
            if (envelope.hasContent()) {
                return envelope
            }
        }
        return ClipboardEnvelope(source = "network", uuid = uuid)
    }

    private fun decodeClipboardEnvelopeCandidate(element: JsonElement?, uuid: String?, source: String): ClipboardEnvelope {
        if (element == null || element.isJsonNull) {
            return ClipboardEnvelope(source = "network:$source", uuid = uuid)
        }
        return when {
            element.isJsonObject -> ClipboardEnvelopeCodec.fromJsonObject(
                element.asJsonObject,
                source = "network:$source",
                defaultUuid = uuid
            )
            element.isJsonPrimitive -> {
                val primitive = element.asString
                val allowPrimitive =
                    source == "clipboard" ||
                    source.endsWith(".clipboard") ||
                    (source == "root" && !looksLikeProtocolEnvelopeString(primitive))
                if (!allowPrimitive || looksLikeProtocolEnvelopeString(primitive)) {
                    ClipboardEnvelope(source = "network:$source", uuid = uuid)
                } else {
                    ClipboardEnvelopeCodec.fromAny(
                        primitive,
                        source = "network:$source",
                        defaultUuid = uuid
                    )
                }
            }
            else -> ClipboardEnvelopeCodec.fromAny(
                reverseBridgeGson.fromJson(element, Any::class.java),
                source = "network:$source",
                defaultUuid = uuid
            )
        }
    }

    private fun canContainClipboardEnvelope(value: JsonObject?, depth: Int = 0): Boolean {
        if (value == null || depth > 2) return false
        if (hasDirectClipboardFields(value)) {
            return true
        }
        return listOf("payload", "data", "result").any { key ->
            canContainClipboardEnvelope(ensureObject(value.get(key)), depth + 1)
        }
    }

    private fun hasDirectClipboardFields(value: JsonObject?): Boolean {
        if (value == null) return false
        return clipboardContentKeys.any { key -> value.has(key) && !value.get(key).isJsonNull }
    }

    private fun looksLikeProtocolEnvelopeString(raw: String?): Boolean {
        val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return false
        if (!trimmed.startsWith("{")) return false
        val lower = trimmed.lowercase()
        val hasOp = lower.contains("op=") || lower.contains("\"op\"")
        val hasRouting =
            lower.contains("what=") ||
            lower.contains("\"what\"") ||
            lower.contains("nodes=") ||
            lower.contains("\"nodes\"") ||
            lower.contains("destinations=") ||
            lower.contains("\"destinations\"") ||
            lower.contains("byid=") ||
            lower.contains("\"byid\"")
        return hasOp && hasRouting
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
