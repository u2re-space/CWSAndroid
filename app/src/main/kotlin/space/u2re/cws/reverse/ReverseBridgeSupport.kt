package space.u2re.cws.reverse

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import space.u2re.cws.daemon.Daemon
import space.u2re.cws.network.EndpointIdentity
import space.u2re.cws.network.ServerV2Packet
import space.u2re.cws.network.ServerV2PacketCodec
import space.u2re.cws.settings.Settings

internal val reverseBridgeGson: Gson = Gson()

internal data class ReverseInboundMessage(
    val payload: JsonObject,
    val action: String,
    val namespace: String?,
    val target: String?,
    val targets: List<String> = emptyList()
)

internal fun parseInboundMessage(rawPayload: String, messageType: String): ReverseInboundMessage? {
    val payload = parsePayload(rawPayload, messageType) ?: return null
    val action = extractAction(payload, messageType)
    val namespace = extractString(payload["namespace"])?.trim()?.ifBlank { null }
        ?: extractString(nestedPayloadElement(payload)?.let(::ensureObject)?.get("namespace"))?.trim()?.ifBlank { null }
    val target = extractTarget(payload)
    return ReverseInboundMessage(
        payload = payload,
        action = action,
        namespace = namespace,
        target = target,
        targets = extractTargetCandidates(payload)
    )
}

internal fun parseInboundPacket(packet: ServerV2Packet): ReverseInboundMessage {
    val payload = reverseBridgeGson.toJsonTree(ServerV2PacketCodec.toMap(packet)).asJsonObject.apply {
        if (!has("type")) {
            addProperty("type", packet.what.ifBlank { packet.op })
        }
    }
    return ReverseInboundMessage(
        payload = payload,
        action = extractAction(payload, packet.what.ifBlank { packet.op }),
        namespace = extractString(payload["namespace"])?.trim()?.ifBlank { null }
            ?: extractString(nestedPayloadElement(payload)?.let(::ensureObject)?.get("namespace"))?.trim()?.ifBlank { null },
        target = extractTarget(payload),
        targets = extractTargetCandidates(payload)
    )
}

internal fun localBaseUrl(settings: Settings): String = "http://127.0.0.1:${settings.listenPortHttp}"

internal fun requestHeaders(settings: Settings): Map<String, String> {
    val auth = settings.authToken.trim()
    if (auth.isBlank()) return emptyMap()
    return mapOf("x-auth-token" to auth)
}

internal fun buildTargetAliases(localDeviceId: String, settings: Settings, userId: String? = null): Set<String> {
    val localTokens = listOfNotNull(
        localDeviceId.ifBlank { null },
        settings.deviceId.ifBlank { null },
        settings.hubClientId.ifBlank { null },
        settings.hubToken.ifBlank { null },
        userId?.ifBlank { null }
    ) + settings.hubTokens
        .split(Regex("[,;\n]"))
        .mapNotNull { it.trim().ifBlank { null } }
    val aliases = linkedSetOf<String>()
    for (token in localTokens) {
        aliases.addAll(EndpointIdentity.aliases(token))
    }
    return aliases
}

internal fun isTargetMatch(target: String?, localDeviceId: String, settings: Settings, userId: String? = null): Boolean {
    val normalizedTarget = EndpointIdentity.normalize(target)
    if (normalizedTarget.isBlank() || EndpointIdentity.isBroadcast(normalizedTarget)) return true

    val aliases = buildTargetAliases(localDeviceId, settings, userId)
    if (aliases.contains(normalizedTarget)) return true
    val targetAliases = EndpointIdentity.aliases(normalizedTarget)
    return targetAliases.any { aliases.contains(it) }
}

internal fun isAnyTargetMatch(targets: List<String>, localDeviceId: String, settings: Settings, userId: String? = null): Boolean {
    if (targets.isEmpty()) {
        val allowUntargetedInbound = listOf(
            System.getenv("CWS_ANDROID_ACCEPT_UNTARGETED"),
            System.getProperty("cws.android.acceptUntargeted")
        ).any { value ->
            val normalized = value?.trim()?.lowercase()
            normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on"
        }
        return allowUntargetedInbound
    }
    return targets.any { target -> isTargetMatch(target, localDeviceId, settings, userId) }
}

internal fun extractReplyTarget(payload: JsonObject): String? {
    return extractString(payload["byId"])
        ?: extractString(payload["from"])
        ?: extractString(payload["sender"])
        ?: nestedPayloadObject(payload)?.let(::extractReplyTarget)
        ?: payload["nodes"]?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.let(::extractString)
        ?: payload["destinations"]?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.let(::extractString)
}

internal fun sendResultPacket(
    requestPayload: JsonObject,
    action: String,
    callbacks: Daemon.SyncCallbacks,
    result: Any
): Boolean {
    val replyTarget = extractReplyTarget(requestPayload) ?: return false
    val replySource = extractTarget(requestPayload)
    val requestUuid = extractString(requestPayload["uuid"])
        ?: nestedPayloadObject(requestPayload)?.let { nested -> extractString(nested["uuid"]) }
    return callbacks.sendPacket(
        ServerV2Packet(
            op = "result",
            what = action,
            type = action,
            purpose = inferPurposeFromAction(action),
            protocol = "socket",
            result = result,
            uuid = requestUuid,
            nodes = listOf(replyTarget),
            destinations = listOf(replyTarget),
            // WHY: replies must identify the local Android device as the source.
            // Reusing the inbound sender makes diagnostics think the gateway or
            // Windows host authored the result, which hides real target success.
            byId = replySource,
            from = replySource,
            sender = replySource
        )
    )
}

internal fun extractTarget(payload: JsonObject): String? {
    return extractString(payload["target"])
        ?: extractString(payload["targetId"])
        ?: extractString(payload["targetDeviceId"])
        ?: extractString(payload["deviceId"])
        ?: extractString(payload["device"])
        ?: extractString(payload["to"])
        ?: payload["nodes"]?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.let(::extractString)
        ?: payload["destinations"]?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.let(::extractString)
        ?: payload["ids"]?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.let(::extractString)
        ?: nestedPayloadObject(payload)?.let(::extractTarget)
}

internal fun extractTargetCandidates(payload: JsonObject): List<String> {
    val out = linkedSetOf<String>()
    fun append(value: String?) {
        value?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
    }
    append(extractString(payload["target"]))
    append(extractString(payload["targetId"]))
    append(extractString(payload["targetDeviceId"]))
    append(extractString(payload["deviceId"]))
    append(extractString(payload["device"]))
    append(extractString(payload["to"]))
    payload["nodes"]?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
        append(extractString(element))
    }
    payload["destinations"]?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
        append(extractString(element))
    }
    payload["ids"]?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
        append(extractString(element))
    }
    nestedPayloadObject(payload)?.let { nested ->
        extractTargetCandidates(nested).forEach(out::add)
    }
    return out.toList()
}

internal fun extractAction(payload: JsonObject, fallbackType: String): String {
    val nested = nestedPayloadObject(payload)
    val candidate = extractString(payload["what"])?.lowercase()
        ?: extractString(payload["action"])?.lowercase()
        ?: extractString(payload["type"])?.lowercase()
        ?: extractString(payload["op"])?.lowercase()
        ?: extractString(nested?.get("what"))?.lowercase()
        ?: extractString(nested?.get("action"))?.lowercase()
        ?: extractString(nested?.get("type"))?.lowercase()
        ?: extractString(nested?.get("op"))?.lowercase()
        ?: fallbackType.lowercase()
    return when (candidate) {
        "request", "ask" -> extractString(payload["what"])?.lowercase() ?: extractString(nested?.get("op"))?.lowercase() ?: candidate
        "response", "result", "resolve", "error", "ack" -> extractString(payload["what"])?.lowercase() ?: fallbackType.lowercase()
        "signal", "notify", "redirect", "act" -> extractString(payload["what"])?.lowercase() ?: extractString(nested?.get("op"))?.lowercase() ?: candidate
        else -> candidate
    }
}

internal fun nestedPayloadElement(payload: JsonObject): JsonElement? {
    val packetPayload = payload["payload"]
    if (packetPayload != null && !packetPayload.isJsonNull) return packetPayload
    val dataPayload = payload["data"]
    if (dataPayload != null && !dataPayload.isJsonNull) return dataPayload
    return null
}

internal fun nestedPayloadObject(payload: JsonObject): JsonObject? {
    return ensureObject(nestedPayloadElement(payload))
}

internal fun parsePayload(rawPayload: String, fallbackType: String): JsonObject? {
    return try {
        val parsed = JsonParser.parseString(rawPayload)
        when {
            parsed.isJsonObject -> parsed.asJsonObject.apply {
                if (!has("type") && fallbackType.isNotBlank()) addProperty("type", fallbackType)
            }
            parsed.isJsonPrimitive -> JsonObject().apply {
                addProperty("type", fallbackType)
                addProperty("text", parsed.asString)
            }
            else -> JsonObject().apply { addProperty("type", fallbackType) }
        }
    } catch (_: Exception) {
        if (rawPayload.isBlank()) {
            null
        } else {
            JsonObject().apply {
                addProperty("type", fallbackType)
                addProperty("text", rawPayload)
            }
        }
    }
}

internal fun extractString(value: JsonElement?): String? = when {
    value == null || value.isJsonNull -> null
    value.isJsonPrimitive -> value.asString.trim().ifBlank { null }
    else -> value.toString()
}

internal fun extractPrimitiveString(value: JsonElement?): String? = when {
    value == null || value.isJsonNull -> null
    value.isJsonPrimitive -> value.asString.trim().ifBlank { null }
    else -> null
}

internal fun ensureObject(value: JsonElement?): JsonObject? {
    return if (value?.isJsonObject == true) value.asJsonObject else null
}

internal fun parseDispatchRequests(value: JsonElement?): Any? {
    if (value == null || value.isJsonNull) return null
    if (value.isJsonArray) {
        return try {
            reverseBridgeGson.fromJson<List<Any>>(value, List::class.java)
        } catch (_: Exception) {
            null
        }
    }
    if (value.isJsonObject) {
        val obj = value.asJsonObject
        val directRequests = obj["requests"]
        if (directRequests != null && directRequests.isJsonArray) return parseDispatchRequests(directRequests)
        return listOf(reverseBridgeGson.fromJson<Map<*, *>>(obj, Map::class.java) as Map<*, *>)
    }
    return null
}

private fun inferPurposeFromAction(action: String): String {
    val normalized = action.trim().lowercase()
    return when {
        normalized.startsWith("airpad:") || normalized.startsWith("mouse:") || normalized.startsWith("keyboard:") -> "airpad"
        normalized.startsWith("clipboard:") -> "clipboard"
        normalized.startsWith("sms:") -> "sms"
        normalized.startsWith("contact:") || normalized.startsWith("contacts:") -> "contact"
        normalized.startsWith("notification:") || normalized.startsWith("notifications:") -> "generic"
        normalized.startsWith("assets:") -> "storage"
        else -> "generic"
    }
}
