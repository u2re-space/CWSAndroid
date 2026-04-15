package space.u2re.cws.daemon

import space.u2re.cws.data.ClipboardEnvelope
import space.u2re.cws.network.EndpointCoreConfig
import space.u2re.cws.network.ReverseGatewayClient
import space.u2re.cws.network.ServerV2NetworkModule
import space.u2re.cws.network.ServerV2HttpClient
import space.u2re.cws.network.ServerV2Packet
import space.u2re.cws.network.ServerV2PacketCodec
import space.u2re.cws.reverse.ReverseGatewayConfig
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime transport coordinator for Android.
 *
 * This class sits above the v2 network module and the optional legacy reverse
 * bridge, applying feature flags, deciding when fallback is allowed, and
 * exposing one diagnostics/metrics surface to the daemon layer.
 */
class DaemonTransportRuntime(
    private val endpointConfig: EndpointCoreConfig,
    private val reverseConfig: ReverseGatewayConfig,
    private val onV2Packet: (ServerV2Packet) -> Unit,
    private val onLegacyMessage: (String, String) -> Unit,
    private val onState: (String, String?) -> Unit
) {
    // WHY: clipboard dispatch keeps both socket and HTTP paths available by
    // default because "frame queued" is not the same as "remote applied it".
    private val clipboardDualPathEnabled = listOf(
        System.getenv("CWS_ANDROID_CLIPBOARD_DUAL_PATH"),
        System.getProperty("cws.android.clipboardDualPath")
    ).firstNotNullOfOrNull { value ->
        val normalized = value?.trim()?.lowercase()
        when (normalized) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    } ?: true
    // WHY: hard cutover lets testing force the app onto the v2 transport even
    // when the legacy bridge would normally still act as a safety net.
    private val hardCutoverMode = listOf(
        System.getenv("CWS_ANDROID_HARD_CUTOVER"),
        System.getProperty("cws.android.hardCutover")
    ).any { value ->
        val normalized = value?.trim()?.lowercase()
        normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on"
    }
    // NOTE: this overrides all normal bridge-disabling heuristics.
    private val forceLegacyBridge = listOf(
        System.getenv("CWS_ANDROID_FORCE_LEGACY_BRIDGE"),
        System.getProperty("cws.android.forceLegacyBridge")
    ).any { value ->
        val normalized = value?.trim()?.lowercase()
        normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on"
    }
    // WHY: dual ingest is normally suppressed to avoid loops/duplicates when
    // WS and the legacy bridge both deliver the same clipboard/dispatch event.
    private val allowLegacyInboundWhenWsConnected = listOf(
        System.getenv("CWS_ANDROID_ALLOW_LEGACY_INBOUND_WITH_WS"),
        System.getProperty("cws.android.allowLegacyInboundWithWs")
    ).any { value ->
        val normalized = value?.trim()?.lowercase()
        normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on"
    }
    private val allowLegacySendFallback = listOf(
        System.getenv("CWS_ANDROID_ALLOW_LEGACY_SEND_FALLBACK"),
        System.getProperty("cws.android.allowLegacySendFallback")
    ).any { value ->
        val normalized = value?.trim()?.lowercase()
        normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on"
    }

    private val networkModule = ServerV2NetworkModule(
        config = endpointConfig,
        onPacket = onV2Packet,
        onState = { event, detail ->
            if (event == "connected" || event == "reconnect") {
                reconnectSuccesses.incrementAndGet()
            }
            updateState("v2-$event", detail)
        }
    )
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

    /**
     * Start the v2 transport and, if still allowed, the legacy bridge sidecar.
     *
     * AI-READ: this method defines the active transport topology for the app.
     */
    fun start() {
        stop()
        if (!networkModule.start()) {
            updateState("disabled", "endpoint config incomplete")
            return
        }

        if (
            reverseConfig.enabled &&
            reverseConfig.endpointUrl.isNotBlank() &&
            reverseConfig.userId.isNotBlank() &&
            reverseConfig.userKey.isNotBlank() &&
            shouldEnableLegacyBridge(reverseConfig.endpointUrl)
        ) {
            legacyBridge = ReverseGatewayClient(
                reverseConfig,
                onMessage = { messageType, text, _ ->
                    if (networkModule.isConnected() && !allowLegacyInboundWhenWsConnected) {
                        // Avoid dual-ingest loops (WS + legacy bridge) for clipboard/dispatch packets.
                        return@ReverseGatewayClient
                    }
                    onLegacyMessage(messageType, text)
                },
                onState = { event, detail ->
                    if (event == "connected") {
                        reconnectSuccesses.incrementAndGet()
                    }
                    if (!networkModule.isConnected()) {
                        updateState("bridge-$event", detail)
                    }
                }
            ).also { it.start() }
        }
    }

    /** Decide whether the legacy bridge should be enabled for the resolved endpoint. */
    private fun shouldEnableLegacyBridge(endpointUrl: String): Boolean {
        if (forceLegacyBridge) return true
        val endpoint = endpointUrl.trim()
        if (endpoint.isBlank()) return true
        return try {
            val uri = URI(endpoint)
            val host = uri.host?.trim().orEmpty().lowercase()
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("wss", ignoreCase = true) -> 443
                else -> 80
            }
            val path = (uri.path ?: "").trim().lowercase()
            val isRootLike = path.isBlank() || path == "/" || path == "/api"
            val isKnownLoopGateway = host == "192.168.0.200" && (port == 8443 || port == 443) && isRootLike
            if (isKnownLoopGateway) {
                updateState("bridge-disabled", "legacy bridge disabled for gateway root endpoint")
                false
            } else {
                true
            }
        } catch (_: Exception) {
            true
        }
    }

    /** Stop both transport paths and publish a stopped runtime state. */
    fun stop() {
        networkModule.stop()
        legacyBridge?.stop()
        legacyBridge = null
        updateState("stopped", "transport runtime stopped")
    }

    /** Ask both transport paths to reconnect and record that request in metrics/state. */
    fun requestReconnect() {
        reconnectRequests.incrementAndGet()
        networkModule.requestReconnect()
        legacyBridge?.requestReconnect()
        updateState("reconnect-requested", "transport reconnect requested")
    }

    fun isConnected(): Boolean = networkModule.isConnected() || legacyBridge?.isConnected() == true

    /** Expose the HTTP client that shares endpoint config with the live transport runtime. */
    fun getHttpClient(): ServerV2HttpClient = networkModule.getHttpClient()

    /** Merge v2 and legacy-bridge diagnostics into the daemon-facing snapshot shape. */
    fun getDiagnostics(): TransportRuntimeDiagnostics {
        val v2Diagnostics = networkModule.getSocketDiagnostics()
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
            candidateState = v2Diagnostics?.candidateState ?: legacyDiagnostics?.candidateState ?: if (v2Diagnostics != null) "1/1" else null,
            activeCandidate = v2Diagnostics?.activeCandidate ?: legacyDiagnostics?.activeCandidate ?: v2Diagnostics?.activeTransport,
            candidateList = v2Diagnostics?.candidateList ?: legacyDiagnostics?.candidateListText ?: v2Diagnostics?.endpoint,
            lastError = v2Diagnostics?.lastDetail ?: legacyDiagnostics?.lastFailureReason
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

    /** Send one packet through v2 first, falling back to the legacy bridge only when policy allows it. */
    fun sendPacket(packet: ServerV2Packet): Boolean {
        if (networkModule.sendPacket(packet)) return true
        if (!shouldUseLegacyFallback()) return false
        return sendPacketViaBridge(packet)
    }

    /**
     * Decide whether failed v2 sends may fall back to the legacy bridge.
     *
     * NOTE: hard-cutover mode intentionally disables this unless explicitly
     * overridden for debugging or staged rollouts.
     */
    private fun shouldUseLegacyFallback(): Boolean {
        if (forceLegacyBridge) return true
        if (hardCutoverMode && !allowLegacySendFallback) return false
        if (networkModule.isConnected() && !allowLegacySendFallback) return false
        return true
    }

    /** Convert a v2 packet into the bridge relay shape and send it target-by-target. */
    private fun sendPacketViaBridge(packet: ServerV2Packet): Boolean {
        val bridge = legacyBridge ?: return false
        if (!bridge.isConnected()) return false
        val senderId = networkModule.normalizeNodeId(
            packet.byId ?: packet.from ?: endpointConfig.userId.ifBlank { endpointConfig.deviceId }
        ).ifBlank {
            networkModule.resolveIdentity().senderId()
        }
        val payload = ServerV2PacketCodec.toMap(packet)
        val messageType = packet.what.ifBlank { ServerV2PacketCodec.inferLegacyRelayType(packet) }
        val targets = packet.nodes
            .map { networkModule.normalizeNodeId(it) }
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

    /**
     * Try clipboard delivery over sockets first, optionally keeping HTTP relay
     * items pending when dual-path delivery is enabled.
     */
    fun sendClipboardViaSockets(
        envelope: ClipboardEnvelope,
        items: List<HubDispatchPayloadItem>,
        resolveTarget: (Map<String, Any>) -> String?,
        senderId: String
    ): List<HubDispatchPayloadItem> {
        val identity = networkModule.resolveIdentity()
        val wireSenderId = networkModule.normalizeNodeId(senderId).ifBlank { identity.senderId() }
        val pending = mutableListOf<HubDispatchPayloadItem>()
        for (item in items) {
            relayAttempts.incrementAndGet()
            val target = networkModule.normalizeNodeId(resolveTarget(item.request))
            if (target.isNullOrBlank()) {
                pending.add(item)
                relayFailures.incrementAndGet()
                continue
            }

            val packet = ServerV2Packet(
                op = "act",
                what = "clipboard:update",
                type = "clipboard:update",
                purpose = "clipboard",
                protocol = "socket.io",
                payload = envelope.toMap(),
                nodes = listOf(target),
                destinations = listOf(target),
                byId = wireSenderId
            )

            val v2Sent = networkModule.sendPacket(packet)
            if (v2Sent) {
                relaySuccess.incrementAndGet()
                // `send()` means frame is queued, not that target applied it.
                // Keep HTTP dispatch as reliability path for cross-host clipboard delivery.
                if (clipboardDualPathEnabled) {
                    pending.add(item)
                }
                continue
            }

            if (!shouldUseLegacyFallback()) {
                relayFailures.incrementAndGet()
                pending.add(item)
                continue
            }

            val bridge = legacyBridge
            if (bridge == null || !bridge.isConnected()) {
                relayFailures.incrementAndGet()
                pending.add(item)
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

    /** Send any remaining clipboard relay items through the HTTP broadcast path. */
    suspend fun dispatchClipboardRequests(items: List<HubDispatchPayloadItem>): Boolean {
        val requests = items.map { it.request }.filter { it.isNotEmpty() }
        if (requests.isEmpty()) return false
        httpAttempts.incrementAndGet()
        val result = networkModule.getHttpClient().broadcastDispatch(requests)
        return if (result.ok) {
            httpSuccesses.incrementAndGet()
            true
        } else {
            httpFailures.incrementAndGet()
            false
        }
    }

    /** Persist and forward the latest runtime state for daemon/UI diagnostics. */
    private fun updateState(state: String, detail: String?) {
        lastState = state
        lastDetail = detail
        onState(state, detail)
    }
}
