package space.u2re.service.endpoint

import io.socket.client.IO
import io.socket.client.Socket
import java.net.URI

class SocketIoTunnelClient(
    private val serverUrl: String,
    private val namespace: String? = null,
    private val onMessage: (String) -> Unit = {}
) {
    private var socket: Socket? = null
    private var started = false

    fun start() {
        if (started) return
        started = true

        val options = IO.Options().apply {
            reconnection = true
            reconnectionAttempts = 10
            reconnectionDelay = 1_000
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
            // keep channel open and available
        }
        socket?.on(Socket.EVENT_DISCONNECT) {
            // no-op
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
        socket?.off()
        socket = null
    }

    fun isConnected(): Boolean = socket?.connected() ?: false

    private fun normalizeServerUrl(raw: String): String = raw.trim().ifBlank {
        "http://127.0.0.1:3000"
    }
}
