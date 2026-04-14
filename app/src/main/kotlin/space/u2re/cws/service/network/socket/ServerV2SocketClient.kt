package space.u2re.cws.network

import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private companion object {
        private val PREFERRED_TRANSPORTS = listOf("websocket", "polling")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectMonitorJob: Job? = null
    private var lastReconnectAtMs: Long = 0L
    private val reconnectEveryMs: Long = 1_000L

    private var socket: SocketIoTunnelClient? = null
    private val endpointCandidates = ServerV2WireContract.resolveSocketEndpointCandidates(config)
    private var endpointIndex = 0
    private val tlsRejectedHosts = mutableSetOf<String>()
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
        restartSocket(rotate = false)

        // Ensure stability even when network callbacks are not fired:
        // attempt reconnect roughly every second after disconnect.
        reconnectMonitorJob?.cancel()
        reconnectMonitorJob = scope.launch {
            while (isActive && started) {
                val ok = socket?.isConnected() == true
                if (!ok) {
                    val now = System.currentTimeMillis()
                    if (now - lastReconnectAtMs >= reconnectEveryMs) {
                        lastReconnectAtMs = now
                        val fromEndpoint = currentEndpoint()
                        restartSocket(rotate = true)
                        updateState(
                            "reconnect-loop",
                            "server-v2 disconnected; auto reconnecting endpoint $fromEndpoint -> ${currentEndpoint()}"
                        )
                    }
                }
                delay(reconnectEveryMs)
            }
        }
    }

    fun stop() {
        started = false
        connected = false
        reconnectMonitorJob?.cancel()
        reconnectMonitorJob = null
        socket?.stop()
        socket = null
        updateState("stopped", "server-v2 socket stopped")
    }

    fun requestReconnect() {
        if (!started) return
        lastReconnectAtMs = System.currentTimeMillis()
        val fromEndpoint = currentEndpoint()
        restartSocket(rotate = true)
        updateState(
            "reconnect-requested",
            "server-v2 socket reconnect requested endpoint $fromEndpoint -> ${currentEndpoint()}"
        )
    }

    fun isConnected(): Boolean = connected && socket?.isConnected() == true

    fun getDiagnostics(): ServerV2SocketDiagnostics = ServerV2SocketDiagnostics(
        connected = isConnected(),
        activeTransport = "socketio",
        endpoint = currentEndpoint(),
        lastState = lastState,
        lastDetail = lastDetail
    )

    fun sendPacket(packet: ServerV2Packet): Boolean {
        val active = socket ?: return false
        if (!isConnected()) return false
        val identity = ServerV2WireContract.resolve(config)
        val normalized = packet.copy(
            op = packet.op.ifBlank { "ask" },
            uuid = packet.uuid ?: UUID.randomUUID().toString(),
            byId = packet.byId ?: identity.senderId(),
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
        val identity = currentIdentity()
        return sendPacket(
            ServerV2Packet(
                op = "ask",
                what = "token",
                payload = emptyMap<String, Any>(),
                nodes = listOf("*"),
                byId = identity.senderId()
            )
        )
    }

    private fun updateState(event: String, detail: String?) {
        maybeMarkTlsRejectedEndpoint(detail)
        lastState = event
        lastDetail = detail
        onState(event, detail)
    }

    private fun currentEndpoint(): String {
        if (endpointCandidates.isEmpty()) {
            return ServerV2WireContract.resolve(config).endpointUrl.ifBlank { config.dispatchUrl }
        }
        return endpointCandidates[endpointIndex.coerceIn(0, endpointCandidates.lastIndex)]
    }

    private fun currentIdentity(): ServerV2WireIdentity {
        val base = ServerV2WireContract.resolve(config)
        val endpoint = currentEndpoint().ifBlank { base.endpointUrl }
        return base.copy(endpointUrl = endpoint)
    }

    private fun advanceEndpoint() {
        if (endpointCandidates.size <= 1) return
        val start = endpointIndex
        var next = (endpointIndex + 1) % endpointCandidates.size
        while (next != start) {
            if (!shouldSkipEndpoint(endpointCandidates[next])) {
                endpointIndex = next
                return
            }
            next = (next + 1) % endpointCandidates.size
        }
        endpointIndex = next
    }

    private fun restartSocket(rotate: Boolean) {
        if (rotate) advanceEndpoint()
        socket?.stop()
        val identity = currentIdentity()
        socket = SocketIoTunnelClient(
            serverUrl = identity.endpointUrl,
            namespace = null,
            onMessage = { raw ->
                val packet = ServerV2PacketCodec.decode(raw) ?: return@SocketIoTunnelClient
                onPacket(packet)
            },
            options = SocketIoTunnelOptions(
                auth = ServerV2WireContract.buildAuth(identity),
                query = ServerV2WireContract.buildQuery(identity),
                // Prefer websocket while retaining polling fallback for strict/mobile TLS edges.
                transports = PREFERRED_TRANSPORTS,
                secure = identity.endpointUrl.startsWith("https://", ignoreCase = true),
                allowInsecureTls = config.allowInsecureTls,
                trustedCa = config.trustedCa
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

    private fun maybeMarkTlsRejectedEndpoint(detail: String?) {
        val normalized = detail?.lowercase()?.trim().orEmpty()
        if (!normalized.contains("unexpected end of stream")) return
        val endpoint = currentEndpoint()
        if (!isSecureSocketScheme(endpoint)) return
        val host = endpointHost(endpoint)
        if (host.isNotBlank()) {
            tlsRejectedHosts.add(host)
        }
    }

    private fun shouldSkipEndpoint(endpoint: String): Boolean {
        if (!isSecureSocketScheme(endpoint)) return false
        val host = endpointHost(endpoint)
        return host.isNotBlank() && tlsRejectedHosts.contains(host)
    }

    private fun isSecureSocketScheme(endpoint: String): Boolean {
        return endpoint.startsWith("https://", ignoreCase = true) || endpoint.startsWith("wss://", ignoreCase = true)
    }

    private fun endpointHost(endpoint: String): String {
        return try {
            URI(endpoint).host?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
