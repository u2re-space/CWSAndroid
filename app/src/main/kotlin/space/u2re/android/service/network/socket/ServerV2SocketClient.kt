package space.u2re.cws.network

import java.util.UUID

data class ServerV2SocketDiagnostics(
    val connected: Boolean,
    val activeTransport: String,
    val endpoint: String,
    val lastState: String,
    val lastDetail: String?
)

class ServerV2SocketClient(
    private val config: EndpointCoreConfig,
    private val onPacket: (ServerV2Packet) -> Unit = {},
    private val onState: (String, String?) -> Unit = { _, _ -> }
) {
    private var socket: SocketIoTunnelClient? = null
    private var started = false
    private var connected = false
    private var lastState = "stopped"
    private var lastDetail: String? = "not-started"

    fun start() {
        if (started) return
        if (!config.isRemoteReady()) {
            updateState("disabled", "server-v2 socket config incomplete")
            return
        }
        started = true
        updateState("starting", "server-v2 socket starting")
        val userId = config.userId.ifBlank { config.deviceId }.trim()
        val authToken = config.userKey.trim()
        val endpoint = config.endpointUrl.ifBlank { config.dispatchUrl }
        socket = SocketIoTunnelClient(
            serverUrl = endpoint,
            namespace = null,
            onMessage = { raw ->
                val packet = ServerV2PacketCodec.decode(raw) ?: return@SocketIoTunnelClient
                onPacket(packet)
            },
            options = SocketIoTunnelOptions(
                auth = buildMap {
                    if (authToken.isNotBlank()) {
                        put("token", authToken)
                        put("airpadToken", authToken)
                    }
                    if (userId.isNotBlank()) {
                        put("clientId", userId)
                        put("userId", userId)
                    }
                },
                query = buildMap {
                    if (authToken.isNotBlank()) {
                        put("token", authToken)
                        put("airpadToken", authToken)
                        put("userKey", authToken)
                    }
                    if (userId.isNotBlank()) {
                        put("clientId", userId)
                        put("__airpad_client", userId)
                        put("userId", userId)
                    }
                    put("connectionType", "exchanger-initiator")
                    put("archetype", "server-v2")
                },
                transports = listOf("websocket", "polling"),
                secure = endpoint.startsWith("https://", ignoreCase = true)
            ),
            onState = { event, detail ->
                connected = event == "connected" || event == "reconnect"
                updateState(event, detail)
                if (event == "connected" || event == "reconnect") {
                    hello()
                }
            }
        )
        socket?.start()
    }

    fun stop() {
        started = false
        connected = false
        socket?.stop()
        socket = null
        updateState("stopped", "server-v2 socket stopped")
    }

    fun requestReconnect() {
        if (!started) return
        updateState("reconnect-requested", "server-v2 socket reconnect requested")
        socket?.stop()
        socket?.start()
    }

    fun isConnected(): Boolean = connected && socket?.isConnected() == true

    fun getDiagnostics(): ServerV2SocketDiagnostics = ServerV2SocketDiagnostics(
        connected = isConnected(),
        activeTransport = "socketio",
        endpoint = config.endpointUrl.ifBlank { config.dispatchUrl },
        lastState = lastState,
        lastDetail = lastDetail
    )

    fun sendPacket(packet: ServerV2Packet): Boolean {
        val active = socket ?: return false
        if (!isConnected()) return false
        val normalized = packet.copy(
            op = packet.op.ifBlank { "ask" },
            uuid = packet.uuid ?: UUID.randomUUID().toString(),
            byId = packet.byId ?: config.userId.ifBlank { config.deviceId },
            timestamp = if (packet.timestamp > 0L) packet.timestamp else System.currentTimeMillis()
        )
        return try {
            active.send("data", ServerV2PacketCodec.encode(normalized))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hello(): Boolean {
        return sendPacket(
            ServerV2Packet(
                op = "ask",
                what = "token",
                payload = emptyMap<String, Any>(),
                nodes = listOf("*")
            )
        )
    }

    private fun updateState(event: String, detail: String?) {
        lastState = event
        lastDetail = detail
        onState(event, detail)
    }
}
