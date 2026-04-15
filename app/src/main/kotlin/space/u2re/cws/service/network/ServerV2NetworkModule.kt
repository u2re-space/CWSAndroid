package space.u2re.cws.network

/**
 * High-level diagnostics snapshot for the Android v2 network stack.
 *
 * AI-READ: this is the compact shape that daemon/UI layers consume when they
 * need to summarize socket readiness without depending on socket internals.
 */
data class ServerV2NetworkModuleDiagnostics(
    val ready: Boolean,
    val connected: Boolean,
    val endpoint: String,
    val transport: String,
    val state: String,
    val detail: String?
)

/**
 * Consolidated Android network core for CWSP v2.
 *
 * This facade keeps endpoint config, the live WebSocket client, and the HTTP
 * compatibility client together so daemon/runtime code can start, stop, and
 * diagnose transport behavior through one boundary.
 */
class ServerV2NetworkModule(
    private val config: EndpointCoreConfig,
    private val onPacket: (ServerV2Packet) -> Unit,
    private val onState: (String, String?) -> Unit
) {
    private val httpClient = ServerV2HttpClient(config)
    private var socketClient: ServerV2SocketClient? = null

    /**
     * Start the network stack if the endpoint config is complete enough to dial.
     *
     * WHY: daemon code should not need to know whether readiness depends on
     * endpoints, credentials, or socket-specific setup details.
     */
    fun start(): Boolean {
        stop()
        if (!config.isRemoteReady()) {
            onState("disabled", "server-v2 network config incomplete")
            return false
        }
        socketClient = ServerV2SocketClient(
            config = config,
            onPacket = onPacket,
            onState = onState
        ).also { it.start() }
        return true
    }

    /** Stop the live socket layer and reset the module back to an idle state. */
    fun stop() {
        socketClient?.stop()
        socketClient = null
    }

    /** Forward a reconnect request to the active socket client, if one exists. */
    fun requestReconnect() {
        socketClient?.requestReconnect()
    }

    fun isConnected(): Boolean = socketClient?.isConnected() == true

    fun sendPacket(packet: ServerV2Packet): Boolean = socketClient?.sendPacket(packet) == true

    /** Expose the shared HTTP fallback/compat client used for probe and relay calls. */
    fun getHttpClient(): ServerV2HttpClient = httpClient

    /** Return the current low-level socket diagnostics snapshot, if the socket layer is active. */
    fun getSocketDiagnostics(): ServerV2SocketDiagnostics? = socketClient?.getDiagnostics()

    /** Build a normalized transport summary for daemon state, UI, and debugging surfaces. */
    fun getDiagnostics(): ServerV2NetworkModuleDiagnostics {
        val socketDiagnostics = getSocketDiagnostics()
        return ServerV2NetworkModuleDiagnostics(
            ready = config.isRemoteReady(),
            connected = isConnected(),
            endpoint = socketDiagnostics?.endpoint?.ifBlank { null } ?: config.endpointUrl.ifBlank { config.dispatchUrl },
            transport = socketDiagnostics?.activeTransport ?: "socketio",
            state = socketDiagnostics?.lastState ?: "stopped",
            detail = socketDiagnostics?.lastDetail
        )
    }

    /** Resolve the current wire identity from endpoint config, including aliases and headers/query metadata. */
    fun resolveIdentity(): ServerV2WireIdentity = ServerV2WireContract.resolve(config)

    /** Normalize routing/node ids the same way the socket/http layers do. */
    fun normalizeNodeId(value: String?): String = ServerV2WireContract.normalizeNodeId(value)
}
