package space.u2re.cws.reverse

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.u2re.cws.daemon.Settings
import space.u2re.cws.daemon.SettingsStore
import space.u2re.cws.daemon.resolve
import space.u2re.cws.network.postJson
import space.u2re.cws.network.postText

import space.u2re.cws.daemon.Daemon
import space.u2re.cws.network.DispatchRequest

private val BridgeLogger = "ReverseAssistantBridge"
private val gson = Gson()

object AssistantNetworkBridge {
    suspend fun handleReverseMessage(context: Context, messageType: String, rawPayload: String, config: ReverseGatewayConfig, callbacks: Daemon.SyncCallbacks? = null): Boolean =
        withContext(Dispatchers.IO) {
            val settings = SettingsStore.load(context).resolve()
            val payload = parsePayload(rawPayload, messageType) ?: return@withContext false
            val action = extractString(payload["action"])?.lowercase()
                ?: extractString(payload["type"])?.lowercase()
                ?: messageType.lowercase()
            val namespace = extractString(payload["namespace"])?.trim()?.ifBlank { null }
            val target = extractTarget(payload)
            if (!isTargetMatch(target, config.deviceId, settings)) {
                Log.d(BridgeLogger, "Skip message for different target=$target")
                return@withContext false
            }

            return@withContext when (action) {
                "feature" -> {
                    val featureName = extractString(payload["data"]?.let { ensureObject(it)?.get("feature") })?.lowercase()
                    val featurePayload = ensureObject(payload["data"])?.get("payload")?.let { ensureObject(it) } ?: JsonObject()
                    when (featureName) {
                        "sms" -> sendLocalSms(context, featurePayload, settings, callbacks)
                        "notifications.speak" -> sendLocalSpeak(context, featurePayload, settings, callbacks)
                        else -> {
                            Log.d(BridgeLogger, "Unhandled feature=$featureName message=$payload")
                            false
                        }
                    }
                }
                "clipboard", "clipboard.set", "set_clipboard", "copy", "paste", "write_clipboard" ->
                    sendLocalClipboard(context, payload, settings, callbacks)
                "sms", "send_sms", "sms.send", "sms-send", "send.sms" ->
                    sendLocalSms(context, payload, settings, callbacks)
                "speak", "notifications.speak", "notification.speak", "speak.notification" ->
                    sendLocalSpeak(context, payload, settings, callbacks)
                "dispatch", "forward", "http", "network.dispatch" ->
                    sendLocalDispatch(context, payload, settings, namespace, action, callbacks)
                else -> {
                    val forwarded = payload.has("requests") || payload.has("to")
                    if (forwarded) {
                        sendLocalDispatch(context, payload, settings, namespace, action, callbacks)
                    } else if (action == "hello") {
                        true
                    } else {
                        Log.d(BridgeLogger, "Unhandled action=$action message=$payload")
                        false
                    }
                }
            }
        }

    private suspend fun sendLocalClipboard(context: Context, payload: JsonObject, settings: Settings, callbacks: Daemon.SyncCallbacks?): Boolean {
        val dataObj = ensureObject(payload["data"])
        val text = extractString(payload["text"]) 
            ?: extractString(dataObj?.get("text"))
            ?: extractString(dataObj?.get("content"))
            ?: extractString(payload["data"]) 
            ?: extractString(payload["body"]) 
            ?: ""
        if (text.isBlank()) return false
        if (callbacks != null) {
            callbacks.setClipboardText(text)
            return true
        }
        val url = localBaseUrl(settings) + "/clipboard"
        val headers = requestHeaders(settings) + mapOf("Content-Type" to "text/plain; charset=utf-8")
        return postText(url, text, headers, allowInsecureTls = true, timeoutMs = 8000).ok
    }

    private suspend fun sendLocalSms(context: Context, payload: JsonObject, settings: Settings, callbacks: Daemon.SyncCallbacks?): Boolean {
        val number = extractString(payload["number"]) ?: extractString(payload["data"]?.let { ensureObject(payload["data"])?.get("number") })
        val content = extractString(payload["content"])
            ?: extractString(payload["text"])
            ?: extractString(payload["data"]?.let { ensureObject(payload["data"])?.get("content") })
        if (number.isNullOrBlank() || content.isNullOrBlank()) return false
        if (callbacks != null) {
            callbacks.sendSms(number, content)
            return true
        }
        val data = mapOf("number" to number, "content" to content)
        val url = localBaseUrl(settings) + "/sms"
        return postJson(url, data, allowInsecureTls = true, headers = requestHeaders(settings)).ok
    }

    private suspend fun sendLocalSpeak(context: Context, payload: JsonObject, settings: Settings, callbacks: Daemon.SyncCallbacks?): Boolean {
        val dataObj = ensureObject(payload["data"])
        val text = extractString(payload["text"])
            ?: extractString(dataObj?.get("text"))
            ?: extractString(dataObj?.get("content"))
            ?: extractString(payload["data"])
            ?: extractString(payload["body"])
            ?: return false
        if (callbacks != null) {
            callbacks.speakNotificationText(text)
            return true
        }
        val url = localBaseUrl(settings) + "/notifications/speak"
        return postJson(url, mapOf("text" to text), allowInsecureTls = true, headers = requestHeaders(settings)).ok
    }

    private suspend fun sendLocalDispatch(
        context: Context,
        payload: JsonObject,
        settings: Settings,
        namespace: String?,
        action: String,
        callbacks: Daemon.SyncCallbacks?
    ): Boolean {
        if (action == "dispatch") {
            val rawTarget = extractTarget(payload)
            val localIdentity = listOf(
                settings.hubClientId,
                settings.authToken,
                settings.deviceId,
                settings.hubToken
            ).firstOrNull { it.isNotBlank() } ?: ""
            if (!isTargetMatch(rawTarget, localIdentity, settings)) {
                return false
            }
            val dataAsText = extractString(payload["data"])
            val isTextData = payload["data"]?.isJsonPrimitive == true && dataAsText != null && dataAsText.isNotBlank()
            val hasRequestsField = payload.has("requests")
            if (!hasRequestsField && isTextData) {
                return sendLocalClipboard(context, payload, settings, callbacks)
            }
        }

        val requestsObj = payload["requests"] ?: payload["data"]?.let { parseDispatchRequests(it) } ?: parseDispatchRequests(payload)
        if (requestsObj == null) return false
        
        if (callbacks != null) {
            val requestsList = try {
                val requestsRaw = if (requestsObj is JsonElement) requestsObj.toString() else gson.toJson(requestsObj)
                val typeToken = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
                val parsedList = gson.fromJson<List<Map<String, Any>>>(requestsRaw, typeToken)
                parsedList.mapNotNull { map ->
                    val rawUrl = map["url"]?.toString() ?: ""
                    if (rawUrl.isBlank()) return@mapNotNull null
                    DispatchRequest(
                        url = rawUrl,
                        method = map["method"]?.toString()?.ifBlank { "POST" } ?: "POST",
                        headers = (map["headers"] as? Map<*, *>)?.map { e -> e.key.toString() to e.value.toString() }?.toMap() ?: emptyMap(),
                        body = map["body"]?.toString() ?: "",
                        unencrypted = map["unencrypted"] as? Boolean ?: false
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
            if (requestsList.isNotEmpty()) {
                callbacks.dispatch(requestsList)
                return true
            }
        }
        
        val routeBody = when (action) {
            "network.dispatch" -> mapOf(
                "userId" to (payload["userId"]?.asString?.ifBlank { null } ?: ""),
                "userKey" to (payload["userKey"]?.asString?.ifBlank { null } ?: ""),
                "namespace" to namespace.orEmpty(),
                "requests" to requestsObj
            )
            else -> mapOf("requests" to requestsObj)
        }
        val url = localBaseUrl(settings) + "/core/ops/http/dispatch"
        return postJson(url, routeBody, allowInsecureTls = true, headers = requestHeaders(settings)).ok
    }

    private fun localBaseUrl(settings: Settings): String = "http://127.0.0.1:${settings.listenPortHttp}"

    private fun requestHeaders(settings: Settings): Map<String, String> {
        val auth = settings.authToken.trim()
        if (auth.isBlank()) return emptyMap()
        return mapOf("x-auth-token" to auth)
    }

private fun buildTargetAliases(localDeviceId: String, settings: Settings): Set<String> {
    val normalized = listOf(
        localDeviceId,
        settings.deviceId,
        settings.authToken,
        settings.hubClientId,
        settings.hubToken
    ).map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toMutableSet()
    val expanded = normalized.flatMap { value ->
        if (value.startsWith("l-")) listOf(value, value.removePrefix("l-")) else listOf(value)
    }
    return normalized.union(expanded.toSet())
}

private fun isTargetMatch(target: String?, localDeviceId: String, settings: Settings): Boolean {
    val trimmed = target?.trim()
    if (trimmed.isNullOrBlank()) return true
    if (trimmed.equals("broadcast", ignoreCase = true)) return true
    if (trimmed.equals("all", ignoreCase = true)) return true

    val normalizedTarget = trimmed.lowercase()
    val aliases = buildTargetAliases(localDeviceId, settings)
    if (aliases.contains(normalizedTarget)) return true
    if (normalizedTarget.startsWith("l-") && aliases.contains(normalizedTarget.removePrefix("l-"))) return true
    if (!normalizedTarget.startsWith("l-")) {
        val prefixed = "l-$normalizedTarget"
        return aliases.contains(prefixed)
    }
    return false
}

    private fun extractTarget(payload: JsonObject): String? {
        return extractString(payload["target"])
            ?: extractString(payload["deviceId"])
            ?: extractString(payload["device"])
            ?: extractString(payload["targetId"])
            ?: extractString(payload["to"])
    }

    private fun parsePayload(rawPayload: String, fallbackType: String): JsonObject? {
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

    private fun extractString(value: JsonElement?): String? = when {
        value == null || value.isJsonNull -> null
        value.isJsonPrimitive -> value.asString.trim().ifBlank { null }
        else -> value.toString()
    }

    private fun ensureObject(value: JsonElement?): JsonObject? {
        return if (value?.isJsonObject == true) value.asJsonObject else null
    }

    private fun parseDispatchRequests(value: JsonElement?): Any? {
        if (value == null || value.isJsonNull) return null
        if (value.isJsonArray) {
            return try {
                gson.fromJson<List<Any>>(value, List::class.java)
            } catch (_: Exception) {
                null
            }
        }
        if (value.isJsonObject) {
            val obj = value.asJsonObject
            val directRequests = obj["requests"]
            if (directRequests != null && directRequests.isJsonArray) return parseDispatchRequests(directRequests)
            return listOf(gson.fromJson<Map<*, *>>(obj, Map::class.java) as Map<*, *>)
        }
        return null
    }
}
