package io.livekit.android.example.voiceassistant.daemon

import android.app.Activity
import android.telephony.SmsManager

suspend fun dispatchHttpRequests(requests: List<DispatchRequest>): List<DispatchResult> {
    val out = mutableListOf<DispatchResult>()
    for (request in requests) {
        try {
            val url = request.url.ifBlank { throw IllegalArgumentException("missing url") }
            val method = request.method.ifBlank { "POST" }.uppercase()
            val headers = request.headers.toMutableMap()
            if (headers["Content-Type"] == null && headers["content-type"] == null) {
                headers["Content-Type"] = "text/plain; charset=utf-8"
            }
            val result = when (method) {
                "POST", "PUT", "PATCH" -> {
                    postText(url, request.body, headers, allowInsecureTls = true, timeoutMs = 8000)
                }
                "GET" -> {
                    val requestText = ""
                    postText(url, requestText, headers, allowInsecureTls = true, timeoutMs = 8000)
                }
                else -> postText(url, request.body, headers, allowInsecureTls = true, timeoutMs = 8000)
            }
            out.add(
                DispatchResult(
                    url = url,
                    ok = result.ok,
                    status = result.status,
                    body = result.body
                )
            )
        } catch (e: Exception) {
            out.add(
                DispatchResult(
                    url = request.url,
                    ok = false,
                    status = null,
                    body = null,
                    error = e.message ?: e.toString()
                )
            )
        }
    }
    return out
}

suspend fun sendSmsAndroid(activity: Activity, number: String, content: String) {
    val hasPerm = PermissionManager.hasPermission(activity, android.Manifest.permission.SEND_SMS)
    if (!hasPerm) {
        throw IllegalStateException("SEND_SMS permission denied")
    }
    SmsManager.getDefault().sendTextMessage(number, null, content, null, null)
}
