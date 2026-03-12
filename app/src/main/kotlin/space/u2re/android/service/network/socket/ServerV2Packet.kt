package space.u2re.cws.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class ServerV2Packet(
    val op: String = "ask",
    val what: String = "",
    val payload: Any? = null,
    val nodes: List<String> = emptyList(),
    val uuid: String? = null,
    val result: Any? = null,
    val error: Any? = null,
    val byId: String? = null,
    val from: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object ServerV2PacketCodec {
    private val gson = Gson()

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
        val nodes = obj.getAsJsonArray("nodes")
            ?.mapNotNull { element ->
                runCatching { element.asString.trim() }.getOrNull()?.ifBlank { null }
            }
            ?: emptyList()
        return ServerV2Packet(
            op = obj.get("op")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty().ifBlank { "ask" },
            what = obj.get("what")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty(),
            payload = gson.fromJson(obj.get("payload"), Any::class.java),
            nodes = nodes,
            uuid = obj.get("uuid")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
            result = gson.fromJson(obj.get("result"), Any::class.java),
            error = gson.fromJson(obj.get("error"), Any::class.java),
            byId = obj.get("byId")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
            from = obj.get("from")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
            timestamp = obj.get("timestamp")?.takeIf { it.isJsonPrimitive }?.asLong ?: System.currentTimeMillis()
        )
    }

    fun toMap(packet: ServerV2Packet): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        put("op", packet.op.ifBlank { "ask" })
        if (packet.what.isNotBlank()) put("what", packet.what)
        if (packet.payload != null) put("payload", packet.payload)
        if (packet.nodes.isNotEmpty()) put("nodes", packet.nodes)
        if (!packet.uuid.isNullOrBlank()) put("uuid", packet.uuid)
        if (packet.result != null) put("result", packet.result)
        if (packet.error != null) put("error", packet.error)
        if (!packet.byId.isNullOrBlank()) put("byId", packet.byId)
        if (!packet.from.isNullOrBlank()) put("from", packet.from)
        put("timestamp", if (packet.timestamp > 0L) packet.timestamp else System.currentTimeMillis())
    }

    fun inferLegacyRelayType(packet: ServerV2Packet): String {
        val what = packet.what.trim().lowercase()
        return when {
            what.startsWith("clipboard:") -> "clipboard"
            what.startsWith("sms:") -> "sms"
            what.startsWith("notification:") || what.startsWith("notifications:") -> "notifications.speak"
            what.contains("dispatch") || what.contains("network") || what.contains("http") -> "network.dispatch"
            else -> what.ifBlank { packet.op.ifBlank { "dispatch" } }
        }
    }
}
