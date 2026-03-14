package space.u2re.cws.reverse

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import space.u2re.cws.daemon.Daemon
import space.u2re.cws.network.DispatchRequest
import space.u2re.cws.network.postJson
import space.u2re.cws.settings.Settings

internal object ReverseDispatchHandler {
    suspend fun handle(
        context: Context,
        payload: JsonObject,
        settings: Settings,
        namespace: String?,
        action: String,
        callbacks: Daemon.SyncCallbacks?
    ): Boolean {
        val dispatchDataObj = nestedPayloadObject(payload)
        val dispatchDataAction = extractString(dispatchDataObj?.get("action"))?.lowercase()
            ?: extractString(dispatchDataObj?.get("type"))?.lowercase()
            ?: extractString(payload["what"])?.lowercase()
        val dispatchText = extractPrimitiveString(payload["text"])
            ?: extractPrimitiveString(payload["body"])
            ?: extractPrimitiveString(payload["data"])
            ?: extractPrimitiveString(payload["payload"])
            ?: extractPrimitiveString(dispatchDataObj?.get("text"))
            ?: extractPrimitiveString(dispatchDataObj?.get("content"))
            ?: extractPrimitiveString(dispatchDataObj?.get("body"))

        if (!payload.has("requests")) {
            val nestedClipboardAction = dispatchDataAction?.contains("clipboard") == true
            if (nestedClipboardAction && !dispatchText.isNullOrBlank()) {
                val clipboardPayload = JsonObject().apply {
                    addProperty("type", "clipboard")
                    addProperty("text", dispatchText)
                    add("data", JsonObject().apply {
                        addProperty("text", dispatchText)
                    })
                }
                return ReverseClipboardHandler.handleDelivery(context, clipboardPayload, settings, callbacks)
            }
        }

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
                return ReverseClipboardHandler.handleDelivery(context, payload, settings, callbacks)
            }
        }

        val requestsObj = payload["requests"] ?: payload["data"]?.let(::parseDispatchRequests) ?: parseDispatchRequests(payload)
        if (requestsObj == null) return false

        if (callbacks != null) {
            val requestsList = toDispatchRequests(requestsObj)
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

    private fun toDispatchRequests(requestsObj: Any): List<DispatchRequest> {
        return try {
            val requestsRaw = if (requestsObj is JsonElement) requestsObj.toString() else reverseBridgeGson.toJson(requestsObj)
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val parsedList = reverseBridgeGson.fromJson<List<Map<String, Any>>>(requestsRaw, type)
            parsedList.mapNotNull { map ->
                val rawUrl = map["url"]?.toString() ?: ""
                if (rawUrl.isBlank()) return@mapNotNull null
                DispatchRequest(
                    url = rawUrl,
                    method = map["method"]?.toString()?.ifBlank { "POST" } ?: "POST",
                    headers = (map["headers"] as? Map<*, *>)?.map { entry ->
                        entry.key.toString() to entry.value.toString()
                    }?.toMap() ?: emptyMap(),
                    body = map["body"]?.toString() ?: "",
                    unencrypted = map["unencrypted"] as? Boolean ?: false
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
