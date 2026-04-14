package space.u2re.cws.network

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class ServerV2Packet(
    val op: String = "ask",
    val what: String = "",
    val type: String? = null,
    val purpose: String? = null,
    val protocol: String? = null,
    val payload: Any? = null,
    val nodes: List<String> = emptyList(),
    val destinations: List<String> = emptyList(),
    val uuid: String? = null,
    val result: Any? = null,
    val results: Any? = null,
    val error: Any? = null,
    val byId: String? = null,
    val from: String? = null,
    val sender: String? = null,
    val ids: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val tokens: List<String> = emptyList(),
    val toRoles: List<String> = emptyList(),
    val srcPlatform: List<String> = emptyList(),
    val dstPlatform: List<String> = emptyList(),
    val flags: Map<String, Any?>? = null,
    val extensions: List<Any?> = emptyList(),
    val defer: String? = null,
    val status: Int? = null,
    val redirect: Boolean? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object ServerV2PacketCodec {
    private val gson = Gson()
    private val coordinatorVerbs = setOf("ask", "act", "result", "request", "response", "signal", "notify", "redirect", "resolve", "error")

    fun encode(packet: ServerV2Packet): String {
        return gson.toJson(toMap(packet))
    }

    fun decode(raw: String): ServerV2Packet? {
        return try {
            val parsed = JsonParser.parseString(raw)
            if (!parsed.isJsonObject) return null
            fromJsonObject(parsed.asJsonObject)
        } catch (_: Exception) {
            null
        }
    }

    fun fromJsonObject(obj: JsonObject): ServerV2Packet {
        val payloadElement = when {
            obj.has("payload") -> obj.get("payload")
            obj.has("data") -> obj.get("data")
            obj.has("body") -> obj.get("body")
            else -> null
        }
        val payloadObject = payloadElement?.takeIf { it.isJsonObject }?.asJsonObject
        val opRaw = obj.get("op")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val typeRaw = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val normalizedOp = normalizeCoordinatorOp(opRaw.ifBlank { typeRaw })
        val what = inferWhat(obj, payloadObject)
        val type = inferType(obj, what)
        val payload = payloadElement?.let { gson.fromJson(it, Any::class.java) }
        val nodes = readStringList(obj, "nodes")
        val destinations = readStringList(obj, "destinations").ifEmpty { nodes }
        val ids = readStringList(obj, "ids").ifEmpty { destinations.ifEmpty { nodes } }
        return ServerV2Packet(
            op = normalizedOp,
            what = what,
            type = type,
            purpose = obj.get("purpose")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null }
                ?: inferPurpose(what),
            protocol = obj.get("protocol")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
            payload = payload,
            nodes = nodes,
            destinations = destinations,
            uuid = obj.get("uuid")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
            result = gson.fromJson(obj.get("result"), Any::class.java),
            results = gson.fromJson(obj.get("results"), Any::class.java),
            error = gson.fromJson(obj.get("error"), Any::class.java),
            byId = obj.get("byId")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
            from = obj.get("from")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
            sender = obj.get("sender")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
            ids = ids,
            urls = readStringList(obj, "urls"),
            tokens = readStringList(obj, "tokens"),
            toRoles = readStringList(obj, "toRoles"),
            srcPlatform = readStringList(obj, "srcPlatform"),
            dstPlatform = readStringList(obj, "dstPlatform"),
            flags = readStringAnyMap(obj.get("flags")),
            extensions = readGenericList(obj, "extensions"),
            defer = obj.get("defer")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
            status = obj.get("status")?.takeIf { it.isJsonPrimitive }?.asInt,
            redirect = obj.get("redirect")?.takeIf { it.isJsonPrimitive }?.asBoolean,
            timestamp = obj.get("timestamp")?.takeIf { it.isJsonPrimitive }?.asLong ?: System.currentTimeMillis()
        )
    }

    private fun normalizeCoordinatorOp(raw: String): String {
        return when (raw.trim().lowercase()) {
            "request" -> "ask"
            "response" -> "result"
            "signal", "notify", "redirect" -> "act"
            "resolve" -> "result"
            "error" -> "error"
            "ack" -> "result"
            else -> raw.trim().ifBlank { "ask" }
        }
    }

    private fun mapPacketOpToFrameOp(raw: String): String {
        return when (raw.trim().lowercase()) {
            "ask" -> "request"
            "result", "resolve" -> "response"
            "error" -> "error"
            else -> raw.trim().ifBlank { "act" }
        }
    }

    fun toMap(packet: ServerV2Packet): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        val normalizedOp = normalizeCoordinatorOp(packet.op)
        put("op", mapPacketOpToFrameOp(normalizedOp))
        if (packet.what.isNotBlank()) {
            put("what", packet.what)
            put("type", packet.what)
        } else if (!packet.type.isNullOrBlank()) {
            put("type", packet.type)
        }
        if (!packet.purpose.isNullOrBlank()) put("purpose", packet.purpose)
        if (!packet.protocol.isNullOrBlank()) put("protocol", packet.protocol)
        if (packet.payload != null) put("payload", packet.payload)
        if (packet.payload != null) put("data", packet.payload)
        if (packet.nodes.isNotEmpty()) put("nodes", packet.nodes)
        if (packet.destinations.isNotEmpty()) put("destinations", packet.destinations)
        if (packet.ids.isNotEmpty()) put("ids", packet.ids)
        if (packet.urls.isNotEmpty()) put("urls", packet.urls)
        if (packet.tokens.isNotEmpty()) put("tokens", packet.tokens)
        if (packet.toRoles.isNotEmpty()) put("toRoles", packet.toRoles)
        if (packet.srcPlatform.isNotEmpty()) put("srcPlatform", packet.srcPlatform)
        if (packet.dstPlatform.isNotEmpty()) put("dstPlatform", packet.dstPlatform)
        if (packet.flags != null) put("flags", packet.flags)
        if (packet.extensions.isNotEmpty()) put("extensions", packet.extensions)
        if (!packet.defer.isNullOrBlank()) put("defer", packet.defer)
        if (packet.status != null) put("status", packet.status)
        if (packet.redirect != null) put("redirect", packet.redirect)
        if (!packet.uuid.isNullOrBlank()) put("uuid", packet.uuid)
        if (packet.result != null) put("result", packet.result)
        if (packet.results != null) put("results", packet.results)
        if (packet.error != null) put("error", packet.error)
        if (!packet.byId.isNullOrBlank()) put("byId", packet.byId)
        if (!packet.from.isNullOrBlank()) put("from", packet.from)
        if (!packet.sender.isNullOrBlank()) put("sender", packet.sender)
        put("timestamp", if (packet.timestamp > 0L) packet.timestamp else System.currentTimeMillis())
    }

    fun inferLegacyRelayType(packet: ServerV2Packet): String {
        val what = packet.what.trim().lowercase()
        return when {
            what.startsWith("airpad:") -> "airpad"
            what.startsWith("clipboard:") -> "clipboard"
            what.startsWith("sms:") -> "sms"
            what.startsWith("contacts:") -> "contacts"
            what.startsWith("notification:") || what.startsWith("notifications:") -> "notifications.speak"
            what.contains("dispatch") || what.contains("network") || what.contains("http") -> "network.dispatch"
            else -> what.ifBlank { packet.op.ifBlank { "dispatch" } }
        }
    }

    private fun inferWhat(root: JsonObject, payload: JsonObject?): String {
        val direct = root.get("what")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (direct.isNotBlank()) return direct
        val nested = listOf("what", "action", "type", "op")
            .asSequence()
            .mapNotNull { key -> payload?.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim() }
            .firstOrNull { !it.isNullOrBlank() }
            .orEmpty()
        if (nested.isNotBlank()) return nested
        return root.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
    }

    private fun inferType(root: JsonObject, what: String): String? {
        val direct = root.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (direct.isNotBlank() && !coordinatorVerbs.contains(direct.lowercase())) return direct
        return what.ifBlank { null }
    }

    private fun inferPurpose(what: String): String? {
        val normalized = what.trim().lowercase()
        return when {
            normalized.startsWith("airpad:") || normalized.startsWith("mouse:") || normalized.startsWith("keyboard:") -> "airpad"
            normalized.startsWith("clipboard:") -> "clipboard"
            normalized.startsWith("notification:") || normalized.startsWith("notifications:") -> "generic"
            normalized.startsWith("contacts:") -> "contact"
            normalized.startsWith("sms:") -> "sms"
            normalized.startsWith("assets:") -> "storage"
            normalized.isBlank() -> null
            else -> "generic"
        }
    }

    private fun readStringList(root: JsonObject, key: String): List<String> {
        val value = root.get(key) ?: return emptyList()
        return when {
            value.isJsonArray -> value.asJsonArray.mapNotNull { entry ->
                runCatching { entry.asString.trim() }.getOrNull()?.ifBlank { null }
            }
            value.isJsonPrimitive -> listOfNotNull(value.asString.trim().ifBlank { null })
            else -> emptyList()
        }.distinct()
    }

    private fun readGenericList(root: JsonObject, key: String): List<Any?> {
        val value = root.get(key) ?: return emptyList()
        if (!value.isJsonArray) return emptyList()
        return value.asJsonArray.map { entry: JsonElement -> gson.fromJson(entry, Any::class.java) }
    }

    private fun readStringAnyMap(value: JsonElement?): Map<String, Any?>? {
        if (value == null || !value.isJsonObject) return null
        val decoded = gson.fromJson(value, Map::class.java) as? Map<*, *> ?: return null
        return decoded.entries.associate { (key, v) -> key.toString() to v }
    }
}
