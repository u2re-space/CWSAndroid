package space.u2re.service.reverse

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ReverseGatewayClient(
    private val config: ReverseGatewayConfig,
    private val onMessage: (String, String, String?) -> Unit = { _, _, _ -> }
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = 1000L
    private var running = false

    fun send(message: String) {
        socket?.send(message)
    }

    fun sendRelay(type: String, data: Any?) {
        val payload = mapOf(
            "type" to type,
            "data" to data,
            "ts" to System.currentTimeMillis()
        )
        val encoded = ReverseRelayCodec.encodeForServer(
            deviceId = config.deviceId,
            payload = payload,
            aesMasterKey = config.masterKey.ifBlank { null },
            signingPrivateKeyPem = config.signingPrivateKeyPem.ifBlank { null }
        )
        socket?.send(encoded)
    }

    fun start() {
        if (running) return;
        if (config.endpointUrl.isBlank() || config.userId.isBlank() || config.userKey.isBlank()) {
            Log.w(LOG_TAG, "Reverse gateway skipped: missing config");
            return;
        }
        if (!config.enabled) {
            Log.i(LOG_TAG, "Reverse gateway disabled by config");
            return;
        }
        running = true;
        connect();
    }

    fun stop() {
        running = false;
        heartbeatJob?.cancel();
        reconnectJob?.cancel();
        heartbeatJob = null;
        reconnectJob = null;
        socket?.close(1000, "stopped");
        socket = null;
    }

    private fun connect() {
        if (!running) return;
        if (config.endpointUrl.isBlank()) return;

        val request = buildRequest() ?: run {
            Log.e(LOG_TAG, "Reverse gateway URL is invalid");
            return;
        };
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = 1000L
                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (isActive) {
                        delay(20_000)
                        webSocket.send(
                            """{"type":"hello","deviceId":"${escapeJson(config.deviceId)}","ts":${System.currentTimeMillis()}}"""
                        )
                    }
                }
                webSocket.send("""{"type":"hello","deviceId":"${escapeJson(config.deviceId)}"}""")
                Log.i(LOG_TAG, "Reverse gateway connected");
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val decoded = try {
                        ReverseRelayCodec.decodeIncoming(
                            text,
                            config.masterKey.ifBlank { null },
                            config.peerPublicKeyPem.ifBlank { null }
                        )
                    } catch (_: Exception) {
                        null
                    }
                    var messageType = "message"
                    var bodyText = text
                    if (decoded != null) {
                        bodyText = when (val inner = decoded.inner) {
                            is String -> {
                                inner
                            }
                            is Map<*, *> -> {
                                val type = inner["type"] as? String
                                if (type != null) messageType = type
                                val body = inner["body"] ?: inner["data"] ?: inner["text"] ?: inner
                                body?.toString() ?: "{}"
                            }
                            else -> inner?.toString() ?: "{}"
                        }
                    }
                    onMessage(messageType, bodyText, config.deviceId)

                    if (messageType == "ping" || text.contains("\"type\":\"ping\"")) {
                        webSocket.send("""{"type":"pong","ts":${System.currentTimeMillis()}}""")
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Reverse gateway message parse failure", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (t is CancellationException) return
                Log.w(LOG_TAG, "Reverse gateway failed", t)
                webSocket.close(1000, "failure")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(LOG_TAG, "Reverse gateway closed: code=$code reason=$reason")
                if (running) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running) return
        heartbeatJob?.cancel()
        heartbeatJob = null
        val delayMs = reconnectDelayMs.coerceAtLeast(1_000).coerceAtMost(30_000)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            connect()
        }
    }

    private fun buildRequest(): Request? {
        val encodedUserId = encodeQuery(config.userId)
        val encodedUserKey = encodeQuery(config.userKey)
        val encodedDeviceId = encodeQuery(config.deviceId)
        val normalized = config.endpointUrl.trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        if (normalized.isBlank()) return null
        val url = "$normalized/ws?mode=reverse&userId=$encodedUserId&userKey=$encodedUserKey&deviceId=$encodedDeviceId"
        return Request.Builder().url(url).build()
    }

    private fun encodeQuery(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    companion object {
        private const val LOG_TAG = "ReverseGateway"
    }
}
