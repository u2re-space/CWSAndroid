package space.u2re.cws.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Canonical packet shape used by the Android v2 transport stack.
 *
 * AI-READ: this data class mirrors the mixed CWSP/coordinator envelope shape,
 * so socket, HTTP, and reverse-bridge code should all speak through this type.
 */
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

/**
 * JSON codec that translates between Android packet objects and the wire format
 * expected by CWSP peers.
 */
object ServerV2PacketCodec {
    private val coordinatorVerbs = setOf("ask", "act", "result", "request", "response", "signal", "notify", "redirect", "resolve", "error")
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Serialize one packet into the normalized JSON envelope sent over the wire. */
    fun encode(packet: ServerV2Packet): String {
        return json.encodeToString(JsonObject.serializer(), toJsonObject(packet))
    }

    /** Parse one raw JSON frame into the Android packet model, returning null for non-object payloads. */
    fun decode(raw: String): ServerV2Packet? {
        return runCatching {
            when (val parsed = json.parseToJsonElement(raw)) {
                is JsonObject -> fromJsonObject(parsed)
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Decode one JSON object into the canonical packet shape, inferring the
     * effective `op`, `what`, `type`, and list fields from legacy variants.
     */
    fun fromJsonObject(obj: JsonObject): ServerV2Packet {
        val payloadElement = firstJson(obj, "payload", "data", "body")
        val payloadObject = payloadElement as? JsonObject
        val opRaw = stringField(obj, "op")
        val typeRaw = stringField(obj, "type")
        val normalizedOp = normalizeCoordinatorOp(opRaw.ifBlank { typeRaw })
        val what = inferWhat(obj, payloadObject)
        val type = inferType(obj, what)
        val nodes = readStringList(obj, "nodes")
        val destinations = readStringList(obj, "destinations").ifEmpty { nodes }
        val ids = readStringList(obj, "ids").ifEmpty { destinations.ifEmpty { nodes } }
        return ServerV2Packet(
            op = normalizedOp,
            what = what,
            type = type,
            purpose = stringField(obj, "purpose").ifBlank { inferPurpose(what) ?: "" }.takeIf { it.isNotBlank() },
            protocol = stringField(obj, "protocol").takeIf { it.isNotBlank() },
            payload = payloadElement?.let(::jsonElementToAny),
            nodes = nodes,
            destinations = destinations,
            uuid = stringField(obj, "uuid").takeIf { it.isNotBlank() },
            result = obj["result"]?.let(::jsonElementToAny),
            results = obj["results"]?.let(::jsonElementToAny),
            error = obj["error"]?.let(::jsonElementToAny),
            byId = stringField(obj, "byId").takeIf { it.isNotBlank() },
            from = stringField(obj, "from").takeIf { it.isNotBlank() },
            sender = stringField(obj, "sender").takeIf { it.isNotBlank() },
            ids = ids,
            urls = readStringList(obj, "urls"),
            tokens = readStringList(obj, "tokens"),
            toRoles = readStringList(obj, "toRoles"),
            srcPlatform = readStringList(obj, "srcPlatform"),
            dstPlatform = readStringList(obj, "dstPlatform"),
            flags = readStringAnyMap(obj["flags"]),
            extensions = readGenericList(obj, "extensions"),
            defer = stringField(obj, "defer").takeIf { it.isNotBlank() },
            status = (obj["status"] as? JsonPrimitive)?.intOrNull,
            redirect = (obj["redirect"] as? JsonPrimitive)?.booleanOrNull,
            timestamp = (obj["timestamp"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis()
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

    /**
     * Render one packet back into the mixed compatibility frame shape expected
     * by existing peers (`request/response` wire ops, duplicated payload/data).
     */
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

    private fun toJsonObject(packet: ServerV2Packet): JsonObject {
        return JsonObject(
            toMap(packet)
                .mapValues { (_, value) -> anyToJsonElement(value) }
                .filterValues { it !== JsonNull }
        )
    }

    /** Infer a legacy relay type string for older bridge paths that still route by coarse message type. */
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
        val direct = stringField(root, "what")
        if (direct.isNotBlank()) return direct
        val nested = listOf("what", "action", "type", "op")
            .asSequence()
            .map { key -> payload?.let { stringField(it, key) }.orEmpty() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (nested.isNotBlank()) return nested
        return stringField(root, "type")
    }

    private fun inferType(root: JsonObject, what: String): String? {
        val direct = stringField(root, "type")
        if (direct.isNotBlank() && !coordinatorVerbs.contains(direct.lowercase())) return direct
        return what.takeIf { it.isNotBlank() }
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

    private fun firstJson(root: JsonObject, vararg keys: String): JsonElement? {
        for (key in keys) {
            val value = root[key] ?: continue
            return value
        }
        return null
    }

    private fun stringField(root: JsonObject, key: String): String {
        return (root[key] as? JsonPrimitive)?.content?.trim().orEmpty()
    }

    private fun readStringList(root: JsonObject, key: String): List<String> {
        val value = root[key] ?: return emptyList()
        return when (value) {
            is JsonArray -> value.mapNotNull { entry ->
                (entry as? JsonPrimitive)?.content?.trim()?.ifBlank { null }
            }
            is JsonPrimitive -> listOfNotNull(value.content.trim().ifBlank { null })
            else -> emptyList()
        }.distinct()
    }

    private fun readGenericList(root: JsonObject, key: String): List<Any?> {
        val value = root[key] as? JsonArray ?: return emptyList()
        return value.map(::jsonElementToAny)
    }

    private fun readStringAnyMap(value: JsonElement?): Map<String, Any?>? {
        val obj = value as? JsonObject ?: return null
        return obj.entries.associate { (key, nested) -> key to jsonElementToAny(nested) }
    }

    private fun jsonElementToAny(value: JsonElement): Any? {
        return when (value) {
            JsonNull -> null
            is JsonObject -> value.entries.associate { (key, nested) -> key to jsonElementToAny(nested) }
            is JsonArray -> value.map(::jsonElementToAny)
            is JsonPrimitive -> value.booleanOrNull ?: value.longOrNull ?: value.content
        }
    }

    private fun anyToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(
                value.entries
                    .mapNotNull { (key, nested) ->
                        val name = key?.toString()?.trim().orEmpty()
                        if (name.isBlank()) null else name to anyToJsonElement(nested)
                    }
                    .toMap()
            )
            is Iterable<*> -> JsonArray(value.map(::anyToJsonElement))
            is Array<*> -> JsonArray(value.map(::anyToJsonElement))
            else -> JsonPrimitive(value.toString())
        }
    }
}
