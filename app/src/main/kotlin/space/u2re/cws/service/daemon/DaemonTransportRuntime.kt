package space.u2re.cws.daemon

import space.u2re.cws.data.ClipboardEnvelope
import space.u2re.cws.network.EndpointCoreConfig
import space.u2re.cws.network.ReverseGatewayClient
import space.u2re.cws.network.ServerV2HttpClient
import space.u2re.cws.network.ServerV2Packet
import space.u2re.cws.network.ServerV2PacketCodec
import space.u2re.cws.network.ServerV2SocketClient
import space.u2re.cws.network.ServerV2WireContract
import space.u2re.cws.reverse.ReverseGatewayConfig
import java.util.concurrent.atomic.AtomicLong

class DaemonTransportRuntime(
    private val endpointConfig: EndpointCoreConfig,
    private val reverseConfig: ReverseGatewayConfig,
    private val onV2Packet: (ServerV2Packet) -> Unit,
    private val onLegacyMessage: (String, String) -> Unit,
    private val onState: (String, String?) -> Unit
) {
    private val httpClient = ServerV2HttpClient(endpointConfig)
    private var v2Socket: ServerV2SocketClient? = null
    private var legacyBridge: ReverseGatewayClient? = null

    private val relayAttempts = AtomicLong(0)
    private val relaySuccess = AtomicLong(0)
    private val relayFallback = AtomicLong(0)
    private val relayFailures = AtomicLong(0)
    private val httpAttempts = AtomicLong(0)
    private val httpSuccesses = AtomicLong(0)
    private val httpFailures = AtomicLong(0)
    private val reconnectRequests = AtomicLong(0)
    private val reconnectSuccesses = AtomicLong(0)

    @Volatile
    private var lastState = "stopped"
    @Volatile
    private var lastDetail: String? = "not-started"

    fun start() {
        stop()
        if (!endpointConfig.isRemoteReady()) {
            updateState("disabled", "endpoint config incomplete")
            return
        }
        v2Socket = ServerV2SocketClient(
            config = endpointConfig,
            onPacket = onV2Packet,
            onState = { event, detail ->
                if (event == "connected" || event == "reconnect") {
                    reconnectSuccesses.incrementAndGet()
                }
                updateState("v2-$event", detail)
            }
        ).also { it.start() }

        if (reverseConfig.enabled && reverseConfig.endpointUrl.isNotBlank() && reverseConfig.userId.isNotBlank() && reverseConfig.userKey.isNotBlank()) {
            legacyBridge = ReverseGatewayClient(
                reverseConfig,
                onMessage = { messageType, text, _ ->
                    onLegacyMessage(messageType, text)
                },
                onState = { event, detail ->
                    if (event == "connected") {
                        reconnectSuccesses.incrementAndGet()
                    }
                    if (v2Socket?.isConnected() != true) {
                        updateState("bridge-$event", detail)
                    }
                }
            ).also { it.start() }
        }
    }

    fun stop() {
        v2Socket?.stop()
        v2Socket = null
        legacyBridge?.stop()
        legacyBridge = null
        updateState("stopped", "transport runtime stopped")
    }

    fun requestReconnect() {
        reconnectRequests.incrementAndGet()
        v2Socket?.requestReconnect()
        legacyBridge?.requestReconnect()
        updateState("reconnect-requested", "transport reconnect requested")
    }

    fun isConnected(): Boolean = v2Socket?.isConnected() == true || legacyBridge?.isConnected() == true

    fun getHttpClient(): ServerV2HttpClient = httpClient

    fun getDiagnostics(): TransportRuntimeDiagnostics {
        val v2Diagnostics = v2Socket?.getDiagnostics()
        val legacyDiagnostics = legacyBridge?.getDiagnostics()
        val activeEndpoint = v2Diagnostics?.endpoint
            ?.ifBlank { null }
            ?: endpointConfig.endpointUrl.ifBlank { endpointConfig.dispatchUrl }
        return TransportRuntimeDiagnostics(
            configured = endpointConfig.isRemoteReady(),
            enabled = endpointConfig.isRemoteReady(),
            connected = isConnected(),
            state = lastState,
            stateDetail = lastDetail,
            endpoint = activeEndpoint,
            candidateState = legacyDiagnostics?.candidateState ?: if (v2Diagnostics != null) "socketio" else null,
            activeCandidate = legacyDiagnostics?.activeCandidate ?: v2Diagnostics?.activeTransport,
            candidateList = legacyDiagnostics?.candidateListText ?: v2Diagnostics?.endpoint,
            lastError = legacyDiagnostics?.lastFailureReason ?: v2Diagnostics?.lastDetail
        )
    }

    fun getMetrics(): TransportRuntimeMetrics = TransportRuntimeMetrics(
        relayAttempts = relayAttempts.get(),
        relaySuccess = relaySuccess.get(),
        relayFallback = relayFallback.get(),
        relayFailures = relayFailures.get(),
        httpAttempts = httpAttempts.get(),
        httpSuccesses = httpSuccesses.get(),
        httpFailures = httpFailures.get(),
        reconnectRequests = reconnectRequests.get(),
        reconnectSuccesses = reconnectSuccesses.get()
    )

    fun sendPacket(packet: ServerV2Packet): Boolean {
        if (v2Socket?.sendPacket(packet) == true) return true
        return sendPacketViaBridge(packet)
    }

    private fun sendPacketViaBridge(packet: ServerV2Packet): Boolean {
        val bridge = legacyBridge ?: return false
        if (!bridge.isConnected()) return false
        val senderId = ServerV2WireContract.normalizeNodeId(
            packet.byId ?: packet.from ?: endpointConfig.userId.ifBlank { endpointConfig.deviceId }
        ).ifBlank {
            ServerV2WireContract.resolve(endpointConfig).senderId()
        }
        val payload = ServerV2PacketCodec.toMap(packet)
        val messageType = packet.what.ifBlank { ServerV2PacketCodec.inferLegacyRelayType(packet) }
        val targets = packet.nodes
            .map { ServerV2WireContract.normalizeNodeId(it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (targets.isEmpty()) return false
        var delivered = false
        for (target in targets) {
            val sent = bridge.sendRelay(
                type = messageType,
                data = payload,
                target = target,
                route = "local",
                namespace = reverseConfig.namespace,
                from = senderId
            )
            delivered = delivered || sent
        }
        return delivered
    }

    fun sendClipboardViaSockets(
        envelope: ClipboardEnvelope,
        items: List<HubDispatchPayloadItem>,
        resolveTarget: (Map<String, Any>) -> String?,
        senderId: String
    ): List<HubDispatchPayloadItem> {
        val v2 = v2Socket
        val bridge = legacyBridge
        val identity = ServerV2WireContract.resolve(endpointConfig)
        val wireSenderId = ServerV2WireContract.normalizeNodeId(senderId).ifBlank { identity.senderId() }
        val pending = mutableListOf<HubDispatchPayloadItem>()
        for (item in items) {
            relayAttempts.incrementAndGet()
            val target = ServerV2WireContract.normalizeNodeId(resolveTarget(item.request))
            if (target.isNullOrBlank()) {
                pending.add(item)
                relayFallback.incrementAndGet()
                continue
            }

            val packet = ServerV2Packet(
                op = "act",
                what = "clipboard:update",
                payload = envelope.toMap(),
                nodes = listOf(target),
                byId = wireSenderId
            )

            val v2Sent = v2?.sendPacket(packet) == true
            if (v2Sent) {
                relaySuccess.incrementAndGet()
                continue
            }

            val bridgePayload = buildMap<String, Any?> {
                put("text", envelope.bestText())
                put("type", "clipboard")
                put("action", "clipboard")
                put("payload", envelope.toMap())
                put("data", envelope.toMap())
                put("packet", ServerV2PacketCodec.toMap(packet))
            }
            val bridgeSent = bridge?.sendRelay(
                type = ServerV2PacketCodec.inferLegacyRelayType(packet),
                data = bridgePayload,
                target = target,
                route = "local",
                namespace = reverseConfig.namespace,
                from = wireSenderId
            ) == true
            if (bridgeSent) {
                relaySuccess.incrementAndGet()
                relayFallback.incrementAndGet()
                continue
            }

            relayFailures.incrementAndGet()
            relayFallback.incrementAndGet()
            pending.add(item)
        }
        return pending
    }

    suspend fun dispatchClipboardRequests(items: List<HubDispatchPayloadItem>): Boolean {
        val requests = items.map { it.request }.filter { it.isNotEmpty() }
        if (requests.isEmpty()) return false
        httpAttempts.incrementAndGet()
        val result = httpClient.broadcastDispatch(requests)
        return if (result.ok) {
            httpSuccesses.incrementAndGet()
            true
        } else {
            httpFailures.incrementAndGet()
            false
        }
    }

    private fun updateState(state: String, detail: String?) {
        lastState = state
        lastDetail = detail
        onState(state, detail)
    }
}
