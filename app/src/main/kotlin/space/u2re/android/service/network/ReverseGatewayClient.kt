package space.u2re.cws.network

import android.util.Log
import space.u2re.cws.reverse.ReverseGatewayConfig
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
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import com.google.gson.Gson
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.cert.CertificateFactory
import java.security.KeyStore
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.net.ssl.TrustManagerFactory

class ReverseGatewayClient(
    private val config: ReverseGatewayConfig,
    private val onMessage: (String, String, String?) -> Unit = { _, _, _ -> },
    private val onState: (String, String?) -> Unit = { _, _ -> }
) {
    companion object {
        private const val DEFAULT_REVERSE_ROLES = "endpoint,peer,node,app"
        private const val LOG_TAG = "ReverseGateway"
        private val gson = Gson()
        private const val STATE_LOG_COOLDOWN_MS = 3_000L
        private const val FAILURE_LOG_COOLDOWN_MS = 12_000L
        private const val PARSE_FAIL_LOG_COOLDOWN_MS = 10_000L

        private val CLIENT_REVERSE_ROLE_TOKENS = setOf(
            "client-reverse",
            "reverse-client",
            "client-downstream",
            "server-downstream",
            "server-reverse",
            "reverse-server"
        )

        private val CLIENT_FORWARD_ROLE_TOKENS = setOf(
            "client-forward",
            "forward-client",
            "client-bridge",
            "server-bridge",
            "server-forward",
            "forward-server"
        )
    }

    data class Diagnostics(
        val candidateState: String,
        val activeCandidate: String,
        val candidateCount: Int,
        val activeScheme: String,
        val candidateListText: String,
        val lastFailureReason: String?
    )

    private data class WebsocketCandidate(
        val endpoint: String,
        val scheme: String
    )

    private val archetypeCandidates = buildArchetypeCandidates(config.roles)
    private var archetypeAttempt = 0
    private val configuredKeepAliveMs = config.keepAliveIntervalMs.coerceAtLeast(1_000L)
    private val configuredReconnectMs = config.reconnectDelayMs.coerceAtLeast(500L)

    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = buildOkHttpClient()
    private fun buildOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(configuredKeepAliveMs, TimeUnit.MILLISECONDS)
        if (config.allowInsecureTls) {
            val trustAll = trustAllManager()
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustAll), SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustAll)
            builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
            return builder.build()
        }

        val trustedCa = loadReverseTrustedCa(config.trustedCa)
        trustedCa?.let { caManager ->
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(caManager), SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, caManager)
        }
        return builder.build()
    }

    private fun loadReverseTrustedCa(raw: String): X509TrustManager? {
        val material = resolveTrustedCaMaterial(raw) ?: return null
        val certificates = extractCertificates(material)
        if (certificates.isEmpty()) return null
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
        }
        val keyManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        certificates.forEachIndexed { index, certificate ->
            keyStore.setCertificateEntry("reverse-root-ca-${index + 1}", certificate)
        }
        keyManagerFactory.init(keyStore)
        return keyManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }

    private fun resolveTrustedCaMaterial(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val pathOrContent = when {
            trimmed.startsWith("fs:", ignoreCase = true) -> trimmed.removePrefix("fs:").trim()
            trimmed.startsWith("file:", ignoreCase = true) -> trimmed.removePrefix("file:").trim()
            else -> trimmed
        }
        val file = File(pathOrContent)
        if (file.exists() && file.isFile) {
            return try {
                file.readText()
            } catch (_: Exception) {
                pathOrContent
            }
        }
        return pathOrContent
    }

    private fun extractCertificates(material: String): List<X509Certificate> {
        val content = material.trim()
        if (content.isBlank()) return emptyList()

        val pemBlocks = CERTIFICATE_PEM_RE.findAll(content).map { it.value }.toList()
        val sources = if (pemBlocks.isNotEmpty()) pemBlocks else listOf(content)
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val out = mutableListOf<X509Certificate>()
        for (source in sources) {
            val normalized = source.trim()
            if (normalized.isBlank()) continue
            val parsed = runCatching {
                certificateFactory.generateCertificate(ByteArrayInputStream(normalized.toByteArray(Charsets.UTF_8))) as X509Certificate
            }.getOrNull()
            if (parsed != null) out.add(parsed)
        }
        return out
    }

    private val CERTIFICATE_PEM_RE = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----", RegexOption.IGNORE_CASE)

    private fun trustAllManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }


    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = configuredReconnectMs
    private var pendingReconnectRequest = false
    private var immediateReconnect = true
    private var socketConnected = false
    private var running = false
    private var websocketCandidates: List<WebsocketCandidate> = emptyList()
    private var websocketCandidateIndex = 0
    private var activeCandidate: WebsocketCandidate? = null
    private var activeConnectScheme = "wss"
    private var lastFailureReason: String? = null
    private val lastStateLogAtByKey = HashMap<String, Long>()
    private var lastFailureLogSignature: String? = null
    private var lastFailureLogAtMs = 0L
    private var lastFailureLogCount = 0

    private fun normalizeRoleToken(value: String): String = value.trim().lowercase()

    private fun parseRoleTokens(value: String): List<String> = value
        .split(",")
        .map { normalizeRoleToken(it) }
        .filter { it.isNotEmpty() }

    private fun buildArchetypeCandidates(rolesRaw: String): List<String> {
        val roles = parseRoleTokens(rolesRaw.ifBlank { "" })
        val hasReverse = roles.any(CLIENT_REVERSE_ROLE_TOKENS::contains)
        val hasForward = roles.any(CLIENT_FORWARD_ROLE_TOKENS::contains)
        return when {
            hasReverse -> listOf("client-reverse")
            hasForward -> listOf("client-forward")
            else -> listOf("client-reverse")
        }
    }

    private fun normalizeRolesForWire(rolesRaw: String): String {
        val input = parseRoleTokens(rolesRaw.ifBlank { DEFAULT_REVERSE_ROLES }).toMutableSet()
        val out = LinkedHashSet<String>()
        input.forEach { token ->
            when (token) {
                in CLIENT_REVERSE_ROLE_TOKENS -> {
                    out.add("client-reverse")
                    out.add("reverse-client")
                    out.add("client")
                }
                in CLIENT_FORWARD_ROLE_TOKENS -> {
                    out.add("client-forward")
                    out.add("forward-client")
                    out.add("client")
                }
                "server-reverse", "reverse-server" -> {
                    out.add("server-reverse")
                    out.add("reverse-server")
                    out.add("server")
                }
                "server-forward", "forward-server" -> {
                    out.add("server-forward")
                    out.add("forward-server")
                    out.add("server")
                }
                else -> out.add(token)
            }
        }
        if (out.none { it in setOf("client", "server", "endpoint", "peer", "node", "app", "hub") }) {
            out.add("endpoint")
            out.add("peer")
            out.add("node")
            out.add("app")
        }
        return out.joinToString(",")
    }

    private fun getCurrentArchetype(): String = archetypeCandidates[archetypeAttempt]

    private fun nextArchetypeCandidate(): Boolean {
        val next = archetypeAttempt + 1
        if (next >= archetypeCandidates.size) return false
        archetypeAttempt = next
        return true
    }

    private fun isArchetypeReject(code: Int, reason: String?): Boolean {
        if (code == 4005) return true
        val normalized = reason?.lowercase() ?: return false
        return normalized.contains("archetype")
    }

    fun send(message: String) {
        socket?.send(message)
    }

    fun isConnected(): Boolean = socket != null && socketConnected

    fun sendRelay(
        type: String,
        data: Any?,
        target: String? = null,
        route: String? = null,
        namespace: String? = null,
        from: String? = null
    ): Boolean {
        if (!socketConnected) return false
        val activeSocket = socket ?: return false
        return try {
            val payload = mutableMapOf<String, Any?>(
                "type" to type,
                "target" to (target ?: "broadcast"),
                "route" to (route ?: "local"),
                "namespace" to (namespace ?: config.namespace),
                "data" to data,
                "ts" to System.currentTimeMillis()
            )
            if (!from.isNullOrBlank()) {
                payload["from"] = from
            }
            val encoded = ReverseRelayCodec.encodeForServer(
                deviceId = config.deviceId,
                payload = payload,
                aesMasterKey = config.masterKey.ifBlank { null },
                signingPrivateKeyPem = config.signingPrivateKeyPem.ifBlank { null }
            )
            activeSocket.send(encoded)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun requestReconnect(immediate: Boolean = true) {
        if (!running) return
        pendingReconnectRequest = true
        immediateReconnect = immediate
        websocketCandidateIndex = 0
        lastFailureReason = null
        onState("reconnect-requested", buildWsStatusLine("reconnect-requested", phase = if (immediate) "manual" else "queued"))
        reconnectDelayMs = configuredReconnectMs
        reconnectJob?.cancel()
        activeCandidate = null
        try {
            socket?.close(1000, "manual-reconnect")
        } catch (_: Exception) {
            // no-op
        }
        if (immediate) {
            connect()
        } else {
            if (socket == null) {
                scheduleReconnect(immediate = false)
            }
        }
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
        websocketCandidates = emptyList()
        websocketCandidateIndex = 0
        activeCandidate = null
        lastFailureReason = null
        connect();
    }

    fun stop() {
        running = false;
        heartbeatJob?.cancel();
        reconnectJob?.cancel();
        heartbeatJob = null;
        reconnectJob = null;
        pendingReconnectRequest = false;
        socketConnected = false
        websocketCandidateIndex = 0
        websocketCandidates = emptyList()
        activeCandidate = null
        lastFailureReason = null
        socket?.close(1000, "stopped");
        socket = null;
    }

    fun getDiagnostics(): Diagnostics {
        val count = websocketCandidates.size
        val active = activeCandidate
        val index = when {
            count <= 0 -> 0
            websocketCandidateIndex < 0 -> 0
            websocketCandidateIndex >= count -> count - 1
            else -> websocketCandidateIndex
        }
        return Diagnostics(
            candidateState = if (count == 0) "0/0" else "${index + 1}/$count",
            activeCandidate = active?.let { describeCandidateLabel(it) } ?: "not initialized",
            candidateCount = count,
            activeScheme = active?.scheme ?: activeConnectScheme,
            candidateListText = if (count == 0) "not initialized" else websocketCandidates.joinToString(", ") { describeCandidateLabel(it) },
            lastFailureReason = lastFailureReason
        )
    }

    private fun connect() {
        if (!running) return;
        if (config.endpointUrl.isBlank()) return;
        val trimmedEndpoint = config.endpointUrl.trim().trimStart('/')
        if (trimmedEndpoint.isBlank()) return
        if (websocketCandidates.isEmpty()) {
            websocketCandidates = resolveWebsocketCandidates(trimmedEndpoint)
        }
        if (websocketCandidates.isEmpty()) {
            websocketCandidates = listOf(WebsocketCandidate(trimmedEndpoint, "wss"), WebsocketCandidate(trimmedEndpoint, "ws"))
        }
        if (websocketCandidateIndex < 0 || websocketCandidateIndex >= websocketCandidates.size) {
            websocketCandidateIndex = 0
        }
        val active = websocketCandidates[websocketCandidateIndex]
        activeCandidate = active
        activeConnectScheme = active.scheme
        if (shouldLogLifecycleEvent("connecting", describeCandidateLabel(active), active.scheme)) {
            onState(
                "connecting",
                buildWsStatusLine(
                    "connecting",
                    candidate = active,
                    index = websocketCandidateIndex,
                    phase = if (pendingReconnectRequest) "manual" else "normal"
                )
            )
        }
        if (pendingReconnectRequest && socket == null) {
            pendingReconnectRequest = false
        }

        val request = buildRequest(active) ?: run {
            Log.e(LOG_TAG, "Reverse gateway URL is invalid");
            return;
        };
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketConnected = true
                val helloClientId = (config.userId.ifBlank { config.deviceId }).ifBlank { "android-client" }
                lastFailureReason = null
                if (shouldLogLifecycleEvent("connected", "phase=ready:${describeCandidateLabel(active)}", active.scheme)) {
                    onState("connected", buildWsStatusLine("connected", candidate = active, index = websocketCandidateIndex, phase = "ready"))
                }
                pendingReconnectRequest = false
                reconnectDelayMs = configuredReconnectMs
                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (isActive) {
                        delay(configuredKeepAliveMs)
                        webSocket.send(
                            """{"type":"hello","deviceId":"${escapeJson(config.deviceId)}","clientId":"${escapeJson(helloClientId)}","namespace":"${escapeJson(config.namespace)}","roles":"${escapeJson(normalizeRolesForWire(config.roles))}","archetype":"${escapeJson(getCurrentArchetype())}","ts":${System.currentTimeMillis()}}"""
                        )
                    }
                }
                webSocket.send(
                    """{"type":"hello","deviceId":"${escapeJson(config.deviceId)}","clientId":"${escapeJson(helloClientId)}","namespace":"${escapeJson(config.namespace)}","roles":"${escapeJson(normalizeRolesForWire(config.roles))}","archetype":"${escapeJson(getCurrentArchetype())}"}"""
                )
                if (shouldLogLifecycleEvent("connected", describeCandidateLabel(active), active.scheme)) {
                    Log.i(LOG_TAG, "Reverse gateway connected");
                }
                onState("connected", buildWsStatusLine("connected", candidate = active, index = websocketCandidateIndex, phase = "hello"))
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
                    logParseFailure(e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (t is CancellationException) return
                socketConnected = false
                if (socket === webSocket) {
                    socket = null
                }
                val reason = describeFailureCause(t, response)
                lastFailureReason = reason
                maybeLogFailure(active, reason, t)
                if (shouldLogLifecycleEvent("failure", reason, active.scheme)) {
                    onState("failure", buildWsStatusLine("failure", candidate = active, index = websocketCandidateIndex, phase = "transport", message = reason))
                } else {
                    onState("failure", buildWsStatusLine("failure", candidate = active, index = websocketCandidateIndex, phase = "transport", message = "queued"))
                }
                webSocket.close(1000, "failure")
                if (tryAdvanceCandidate("failure")) {
                    return
                }
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (shouldLogLifecycleEvent("closed", "code=$code reason=$reason", active.scheme)) {
                    Log.i(LOG_TAG, "Reverse gateway closed: code=$code reason=$reason")
                }
                socketConnected = false
                if (socket === webSocket) {
                    socket = null
                }
                if (reason.isNotBlank()) {
                    lastFailureReason = "closed: code=$code reason=$reason"
                }
                if (shouldLogLifecycleEvent("disconnected", "code=$code reason=$reason", active.scheme)) {
                    onState("disconnected", buildWsStatusLine("disconnected", candidate = active, index = websocketCandidateIndex, phase = "closed", message = "code=$code reason=$reason"))
                } else {
                    onState("disconnected", buildWsStatusLine("disconnected", candidate = active, index = websocketCandidateIndex, phase = "closed", message = "queued"))
                }
                if (pendingReconnectRequest && reason == "manual-reconnect") {
                    if (immediateReconnect) {
                        connect()
                    } else {
                        scheduleReconnect(immediate = false)
                    }
                    return
                }
                if (running && reason.lowercase() != "manual-reconnect" && tryAdvanceCandidate("close")) {
                    return
                }
                if (running && isArchetypeReject(code, reason) && nextArchetypeCandidate()) {
                    if (shouldLogLifecycleEvent("fallback-archetype", getCurrentArchetype(), active.scheme)) {
                        Log.i(LOG_TAG, "Trying fallback archetype ${getCurrentArchetype()}")
                    }
                    websocketCandidateIndex = 0
                    activeCandidate = null
                    connect()
                    return
                }
                if (running) scheduleReconnect()
            }
        })
    }

    private fun tryAdvanceCandidate(source: String): Boolean {
        if (websocketCandidates.size <= 1) return false
        websocketCandidateIndex = (websocketCandidateIndex + 1) % websocketCandidates.size
        val nextCandidate = websocketCandidates[websocketCandidateIndex]
        if (shouldLogLifecycleEvent("connecting", "rotate:$source", nextCandidate.scheme)) {
            onState(
                "connecting",
                buildWsStatusLine(
                    "connecting",
                    candidate = nextCandidate,
                    index = websocketCandidateIndex,
                    phase = "rotate",
                    message = source
                )
            )
        }
        connect()
        return true
    }

    private fun shouldLogLifecycleEvent(event: String, detail: String, scheme: String): Boolean {
        val key = "$event|$scheme|$detail"
        val now = System.currentTimeMillis()
        val last = lastStateLogAtByKey[key] ?: 0L
        if (now - last < STATE_LOG_COOLDOWN_MS) return false
        lastStateLogAtByKey[key] = now
        return true
    }

    private fun maybeLogFailure(candidate: WebsocketCandidate?, reason: String, cause: Throwable?) {
        val signature = "${describeCandidateLabel(candidate ?: WebsocketCandidate("unknown", activeConnectScheme))}|$reason"
        val now = System.currentTimeMillis()
        if (signature == lastFailureLogSignature && now - lastFailureLogAtMs < FAILURE_LOG_COOLDOWN_MS) {
            lastFailureLogCount++
            return
        }
        if (lastFailureLogSignature != null && lastFailureLogCount > 1) {
            Log.w(LOG_TAG, "Reverse gateway failure repeated x${lastFailureLogCount}: $lastFailureLogSignature")
        }
        lastFailureLogSignature = signature
        lastFailureLogAtMs = now
        lastFailureLogCount = 1
        Log.w(LOG_TAG, "Reverse gateway failed: $reason", cause)
    }

    private fun logParseFailure(cause: Throwable) {
        val now = System.currentTimeMillis()
        val key = "parseFailure"
        val last = lastStateLogAtByKey[key] ?: 0L
        if (now - last < PARSE_FAIL_LOG_COOLDOWN_MS) return
        lastStateLogAtByKey[key] = now
        Log.w(LOG_TAG, "Reverse gateway message parse failure", cause)
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        if (!running) return
        heartbeatJob?.cancel()
        heartbeatJob = null
        val delayMs = if (immediate) 250 else reconnectDelayMs.coerceAtLeast(1_000).coerceAtMost(30_000)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            connect()
        }
    }

    private fun buildWsStatusLine(
        event: String,
        candidate: WebsocketCandidate? = activeCandidate,
        index: Int = websocketCandidateIndex,
        phase: String? = null,
        message: String? = null
    ): String {
        val count = websocketCandidates.size
        val safeIndex = if (count <= 0) 0 else (index % count).coerceAtLeast(0) + 1
        val safeCandidate = candidate ?: activeCandidate
        val candidateLabel = safeCandidate?.let { describeCandidateLabel(it) } ?: "n/a"
        val safeScheme = safeCandidate?.scheme ?: activeConnectScheme.ifBlank { "wss" }
        return buildString {
            append("event=$event")
            append(" candidate=$safeIndex/$count")
            append(" scheme=$safeScheme")
            append(" url=$candidateLabel")
            if (!phase.isNullOrBlank()) append(" phase=$phase")
            if (!message.isNullOrBlank()) append(" reason=${message.trim()}")
        }
    }

    private fun resolveWebsocketCandidates(trimmedEndpoint: String): List<WebsocketCandidate> {
        val entries = trimmedEndpoint
            .split(Regex("[,;\n]"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val candidates = mutableListOf<WebsocketCandidate>()
        val seen = HashSet<String>()

        fun addCandidate(endpoint: String, scheme: String) {
            val normalizedEndpoint = endpoint.trim()
            val normalizedScheme = scheme.lowercase()
            if (normalizedEndpoint.isBlank()) return
            val key = "$normalizedScheme|$normalizedEndpoint"
            if (seen.add(key)) {
                candidates.add(WebsocketCandidate(normalizedEndpoint, normalizedScheme))
            }
        }

        for (entry in entries) {
            when {
                entry.startsWith("ws://") || entry.startsWith("wss://") -> {
                    val explicitScheme = entry.substringBefore("://").lowercase()
                    addCandidate(entry, explicitScheme)
                }

                entry.startsWith("http://") -> {
                    addCandidate(entry, "ws")
                }

                entry.startsWith("https://") -> {
                    addCandidate(entry, "wss")
                }

                else -> {
                    addCandidate(entry, "wss")
                    addCandidate(entry, "ws")
                }
            }
        }

        if (candidates.isEmpty()) {
            addCandidate(trimmedEndpoint, "wss")
            addCandidate(trimmedEndpoint, "ws")
        }

        return candidates
    }

    private fun describeCandidateLabel(candidate: WebsocketCandidate): String {
        return if (
            candidate.endpoint.startsWith("ws://") ||
            candidate.endpoint.startsWith("wss://")
        ) {
            candidate.endpoint
        } else {
            "${candidate.scheme}://${candidate.endpoint}"
        }
    }

    private fun describeFailureCause(throwable: Throwable?, response: Response?): String {
        val code = response?.code
        val responseMessage = response?.message
        val throwableMessage = throwable?.message?.trim()?.ifBlank { null }
        val details = when {
            !throwableMessage.isNullOrBlank() -> throwableMessage
            !responseMessage.isNullOrBlank() -> responseMessage
            else -> "unknown"
        }
        val candidate = activeCandidate?.let { describeCandidateLabel(it) } ?: "not initialized"
        val codeText = if (code == null) "" else "code=$code "
        return "$candidate ${codeText}failure: ${details}"
    }

    private fun resolveWebsocketBase(trimmedEndpoint: String, scheme: String): String {
        return when {
            trimmedEndpoint.startsWith("ws://") || trimmedEndpoint.startsWith("wss://") -> trimmedEndpoint.replaceFirst(
                Regex("^\\w+://"), "$scheme://"
            )
            trimmedEndpoint.startsWith("http://") && scheme == "ws" -> trimmedEndpoint.replaceFirst("http://", "ws://")
            trimmedEndpoint.startsWith("http://") && scheme == "wss" -> trimmedEndpoint.replaceFirst("http://", "wss://")
            trimmedEndpoint.startsWith("https://") && scheme == "ws" -> trimmedEndpoint.replaceFirst("https://", "ws://")
            trimmedEndpoint.startsWith("https://") && scheme == "wss" -> trimmedEndpoint.replaceFirst("https://", "wss://")
            else -> "$scheme://$trimmedEndpoint"
        }.trimEnd('/')
    }

    private fun buildRequest(candidate: WebsocketCandidate): Request? {
        val encodedUserId = encodeQuery(config.userId)
        val encodedUserKey = encodeQuery(config.userKey)
        val encodedDeviceId = encodeQuery(config.deviceId)
        val encodedNamespace = encodeQuery(config.namespace.ifBlank { "default" })
        val normalizedRoles = normalizeRolesForWire(config.roles.ifBlank { "endpoint,peer,node,app" })
        val encodedRoles = encodeQuery(normalizedRoles)
        val encodedArchetype = encodeQuery(getCurrentArchetype())
        val encodedClientId = encodeQuery((config.userId.ifBlank { config.deviceId }).ifBlank { "android-client" })
        val wsMode = if (getCurrentArchetype().contains("forward")) "push" else "reverse"
        val trimmed = candidate.endpoint.trim().trimStart('/')
        if (trimmed.isBlank()) return null

        val websocketBase = resolveWebsocketBase(trimmed, candidate.scheme)
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

        val url = "$websocketUrl?mode=$wsMode&archetype=$encodedArchetype&userId=$encodedUserId&userKey=$encodedUserKey&deviceId=$encodedDeviceId&clientId=$encodedClientId&namespace=$encodedNamespace&roles=$encodedRoles"

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

}
