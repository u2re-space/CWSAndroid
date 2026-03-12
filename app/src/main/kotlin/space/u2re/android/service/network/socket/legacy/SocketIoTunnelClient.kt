package space.u2re.cws.network

import android.net.Uri
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URI
import android.util.Log
import java.util.HashMap

data class SocketIoTunnelOptions(
    val auth: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val transports: List<String> = listOf("websocket", "polling"),
    val secure: Boolean? = null,
    val upgrade: Boolean? = null
)

class SocketIoTunnelClient(
    private val serverUrl: String,
    private val namespace: String? = null,
    private val onMessage: (String) -> Unit = {},
    private val options: SocketIoTunnelOptions = SocketIoTunnelOptions(),
    private val onState: (String, String?) -> Unit = { _, _ -> }
) {
    private var socket: Socket? = null
    private var started = false
    private val eventLogThrottle = HashMap<String, Long>()
    private var lastSocketEventLogCount = 0
    private var lastSocketEvent: String? = null

    companion object {
        private const val LOG_TAG = "SocketIoTunnel"
        private const val SOCKET_IO_EVENT_LOG_COOLDOWN_MS = 3_000L
        private const val LOW_LEVEL_ERROR_COOLDOWN_MS = 10_000L
    }

    fun start() {
        if (started) return
        started = true

        val options = IO.Options().apply {
            reconnection = true
            reconnectionAttempts = 10
            reconnectionDelay = 1_000
            auth.putAll(this@SocketIoTunnelClient.options.auth)
            query = this@SocketIoTunnelClient.options.query.entries.joinToString("&") { (key, value) ->
                "${Uri.encode(key)}=${Uri.encode(value)}"
            }
            transports = this@SocketIoTunnelClient.options.transports.toTypedArray()
            this@SocketIoTunnelClient.options.upgrade?.let { upgrade = it }
        }

        val endpoint = normalizeServerUrl(serverUrl)
        socket = try {
            if (namespace.isNullOrBlank()) {
                IO.socket(endpoint, options)
            } else {
                IO.socket(URI("$endpoint/$namespace"), options)
            }
        } catch (_: Exception) {
            started = false
            return
        }

        socket?.on(Socket.EVENT_CONNECT) {
            logSocketIoEvent("connected", "endpoint=$endpoint namespace=${namespace ?: "default"}")
            onState("connected", "socketio endpoint=$endpoint namespace=${namespace ?: "default"}")
        }

        socket?.on(Socket.EVENT_DISCONNECT) { args ->
            val reason = (args.firstOrNull() ?: "unknown").toString()
            logSocketIoEvent("disconnected", "endpoint=$endpoint namespace=${namespace ?: "default"} reason=$reason")
            onState("disconnected", "socketio endpoint=$endpoint namespace=${namespace ?: "default"} reason=$reason")
        }

        socket?.on("reconnect") {
            logSocketIoEvent("reconnect", "endpoint=$endpoint namespace=${namespace ?: "default"}")
            onState("reconnect", "socketio endpoint=$endpoint namespace=${namespace ?: "default"}")
        }

        socket?.on("reconnecting") { _ ->
            logSocketIoEvent("reconnecting", "endpoint=$endpoint namespace=${namespace ?: "default"}")
            onState("reconnecting", "socketio endpoint=$endpoint namespace=${namespace ?: "default"}")
        }

        socket?.on("reconnect_error") { args ->
            val error = args.firstOrNull()?.toString() ?: "error"
            logSocketIoEvent("reconnect-error", "endpoint=$endpoint namespace=${namespace ?: "default"} error=$error")
            onState("reconnect-error", "socketio endpoint=$endpoint namespace=${namespace ?: "default"} error=$error")
        }

        socket?.on("reconnect_failed") {
            logSocketIoEvent("reconnect-failed", "endpoint=$endpoint namespace=${namespace ?: "default"}")
            onState("reconnect-failed", "socketio endpoint=$endpoint namespace=${namespace ?: "default"}")
        }

        socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = args.firstOrNull()?.toString() ?: "connect error"
            logSocketIoEvent("connect-error", "endpoint=$endpoint namespace=${namespace ?: "default"} error=$error")
            onState("connect-error", "socketio endpoint=$endpoint namespace=${namespace ?: "default"} error=$error")
        }

        socket?.on("error") { args ->
            val error = args.firstOrNull()?.toString() ?: "socket error"
            logSocketIoEvent("socket-error", "endpoint=$endpoint namespace=${namespace ?: "default"} error=$error")
            onState("socket-error", "socketio endpoint=$endpoint namespace=${namespace ?: "default"} error=$error")
        }

        socket?.on("data") { args ->
            val text = args.firstOrNull()?.toString() ?: return@on
            onMessage(text)
        }
        socket?.on("message") { args ->
            val text = args.firstOrNull()?.toString() ?: return@on
            onMessage(text)
        }
        socket?.on("reverse-message") { args ->
            val text = args.firstOrNull()?.toString() ?: return@on
            onMessage(text)
        }
        socket?.connect()
    }

    fun send(event: String, payload: String) {
        socket?.emit(event, payload)
    }

    fun stop() {
        if (!started) return
        started = false
        socket?.disconnect()
        logSocketIoEvent("stopped", "endpoint=$serverUrl namespace=${namespace ?: "default"}")
        socket?.off()
        socket = null
        eventLogThrottle.clear()
        lastSocketEventLogCount = 0
        lastSocketEvent = null
    }

    fun isConnected(): Boolean = socket?.connected() ?: false

    private fun normalizeServerUrl(raw: String): String = raw.trim().ifBlank {
        "http://127.0.0.1:3000"
    }

    private fun shouldLogSocketIoEvent(event: String, detail: String): Boolean {
        val isErrorEvent = event == "connect-error" || event == "reconnect-error" || event == "socket-error"
        val key = if (isErrorEvent) event else "$event|$detail"
        val now = System.currentTimeMillis()
        val last = eventLogThrottle[key] ?: 0L
        val cooldown = if (isErrorEvent) LOW_LEVEL_ERROR_COOLDOWN_MS else SOCKET_IO_EVENT_LOG_COOLDOWN_MS
        if (now - last < cooldown) {
            val previous = lastSocketEvent
            if (previous == event) {
                lastSocketEventLogCount++
            } else {
                if (lastSocketEventLogCount > 1) {
                    Log.d(LOG_TAG, "Socket.IO event '$previous' repeated x$lastSocketEventLogCount")
                }
                lastSocketEvent = event
                lastSocketEventLogCount = 1
            }
            return false
        }
        eventLogThrottle[key] = now
        if (lastSocketEvent == event) {
            if (lastSocketEventLogCount > 1) {
                Log.d(LOG_TAG, "Socket.IO event '$event' repeated x$lastSocketEventLogCount")
            }
            lastSocketEventLogCount = 0
        }
        lastSocketEvent = event
        return true
    }

    private fun logSocketIoEvent(event: String, detail: String) {
        if (!shouldLogSocketIoEvent(event, detail)) return
        Log.d(LOG_TAG, "Socket.IO $event [$detail]")
    }
}
