package space.u2re.cws.daemon

import android.app.Activity
import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import space.u2re.cws.network.EndpointIdentity
import space.u2re.cws.network.ServerV2Packet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

private const val ANDROID_COORDINATOR_DEFAULT_TIMEOUT_MS = 6_000L
private const val ANDROID_COORDINATOR_STATE_POLL_MS = 800L

data class AndroidNetworkCoordinatorState(
    val connected: Boolean,
    val state: String,
    val detail: String?,
    val remoteHost: String,
    val stateUpdatedAtMs: Long
)

interface AndroidNetworkCoordinatorContract {
    fun start(application: Application, activity: Activity? = null)
    fun stop()
    fun isConnected(): Boolean
    fun getRemoteHost(): String
    fun state(): AndroidNetworkCoordinatorState
    fun onConnectionChange(handler: (Boolean) -> Unit): () -> Unit
    fun onStateChange(handler: (String, String?) -> Unit): () -> Unit
    fun sendCoordinatorAct(what: String, payload: Any?, nodes: List<String>? = null): Boolean
    suspend fun sendCoordinatorAsk(
        what: String,
        payload: Any?,
        nodes: List<String>? = null,
        timeoutMs: Long = ANDROID_COORDINATOR_DEFAULT_TIMEOUT_MS
    ): Any?
    suspend fun sendCoordinatorRequest(
        what: String,
        payload: Any?,
        nodes: List<String>? = null,
        timeoutMs: Long = ANDROID_COORDINATOR_DEFAULT_TIMEOUT_MS
    ): Any?
    fun requestClipboardHistory(target: String): Boolean
    suspend fun requestClipboardRead(target: String): Any?
    fun sendClipboardUpdate(text: String, target: String? = null): Boolean
}

object AndroidNetworkCoordinator : AndroidNetworkCoordinatorContract {
    private var application: Application? = null
    private var activityRef: Activity? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val responseWaiters = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
    private val connectionHandlers = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val stateHandlers = CopyOnWriteArraySet<(String, String?) -> Unit>()
    private var watchJob: Job? = null
    private var lastConnected = false
    private var lastState: String? = null
    private var lastDetail: String? = null
    private var lastPacketObserverDaemon: Daemon? = null
    private var packetObserverStop: (() -> Unit)? = null

    private fun launchWatchLoop(): Unit {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                emitConnectionStateDelta()
                delay(ANDROID_COORDINATOR_STATE_POLL_MS)
            }
        }
    }

    private fun stopWatchLoop() {
        if (connectionHandlers.isEmpty() && stateHandlers.isEmpty()) {
            watchJob?.cancel()
            watchJob = null
            lastConnected = false
            lastState = null
            lastDetail = null
        }
    }

    private fun currentSnapshot(): AndroidNetworkCoordinatorState {
        val daemon = DaemonController.current()
        val snapshot = daemon?.getConnectionSnapshot() ?: Daemon.DaemonConnectionSnapshot.stopped()
        return AndroidNetworkCoordinatorState(
            connected = snapshot.reverseGatewayConnected,
            state = snapshot.reverseGatewayState,
            detail = snapshot.reverseGatewayStateDetail,
            remoteHost = snapshot.transportEndpoint,
            stateUpdatedAtMs = snapshot.reverseGatewayStateUpdatedAtMs
        )
    }

    private fun emitConnectionStateDelta() {
        val current = currentSnapshot()
        if (current.connected != lastConnected) {
            lastConnected = current.connected
            connectionHandlers.forEach { handler ->
                handler(current.connected)
            }
        }
        if (current.state != lastState || current.detail != lastDetail) {
            lastState = current.state
            lastDetail = current.detail
            stateHandlers.forEach { handler ->
                handler(current.state, current.detail)
            }
        }
    }

    private fun attachPacketObserver(daemon: Daemon) {
        if (lastPacketObserverDaemon === daemon) return
        packetObserverStop?.invoke()
        packetObserverStop = daemon.observeServerV2Packets(::handleInboundPacket)
        lastPacketObserverDaemon = daemon
    }

    private fun inferPurpose(what: String): String {
        val lowered = what.lowercase(Locale.ROOT)
        return when {
            lowered.startsWith("clipboard:") -> "clipboard"
            lowered.startsWith("sms:") -> "sms"
            lowered.startsWith("notification:") || lowered.startsWith("notifications:") -> "notification"
            lowered.startsWith("contact:") || lowered.startsWith("contacts:") -> "contact"
            else -> "generic"
        }
    }

    private fun normalizeNodes(nodes: List<String>?): List<String> {
        return nodes.orEmpty()
            .mapNotNull { raw ->
                val normalized = EndpointIdentity.bestRouteTarget(raw).ifBlank { raw.trim() }
                normalized.ifBlank { null }
            }
            .filter { it.isNotBlank() }
    }

    private fun daemonOrThrow(): Daemon {
        val app = application ?: throw IllegalStateException("AndroidNetworkCoordinator.start(...) is required before sending packets")
        val daemon = DaemonController.current() ?: DaemonController.start(app, activityRef)
        attachPacketObserver(daemon)
        return daemon
    }

    private fun parseCoordinatorError(error: Any?): String {
        val mapError = error as? Map<*, *>
        return when {
            mapError == null -> error?.toString().orEmpty().ifBlank { "remote operation error" }
            mapError["message"] != null -> mapError["message"].toString()
            mapError["error"] != null -> mapError["error"].toString()
            mapError["code"] != null -> mapError["code"].toString()
            else -> mapError.toString()
        }
    }

    private fun normalizeCoordinatorErrorResponse(reason: String?): Any {
        return mapOf(
            "ok" to false,
            "error" to (reason?.ifBlank { "remote operation error" } ?: "remote operation error")
        )
    }

    private fun handleInboundPacket(packet: ServerV2Packet) {
        val uuid = packet.uuid?.trim().orEmpty()
        if (uuid.isBlank()) return
        val waiter = responseWaiters.remove(uuid) ?: return
        val normalizedOp = packet.op.lowercase(Locale.ROOT)
        if (normalizedOp == "error") {
            val reason = parseCoordinatorError(packet.error)
            waiter.complete(normalizeCoordinatorErrorResponse(reason))
            return
        }
        if (normalizedOp == "result" || normalizedOp == "resolve" || normalizedOp == "response") {
            waiter.complete(packet.result ?: packet.results ?: packet.payload)
            return
        }
        waiter.complete(packet.result ?: packet.payload)
    }

    private suspend fun sendCoordinatorRequestInternal(
        op: String,
        what: String,
        payload: Any?,
        nodes: List<String>?,
        timeoutMs: Long
    ): Any? {
        val daemon = daemonOrThrow()
        val uuid = UUID.randomUUID().toString()
        val normalizedNodes = normalizeNodes(nodes)
        val deferred = CompletableDeferred<Any?>()
        responseWaiters[uuid] = deferred
        return try {
            val sent = daemon.sendPacket(
                ServerV2Packet(
                    op = op,
                    what = what,
                    type = what,
                    purpose = inferPurpose(what),
                    protocol = "socket",
                    payload = payload,
                    nodes = normalizedNodes,
                    destinations = normalizedNodes,
                    uuid = uuid
                )
            )
            if (!sent) {
                return normalizeCoordinatorErrorResponse("transport unavailable")
            }
            withTimeout(timeoutMs) {
                try {
                    deferred.await()
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    normalizeCoordinatorErrorResponse(parseCoordinatorError(error))
                }
            }
        } catch (error: TimeoutCancellationException) {
            normalizeCoordinatorErrorResponse("Timeout waiting for $what")
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            normalizeCoordinatorErrorResponse(parseCoordinatorError(error))
        } finally {
            responseWaiters.remove(uuid)
        }
    }

    override fun start(application: Application, activity: Activity?) {
        this.application = application
        this.activityRef = activity
        val daemon = daemonOrThrow()
        if (stateHandlers.isNotEmpty() || connectionHandlers.isNotEmpty()) {
            launchWatchLoop()
        }
        emitConnectionStateDelta()
        attachPacketObserver(daemon)
    }

    override fun stop() {
        watchJob?.cancel()
        watchJob = null
        connectionHandlers.clear()
        stateHandlers.clear()
        responseWaiters.values.forEach { waiter ->
            waiter.complete(normalizeCoordinatorErrorResponse("coordinator stopped"))
        }
        responseWaiters.clear()
        packetObserverStop?.invoke()
        packetObserverStop = null
        lastPacketObserverDaemon = null
        lastConnected = false
        lastState = null
        lastDetail = null
    }

    override fun isConnected(): Boolean {
        return currentSnapshot().connected
    }

    override fun getRemoteHost(): String {
        return currentSnapshot().remoteHost
    }

    override fun state(): AndroidNetworkCoordinatorState {
        return currentSnapshot()
    }

    override fun onConnectionChange(handler: (Boolean) -> Unit): () -> Unit {
        handler(currentSnapshot().connected)
        connectionHandlers.add(handler)
        launchWatchLoop()
        return {
            connectionHandlers.remove(handler)
            stopWatchLoop()
        }
    }

    override fun onStateChange(handler: (String, String?) -> Unit): () -> Unit {
        val snapshot = currentSnapshot()
        handler(snapshot.state, snapshot.detail)
        stateHandlers.add(handler)
        launchWatchLoop()
        return {
            stateHandlers.remove(handler)
            stopWatchLoop()
        }
    }

    override fun sendCoordinatorAct(what: String, payload: Any?, nodes: List<String>?): Boolean {
        return runCatching {
            val daemon = daemonOrThrow()
            val normalizedNodes = normalizeNodes(nodes)
            daemon.sendPacket(
                ServerV2Packet(
                    op = "act",
                    what = what,
                    type = what,
                    purpose = inferPurpose(what),
                    protocol = "socket",
                    payload = payload,
                    nodes = normalizedNodes,
                    destinations = normalizedNodes
                )
            )
        }.getOrElse { false }
    }

    override suspend fun sendCoordinatorAsk(
        what: String,
        payload: Any?,
        nodes: List<String>?,
        timeoutMs: Long
    ): Any? {
        return sendCoordinatorRequestInternal(
            op = "ask",
            what = what,
            payload = payload,
            nodes = nodes,
            timeoutMs = timeoutMs
        )
    }

    override suspend fun sendCoordinatorRequest(
        what: String,
        payload: Any?,
        nodes: List<String>?,
        timeoutMs: Long
    ): Any? {
        return sendCoordinatorRequestInternal(
            op = "act",
            what = what,
            payload = payload,
            nodes = nodes,
            timeoutMs = timeoutMs
        )
    }

    override fun requestClipboardHistory(target: String): Boolean {
        val normalizedTarget = EndpointIdentity.bestRouteTarget(target).ifBlank { target.trim() }
        if (normalizedTarget.isBlank()) return false
        return DaemonController.current()?.requestClipboardHistory(normalizedTarget) == true
    }

    override suspend fun requestClipboardRead(target: String): Any? {
        val normalizedTarget = EndpointIdentity.bestRouteTarget(target).ifBlank { target.trim() }
        return sendCoordinatorRequest(
            what = "clipboard:get",
            payload = mapOf("request" to "history"),
            nodes = normalizedTarget.takeIf { it.isNotBlank() }?.let { listOf(it) },
            timeoutMs = ANDROID_COORDINATOR_DEFAULT_TIMEOUT_MS
        )
    }

    override fun sendClipboardUpdate(text: String, target: String?): Boolean {
        val nodes = target?.let { candidate ->
            val normalized = EndpointIdentity.bestRouteTarget(candidate).ifBlank { candidate.trim() }
            normalized.ifBlank { null }?.let { listOf(it) }
        }
        return sendCoordinatorAct("clipboard:update", mapOf("text" to text), nodes)
    }
}
