package space.u2re.cws.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.OpenableColumns
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale

data class DataAssetEnvelope(
    val hash: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val source: String,
    val data: String? = null,
    val encoding: String? = null,
    val uri: String? = null,
    val text: String? = null,
    val metadata: Map<String, Any?> = emptyMap()
) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        put("hash", hash)
        put("name", name)
        put("mimeType", mimeType)
        put("type", mimeType)
        put("size", size)
        put("source", source)
        if (!data.isNullOrBlank()) put("data", data)
        if (!encoding.isNullOrBlank()) put("encoding", encoding)
        if (!uri.isNullOrBlank()) put("uri", uri)
        if (!text.isNullOrBlank()) put("text", text)
        if (metadata.isNotEmpty()) put("meta", metadata)
    }
}

data class ClipboardEnvelope(
    val text: String? = null,
    val json: String? = null,
    val mimeType: String? = null,
    val label: String? = null,
    val source: String? = null,
    val uuid: String? = null,
    val assets: List<DataAssetEnvelope> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap()
) {
    fun hasContent(): Boolean = !bestText().isNullOrBlank() || assets.isNotEmpty()

    fun bestText(): String? {
        val directText = text?.trim()?.takeIf { it.isNotBlank() }
        if (directText != null) return directText
        val directJson = json?.trim()?.takeIf { it.isNotBlank() }
        if (directJson != null) return directJson
        return assets.firstNotNullOfOrNull { asset ->
            asset.text?.trim()?.takeIf { it.isNotBlank() }
                ?: asset.data?.trim()?.takeIf { it.isNotBlank() }
                ?: asset.uri?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    fun fingerprint(): String = ClipboardEnvelopeCodec.canonicalJson(toMap()).sha256Hex()

    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        val resolvedText = text?.trim()?.takeIf { it.isNotBlank() }
        val resolvedJson = json?.trim()?.takeIf { it.isNotBlank() }
        val resolvedMime = mimeType?.trim()?.takeIf { it.isNotBlank() }
        val resolvedLabel = label?.trim()?.takeIf { it.isNotBlank() }
        val resolvedSource = source?.trim()?.takeIf { it.isNotBlank() }
        val resolvedUuid = uuid?.trim()?.takeIf { it.isNotBlank() }
        if (resolvedText != null) put("text", resolvedText)
        if (resolvedJson != null) {
            put("json", resolvedJson)
            if (resolvedText == null) put("content", resolvedJson)
        }
        if (resolvedMime != null) {
            put("mimeType", resolvedMime)
            put("type", resolvedMime)
        }
        if (resolvedLabel != null) put("label", resolvedLabel)
        if (resolvedSource != null) put("source", resolvedSource)
        if (resolvedUuid != null) put("uuid", resolvedUuid)
        if (assets.isNotEmpty()) put("assets", assets.map { it.toMap() })
        if (metadata.isNotEmpty()) put("meta", metadata)
        bestText()?.let { put("content", it) }
    }
}

object ClipboardEnvelopeCodec {
    private const val MAX_INLINE_ASSET_BYTES = 4 * 1024 * 1024
    private val gson = Gson()

    fun fromJsonObject(obj: JsonObject, source: String? = null, defaultUuid: String? = null): ClipboardEnvelope {
        val parsed = gson.fromJson(obj, Any::class.java)
        return fromAny(parsed, source = source, defaultUuid = defaultUuid)
    }

    fun fromHttpBody(body: String, contentType: String, source: String? = null, defaultUuid: String? = null): ClipboardEnvelope {
        val trimmed = body.trim()
        if (!contentType.lowercase(Locale.ROOT).contains("application/json")) {
            return fromAny(trimmed, source = source, defaultUuid = defaultUuid)
        }
        val parsed = try {
            JsonParser.parseString(trimmed.ifBlank { "{}" })
        } catch (_: Exception) {
            null
        }
        return when {
            parsed == null || parsed.isJsonNull -> ClipboardEnvelope(text = trimmed.ifBlank { null }, source = source, uuid = defaultUuid)
            parsed.isJsonPrimitive -> ClipboardEnvelope(text = parsed.asString.trim().ifBlank { null }, source = source, uuid = defaultUuid)
            else -> fromAny(gson.fromJson(parsed, Any::class.java), source = source, defaultUuid = defaultUuid)
        }
    }

    fun fromAny(raw: Any?, source: String? = null, defaultUuid: String? = null): ClipboardEnvelope {
        return when (raw) {
            null -> ClipboardEnvelope(source = source, uuid = defaultUuid)
            is ClipboardEnvelope -> raw.copy(
                source = raw.source ?: source,
                uuid = raw.uuid ?: defaultUuid
            )
            is String -> ClipboardEnvelope(text = raw.trim().ifBlank { null }, source = source, uuid = defaultUuid)
            is Number, is Boolean -> ClipboardEnvelope(text = raw.toString(), source = source, uuid = defaultUuid)
            is List<*> -> ClipboardEnvelope(
                json = canonicalJson(raw),
                source = source,
                uuid = defaultUuid,
                assets = parseAssets(raw, source ?: "payload")
            )
            is Map<*, *> -> fromMap(raw, source = source, defaultUuid = defaultUuid)
            else -> ClipboardEnvelope(text = raw.toString().trim().ifBlank { null }, source = source, uuid = defaultUuid)
        }
    }

    fun fromIntent(context: Context, intent: Intent, source: String = "intent"): ClipboardEnvelope {
        val directText = listOf(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val assets = linkedMapOf<String, DataAssetEnvelope>()
        collectAssetUris(intent).forEach { uri ->
            buildUriAsset(context, uri, source)?.let { asset ->
                assets["${asset.hash}:${asset.uri ?: asset.name}"] = asset
            }
        }

        val clipText = extractClipDataText(context, intent.clipData)
        val resolvedText = directText?.takeIf { it.isNotBlank() }
            ?: clipText?.takeIf { it.isNotBlank() }
            ?: assets.values.firstNotNullOfOrNull { it.text?.takeIf { text -> text.isNotBlank() } }

        return ClipboardEnvelope(
            text = resolvedText,
            mimeType = intent.type?.trim()?.ifBlank { null },
            source = source,
            assets = assets.values.toList(),
            metadata = linkedMapOf<String, Any?>().apply {
                intent.action?.trim()?.ifBlank { null }?.let { put("intentAction", it) }
                if (!intent.type.isNullOrBlank()) put("intentType", intent.type)
                if (intent.action == Intent.ACTION_SEND_MULTIPLE) put("multiple", true)
            }
        )
    }

    fun canonicalJson(value: Any?): String = gson.toJson(value)

    private fun fromMap(raw: Map<*, *>, source: String? = null, defaultUuid: String? = null): ClipboardEnvelope {
        val payload = nestedMap(raw)
        val resolvedSource = firstString(raw, "source") ?: firstString(payload, "source") ?: source
        val resolvedUuid = firstString(raw, "uuid", "id") ?: firstString(payload, "uuid", "id") ?: defaultUuid
        val resolvedMimeType = firstMimeType(raw, payload)
        val text = findBestText(raw, payload)
        val jsonPayload = findBestJson(raw, payload)
        val assets = (parseAssets(raw, resolvedSource ?: "payload") + parseAssets(payload, resolvedSource ?: "payload"))
            .distinctBy { "${it.hash}:${it.uri ?: it.name}:${it.mimeType}" }
        return ClipboardEnvelope(
            text = text,
            json = jsonPayload,
            mimeType = resolvedMimeType,
            label = firstString(raw, "label", "title") ?: firstString(payload, "label", "title"),
            source = resolvedSource,
            uuid = resolvedUuid,
            assets = assets,
            metadata = linkedMapOf<String, Any?>().apply {
                val targets = extractTargets(raw)
                if (targets.isNotEmpty()) put("targets", targets)
                firstString(raw, "byId", "from")?.let { put("from", it) }
                firstString(raw, "target", "to", "deviceId", "targetId", "targetDeviceId")?.let { put("target", it) }
            }
        )
    }

    private fun findBestText(root: Map<*, *>, nested: Map<*, *>?): String? {
        val direct = listOf(root, nested).filterNotNull().firstNotNullOfOrNull { map ->
            listOf("text", "body", "content", "clipboard", "value").firstNotNullOfOrNull { key ->
                when (val value = map[key]) {
                    null -> null
                    is String -> value.trim().ifBlank { null }
                    is Number, is Boolean -> value.toString()
                    else -> null
                }
            }
        }
        if (direct != null) return direct
        val rawData = root["data"] ?: root["payload"] ?: nested?.get("data") ?: nested?.get("payload")
        return when (rawData) {
            is String -> rawData.trim().ifBlank { null }
            is Number, is Boolean -> rawData.toString()
            else -> null
        }
    }

    private fun findBestJson(root: Map<*, *>, nested: Map<*, *>?): String? {
        val explicit = listOf(root["json"], nested?.get("json")).firstOrNull { it != null }
        if (explicit != null) {
            return when (explicit) {
                is String -> explicit.trim().ifBlank { null }
                else -> canonicalJson(explicit)
            }
        }
        val structured = listOf(root["payload"], root["data"], nested?.get("payload"), nested?.get("data"))
            .firstOrNull { it is Map<*, *> || it is List<*> }
        return when (structured) {
            null -> null
            else -> canonicalJson(structured)
        }
    }

    private fun nestedMap(root: Map<*, *>): Map<*, *>? {
        val nested = root["payload"] ?: root["data"]
        return nested as? Map<*, *>
    }

    private fun firstString(map: Map<*, *>?, vararg keys: String): String? {
        if (map == null) return null
        for (key in keys) {
            val value = map[key]?.toString()?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun firstMimeType(root: Map<*, *>, nested: Map<*, *>?): String? {
        return listOf(root, nested).filterNotNull().firstNotNullOfOrNull { map ->
            listOf("mimeType", "type", "contentType").firstNotNullOfOrNull { key ->
                val value = map[key]?.toString()?.trim()
                if (!value.isNullOrBlank() && value.contains("/")) value else null
            }
        }
    }

    private fun extractTargets(root: Map<*, *>): List<String> {
        val out = mutableListOf<String>()
        listOf("targets", "target", "targetId", "targetDeviceId", "deviceId", "to").forEach { key ->
            out.addAll(extractTargetValue(root[key]))
        }
        return out.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun extractTargetValue(value: Any?): List<String> {
        return when (value) {
            null -> emptyList()
            is String -> value.split("[;,]".toRegex()).map { it.trim() }.filter { it.isNotBlank() }
            is List<*> -> value.flatMap { extractTargetValue(it) }
            is Map<*, *> -> extractTargetValue(value["targets"] ?: value["target"] ?: value["to"] ?: value["deviceId"])
            else -> listOf(value.toString())
        }
    }

    private fun parseAssets(value: Any?, source: String): List<DataAssetEnvelope> {
        return when (value) {
            null -> emptyList()
            is List<*> -> value.flatMap { parseAssets(it, source) }
            is Map<*, *> -> {
                val explicit = listOf("assets", "asset", "files", "file", "attachments", "extraStream", "contentUri", "contentUris", "uri")
                    .flatMap { key -> parseAssets(value[key], source) }
                if (explicit.isNotEmpty()) {
                    explicit
                } else {
                    parseAssetMap(value, source)?.let { listOf(it) } ?: emptyList()
                }
            }
            is String -> parseAssetString(value, source)?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun parseAssetMap(map: Map<*, *>, source: String): DataAssetEnvelope? {
        val mimeType = firstMimeType(map, null) ?: "application/octet-stream"
        val directUri = firstString(map, "uri", "contentUri", "url")
        val directData = firstString(map, "data", "base64", "value")
        val directText = firstString(map, "text", "content")
        if (directUri.isNullOrBlank() && directData.isNullOrBlank() && directText.isNullOrBlank()) return null
        val normalizedData = when {
            !directData.isNullOrBlank() -> directData
            !directText.isNullOrBlank() -> directText
            else -> null
        }
        val encoding = when {
            map.containsKey("base64") -> "base64"
            map["encoding"] != null -> map["encoding"].toString()
            normalizedData == directText -> "utf8"
            else -> null
        }
        val hashSource = listOfNotNull(normalizedData, directUri, canonicalJson(map)).joinToString("|")
        val hash = hashSource.sha256Hex()
        val candidateName = firstString(map, "name", "filename")
            ?: buildCanonicalAssetName(prefixForMime(mimeType), hash, extensionFromMime(mimeType))
        return DataAssetEnvelope(
            hash = hash,
            name = candidateName,
            mimeType = mimeType,
            size = (map["size"] as? Number)?.toLong() ?: normalizedData?.length?.toLong() ?: 0L,
            source = source,
            data = normalizedData,
            encoding = encoding,
            uri = directUri,
            text = directText,
            metadata = emptyMap()
        )
    }

    private fun parseAssetString(value: String, source: String): DataAssetEnvelope? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("data:", ignoreCase = true)) {
            val commaIdx = trimmed.indexOf(',')
            if (commaIdx > 5) {
                val header = trimmed.substring(5, commaIdx)
                val body = trimmed.substring(commaIdx + 1)
                val mimeType = header.substringBefore(';').ifBlank { "application/octet-stream" }
                val encoding = if (header.contains(";base64", ignoreCase = true)) "base64" else "utf8"
                val hash = trimmed.sha256Hex()
                return DataAssetEnvelope(
                    hash = hash,
                    name = buildCanonicalAssetName(prefixForMime(mimeType), hash, extensionFromMime(mimeType)),
                    mimeType = mimeType,
                    size = body.length.toLong(),
                    source = source,
                    data = body,
                    encoding = encoding,
                    text = if (mimeType.startsWith("text/")) body else null
                )
            }
        }
        if (trimmed.startsWith("content://", ignoreCase = true) || trimmed.startsWith("file://", ignoreCase = true)) {
            val hash = trimmed.sha256Hex()
            return DataAssetEnvelope(
                hash = hash,
                name = buildCanonicalAssetName("uri", hash, extensionFromUri(trimmed)),
                mimeType = "application/octet-stream",
                size = 0L,
                source = source,
                uri = trimmed
            )
        }
        val looksLikeBase64 = trimmed.length >= 32 && trimmed.length % 4 == 0 && trimmed.matches(Regex("^[A-Za-z0-9+/=\\n\\r]+$"))
        if (looksLikeBase64) {
            val hash = trimmed.sha256Hex()
            return DataAssetEnvelope(
                hash = hash,
                name = buildCanonicalAssetName("blob", hash, "bin"),
                mimeType = "application/octet-stream",
                size = trimmed.length.toLong(),
                source = source,
                data = trimmed,
                encoding = "base64"
            )
        }
        return null
    }

    private fun collectAssetUris(intent: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        val stream = intent.parcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
        if (stream != null) out.add(stream)
        val streams = intent.parcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM)
        if (streams != null) out.addAll(streams)
        intent.data?.let(out::add)
        val clipData = intent.clipData
        if (clipData != null) {
            for (idx in 0 until clipData.itemCount) {
                clipData.getItemAt(idx)?.uri?.let(out::add)
            }
        }
        return out.distinctBy { it.toString() }
    }

    private fun extractClipDataText(context: Context, clipData: ClipData?): String? {
        if (clipData == null || clipData.itemCount <= 0) return null
        for (idx in 0 until clipData.itemCount) {
            val text = runCatching {
                clipData.getItemAt(idx)?.coerceToText(context)?.toString()?.trim()
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun buildUriAsset(context: Context, uri: Uri, source: String): DataAssetEnvelope? {
        return runCatching {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri)?.trim()?.ifBlank { null } ?: "application/octet-stream"
            var displayName: String? = null
            var size = 0L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx)?.trim()?.ifBlank { null }
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8 * 1024)
                val output = ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_INLINE_ASSET_BYTES) {
                        output.reset()
                        return@use null
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            val hash = bytes?.sha256Hex() ?: uri.toString().sha256Hex()
            val extension = displayName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
                ?: extensionFromMime(mimeType)
                ?: extensionFromUri(uri.toString())
            val name = buildCanonicalAssetName(prefixForMime(mimeType), hash, extension)
            val inlineText = if (bytes != null && mimeType.startsWith("text/")) bytes.toString(Charsets.UTF_8) else null
            val inlineData = when {
                bytes == null -> null
                mimeType.startsWith("text/") -> inlineText
                else -> Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            val encoding = when {
                bytes == null -> null
                mimeType.startsWith("text/") -> "utf8"
                else -> "base64"
            }
            DataAssetEnvelope(
                hash = hash,
                name = name,
                mimeType = mimeType,
                size = if (size > 0L) size else bytes?.size?.toLong() ?: 0L,
                source = source,
                data = inlineData,
                encoding = encoding,
                uri = uri.toString(),
                text = inlineText?.takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    private fun prefixForMime(mimeType: String): String {
        return when {
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("text/") -> "text"
            mimeType.startsWith("audio/") -> "audio"
            mimeType.startsWith("video/") -> "video"
            else -> "asset"
        }
    }

    private fun extensionFromMime(mimeType: String): String? {
        return when (mimeType.lowercase(Locale.ROOT)) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "text/plain" -> "txt"
            "application/json" -> "json"
            else -> mimeType.substringAfter('/', "").substringBefore(';').takeIf { it.isNotBlank() }
        }
    }

    private fun extensionFromUri(uri: String): String? {
        val tail = uri.substringAfterLast('/', "").substringAfterLast('.', "")
        return tail.takeIf { it.isNotBlank() }
    }

    private fun buildCanonicalAssetName(prefix: String, hash: String, extension: String?): String {
        val safePrefix = prefix.trim().ifBlank { "asset" }
        val safeExt = extension?.trim()?.trimStart('.')?.ifBlank { null }
        return if (safeExt == null) "$safePrefix-$hash" else "$safePrefix-$hash.$safeExt"
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            getParcelableExtra(name) as? T
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelableArrayListExtraCompat(name: String): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(name, T::class.java)
        } else {
            getParcelableArrayListExtra(name)
        }
    }
}

private fun ByteArray.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return digest.joinToString("") { "%02x".format(it) }
}

private fun String.sha256Hex(): String = toByteArray(Charsets.UTF_8).sha256Hex()
