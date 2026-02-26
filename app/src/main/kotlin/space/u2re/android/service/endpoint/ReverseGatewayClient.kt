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
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import com.google.gson.Gson

class ReverseGatewayClient(
    private val config: ReverseGatewayConfig,
    private val onMessage: (String, String, String?) -> Unit = { _, _, _ -> }
) {
    private val configuredKeepAliveMs = config.keepAliveIntervalMs.coerceAtLeast(1_000L)
    private val configuredReconnectMs = config.reconnectDelayMs.coerceAtLeast(500L)

    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(configuredKeepAliveMs, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = configuredReconnectMs
    private var running = false

    fun send(message: String) {
        socket?.send(message)
    }

    fun sendRelay(type: String, data: Any?, target: String? = null, route: String? = null, namespace: String? = null) {
        val payload = mapOf(
            "type" to type,
            "target" to (target ?: "broadcast"),
            "route" to (route ?: "local"),
            "namespace" to (namespace ?: config.namespace),
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
        val missingConfig = listOfNotNull(
            if (config.endpointUrl.isBlank()) "endpointUrl" else null,
            if (config.userId.isBlank()) "userId" else null,
            if (config.userKey.isBlank()) "userKey" else null
        )
        if (missingConfig.isNotEmpty()) {
            Log.w(LOG_TAG, "Reverse gateway skipped: missing config (${missingConfig.joinToString(", ")})");
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
                reconnectDelayMs = configuredReconnectMs
                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (isActive) {
                        delay(configuredKeepAliveMs)
                        webSocket.send(
                            """{"type":"hello","deviceId":"${escapeJson(config.deviceId)}","namespace":"${escapeJson(config.namespace)}","roles":"${escapeJson(config.roles)}","ts":${System.currentTimeMillis()}}"""
                        )
                    }
                }
                webSocket.send(
                    """{"type":"hello","deviceId":"${escapeJson(config.deviceId)}","namespace":"${escapeJson(config.namespace)}","roles":"${escapeJson(config.roles)}"}"""
                )
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
                        when (body) {
                            is String -> body
                            else -> gson.toJson(body)
                        }
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
        val encodedNamespace = encodeQuery(config.namespace.ifBlank { "default" })
        val encodedRoles = encodeQuery(config.roles.ifBlank { "endpoint,peer,node,app" })
        val trimmed = config.endpointUrl.trim().trimStart('/')
        if (trimmed.isBlank()) return null

        val websocketBase = when {
            trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
            trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "ws://")
            trimmed.startsWith("https://") -> trimmed.replaceFirst("https://", "wss://")
            else -> "wss://$trimmed"
        }.trimEnd('/')
        val parsed = try {
            URI(websocketBase)
        } catch (_: Exception) {
            return null
        }

        val rawPath = parsed.path.orEmpty()
        val hasWsPath = Regex("/ws(?:[/?#]|$)").containsMatchIn(rawPath)
        val wsPath = when {
            hasWsPath -> rawPath.ifBlank { "/ws" }
            rawPath.isBlank() || rawPath == "/" -> "/ws"
            rawPath == "/api/broadcast" || rawPath.startsWith("/api/broadcast/") -> "/ws"
            else -> {
                val normalized = if (rawPath.endsWith("/")) rawPath else "$rawPath/"
                "${normalized}ws"
            }
        }
        val websocketUrl = URI(
            parsed.scheme,
            parsed.userInfo,
            parsed.host,
            parsed.port,
            wsPath,
            null,
            null
        ).toString()

        val url = "$websocketUrl?mode=reverse&userId=$encodedUserId&userKey=$encodedUserKey&deviceId=$encodedDeviceId&namespace=$encodedNamespace&roles=$encodedRoles"

        return try {
            Request.Builder().url(url).build()
        } catch (_: Exception) {
            null
        }
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
        private val gson = Gson()
    }
}
