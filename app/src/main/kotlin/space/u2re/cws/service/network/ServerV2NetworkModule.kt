package space.u2re.cws.network

data class ServerV2NetworkModuleDiagnostics(
    val ready: Boolean,
    val connected: Boolean,
    val endpoint: String,
    val transport: String,
    val state: String,
    val detail: String?
)

/**
 * Consolidated network core stack module for Kotlin runtime:
 * - endpoint identity/wire contract
 * - socket transport lifecycle
 * - HTTP transport facade
 */
class ServerV2NetworkModule(
    private val config: EndpointCoreConfig,
    private val onPacket: (ServerV2Packet) -> Unit,
    private val onState: (String, String?) -> Unit
) {
    private val httpClient = ServerV2HttpClient(config)
    private var socketClient: ServerV2SocketClient? = null

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

    fun stop() {
        socketClient?.stop()
        socketClient = null
    }

    fun requestReconnect() {
        socketClient?.requestReconnect()
    }

    fun isConnected(): Boolean = socketClient?.isConnected() == true

    fun sendPacket(packet: ServerV2Packet): Boolean = socketClient?.sendPacket(packet) == true

    fun getHttpClient(): ServerV2HttpClient = httpClient

    fun getSocketDiagnostics(): ServerV2SocketDiagnostics? = socketClient?.getDiagnostics()

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

    fun resolveIdentity(): ServerV2WireIdentity = ServerV2WireContract.resolve(config)

    fun normalizeNodeId(value: String?): String = ServerV2WireContract.normalizeNodeId(value)
}
