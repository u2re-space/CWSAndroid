package space.u2re.cws.network

import java.net.URI
import java.net.URLEncoder
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.net.ssl.SSLContext
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Compact socket diagnostics snapshot surfaced to daemon/UI layers.
 *
 * AI-READ: this is the main bridge between the internal reconnect/candidate
 * state machine and the human-readable status shown elsewhere in the app.
 */
data class ServerV2SocketDiagnostics(
    val connected: Boolean,
    val activeTransport: String,
    val endpoint: String,
    val candidateState: String,
    val activeCandidate: String,
    val candidateList: String,
    val lastState: String,
    val lastDetail: String?
)

/**
 * Android WebSocket client for the CWSP v2 realtime transport.
 *
 * It owns endpoint candidate rotation, TLS/custom-CA handling, reconnect and
 * keepalive loops, handshake identity rendering, and packet encode/decode.
 */
class ServerV2SocketClient(
    private val config: EndpointCoreConfig,
    private val onPacket: (ServerV2Packet) -> Unit = {},
    private val onState: (String, String?) -> Unit = { _, _ -> }
) {
    private val hiddenQueryKeys = setOf("token", "airpadtoken", "userkey")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectMonitorJob: Job? = null
    private var keepaliveMonitorJob: Job? = null
    private var lastReconnectAtMs: Long = 0L
    private val reconnectEveryMs: Long = 1_500L
    private val keepaliveEveryMs: Long = listOf(
        System.getenv("CWS_ANDROID_WS_KEEPALIVE_MS"),
        System.getProperty("cws.android.wsKeepaliveMs")
    ).firstNotNullOfOrNull { it?.trim()?.toLongOrNull() }?.coerceAtLeast(4_000L) ?: 15_000L
    private val staleSocketAfterMs: Long = listOf(
        System.getenv("CWS_ANDROID_WS_STALE_MS"),
        System.getProperty("cws.android.wsStaleMs")
    ).firstNotNullOfOrNull { it?.trim()?.toLongOrNull() }?.coerceAtLeast(keepaliveEveryMs + 2_000L) ?: 45_000L
    private var lastInboundAtMs: Long = 0L

    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    private val endpointCandidates = ServerV2WireContract.resolveSocketEndpointCandidates(config)
    private var endpointIndex = 0
    private val tlsRejectedHosts = mutableSetOf<String>()
    private var started = false
    private var connected = false
    private var lastState = "stopped"
    private var lastDetail: String? = "not-started"
    private val relaxIpHostnameMismatch: Boolean = listOf(
        System.getenv("CWS_ANDROID_TLS_RELAX_IP_HOSTNAME"),
        System.getProperty("cws.android.tlsRelaxIpHostname")
    ).firstNotNullOfOrNull { it?.trim() }
        ?.lowercase()
        ?.let { it == "1" || it == "true" || it == "yes" || it == "on" }
        ?: true

    /**
     * Start the socket client plus the background reconnect/keepalive monitors.
     *
     * WHY: Android connectivity callbacks are not always reliable enough on
     * their own, so the client maintains its own recovery loop as well.
     */
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
                val healthy = connected && socket != null
                if (!healthy) {
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
        keepaliveMonitorJob?.cancel()
        keepaliveMonitorJob = scope.launch {
            while (isActive && started) {
                if (connected && socket != null) {
                    val now = System.currentTimeMillis()
                    val idleForMs = now - lastInboundAtMs
                    if (idleForMs >= keepaliveEveryMs) {
                        val sent = sendKeepaliveProbe()
                        if (!sent) {
                            updateState("keepalive-failed", "keepalive probe failed; forcing reconnect")
                            restartSocket(rotate = true)
                        }
                    }
                    if (idleForMs >= staleSocketAfterMs) {
                        updateState("stale", "no inbound packets for ${idleForMs}ms; reconnecting")
                        restartSocket(rotate = true)
                    }
                }
                delay(keepaliveEveryMs)
            }
        }
    }

    /** Stop monitors, close the live websocket, and release the OkHttp client. */
    fun stop() {
        started = false
        connected = false
        reconnectMonitorJob?.cancel()
        reconnectMonitorJob = null
        keepaliveMonitorJob?.cancel()
        keepaliveMonitorJob = null
        socket?.close(1001, "stop")
        socket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        updateState("stopped", "server-v2 socket stopped")
    }

    /** Force an immediate reconnect attempt, usually after connectivity or config changes. */
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

    fun isConnected(): Boolean = connected && socket != null

    /** Snapshot the current candidate/transport state for daemon diagnostics and UI. */
    fun getDiagnostics(): ServerV2SocketDiagnostics = ServerV2SocketDiagnostics(
        connected = isConnected(),
        activeTransport = "ws",
        endpoint = currentEndpoint(),
        candidateState = "${endpointIndex + 1}/${if (endpointCandidates.isEmpty()) 1 else endpointCandidates.size}",
        activeCandidate = currentEndpoint(),
        candidateList = if (endpointCandidates.isEmpty()) currentEndpoint() else endpointCandidates.joinToString(","),
        lastState = lastState,
        lastDetail = lastDetail
    )

    /** Encode and send one packet over the active websocket if the socket is healthy. */
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
            active.send(ServerV2PacketCodec.encode(normalized))
        } catch (_: Exception) {
            false
        }
    }

    /** Send the standard token/identity probe immediately after connect. */
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

    /** Send the lightweight keepalive packet used to detect stalled but not yet closed sockets. */
    private fun sendKeepaliveProbe(): Boolean {
        val identity = currentIdentity()
        return sendPacket(
            ServerV2Packet(
                op = "ask",
                what = "token",
                payload = mapOf("keepalive" to true, "at" to System.currentTimeMillis()),
                nodes = listOf("*"),
                byId = identity.senderId()
            )
        )
    }

    /** Update the externally visible state and remember TLS-failure hints for candidate rotation. */
    private fun updateState(event: String, detail: String?) {
        maybeMarkTlsRejectedEndpoint(detail)
        lastState = event
        lastDetail = detail
        onState(event, detail)
    }

    /** Return the currently selected endpoint candidate, or the fallback endpoint from config. */
    private fun currentEndpoint(): String {
        if (endpointCandidates.isEmpty()) {
            return ServerV2WireContract.resolve(config).endpointUrl.ifBlank { config.dispatchUrl }
        }
        return endpointCandidates[endpointIndex.coerceIn(0, endpointCandidates.lastIndex)]
    }

    /** Re-resolve the wire identity against the current endpoint candidate. */
    private fun currentIdentity(): ServerV2WireIdentity {
        val base = ServerV2WireContract.resolve(config)
        val endpoint = currentEndpoint().ifBlank { base.endpointUrl }
        return base.copy(endpointUrl = endpoint)
    }

    /**
     * Rotate to the next viable endpoint candidate.
     *
     * NOTE: secure endpoints already marked as TLS-rejected are skipped where
     * possible so repeated reconnects move on to the next useful candidate.
     */
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

    /**
     * Rebuild the websocket against the current or next candidate endpoint.
     *
     * AI-READ: this is the core transport state-machine method; most connection
     * failures, candidate changes, and TLS fallbacks eventually pass through it.
     */
    private fun restartSocket(rotate: Boolean) {
        if (rotate) advanceEndpoint()
        socket?.close(1001, "reconnect")
        socket = null
        val identity = currentIdentity()
        val endpoint = toCanonicalWsUrl(identity.endpointUrl, identity)
        val endpointLog = endpointForDiagnostics(endpoint)
        if (client == null) {
            client = buildSocketClient()
        }
        updateState("connecting", endpointLog)
        val requestBuilder = Request.Builder().url(endpoint)
        ServerV2WireContract.buildHeaders(identity).forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                requestBuilder.header(key, value)
            }
        }
        val request = requestBuilder.build()
        socket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket != socket) return
                connected = true
                lastInboundAtMs = System.currentTimeMillis()
                updateState("connected", endpointLog)
                hello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket != socket) return
                lastInboundAtMs = System.currentTimeMillis()
                val packet = ServerV2PacketCodec.decode(text) ?: return
                onPacket(packet)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != socket) return
                connected = false
                updateState("closing", "$endpointLog code=$code reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != socket) return
                connected = false
                updateState("closed", "$endpointLog code=$code reason=$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != socket) return
                connected = false
                val details = listOfNotNull(
                    endpointLog,
                    t.message,
                    response?.code?.let { "http=$it" }
                ).joinToString(" | ")
                updateState("failed", details)
                if (isTlsHandshakeFailure(details) && started) {
                    val fromEndpoint = currentEndpoint()
                    restartSocket(rotate = true)
                    updateState(
                        "tls-fallback",
                        "tls handshake failed; rotating endpoint $fromEndpoint -> ${currentEndpoint()}"
                    )
                }
            }
        })
    }

    /** Remember endpoints that appear to fail due to TLS so later reconnects can de-prioritize them. */
    private fun maybeMarkTlsRejectedEndpoint(detail: String?) {
        val normalized = detail?.lowercase()?.trim().orEmpty()
        if (!isTlsHandshakeFailure(normalized) && !normalized.contains("unexpected end of stream")) return
        val endpoint = currentEndpoint()
        if (!isSecureSocketScheme(endpoint)) return
        val host = endpointHost(endpoint)
        if (host.isNotBlank()) {
            tlsRejectedHosts.add(host)
        }
    }

    /** Best-effort detector for TLS/certificate mismatch failures reported by OkHttp/SSL. */
    private fun isTlsHandshakeFailure(detail: String?): Boolean {
        val normalized = detail?.lowercase()?.trim().orEmpty()
        if (normalized.isBlank()) return false
        return normalized.contains("trust anchor for certification path not found") ||
            normalized.contains("certpathvalidatorexception") ||
            normalized.contains("unable to find valid certification path") ||
            normalized.contains("pkix path building failed") ||
            normalized.contains("certificate_unknown") ||
            normalized.contains("handshake") ||
            normalized.contains("ssl")
    }

    /**
     * Build the OkHttp websocket client with either insecure TLS, a custom CA,
     * or the default platform trust configuration.
     */
    private fun buildSocketClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .pingInterval(20, TimeUnit.SECONDS)
            .pingInterval(keepaliveEveryMs, java.util.concurrent.TimeUnit.MILLISECONDS)

        if (config.allowInsecureTls) {
            val trustAllManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllManager)
            builder.hostnameVerifier { _, _ -> true }
            return builder.build()
        }

        val trustedCaPath = resolveTrustedCaPath(config.trustedCa)
        if (trustedCaPath.isNotBlank()) {
            runCatching {
                val trustManager = trustManagerFromCustomCa(trustedCaPath)
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
                builder.hostnameVerifier { hostname, session ->
                    val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                    if (defaultVerifier.verify(hostname, session)) {
                        true
                    } else {
                        val isIpHost = hostname.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$"))
                        relaxIpHostnameMismatch && isIpHost
                    }
                }
            }.onFailure { error ->
                updateState("tls-ca-load-failed", "${trustedCaPath}: ${error.message}")
            }
        }
        return builder.build()
    }

    /** Resolve the custom CA file path from explicit config plus known config/storage fallbacks. */
    private fun resolveTrustedCaPath(raw: String?): String {
        val value = raw?.trim().orEmpty()
        val configDir = resolveConfigDirPath()
        val storageDir = config.storagePath.trim().removePrefix("fs:").removePrefix("file:").trim()

        val candidates = linkedSetOf<String>()
        if (value.isNotBlank()) {
            val normalized = value.removePrefix("fs:").removePrefix("file:").trim()
            if (normalized.isNotBlank()) candidates.add(normalized)
            if (normalized.isNotBlank() && !File(normalized).isAbsolute) {
                if (configDir.isNotBlank()) candidates.add(File(configDir, normalized).absolutePath)
                if (storageDir.isNotBlank()) candidates.add(File(storageDir, normalized).absolutePath)
            }
        }

        if (configDir.isNotBlank()) {
            val root = File(configDir).parentFile?.absolutePath.orEmpty()
            if (root.isNotBlank()) {
                candidates.add(File(root, "https/private/rootCA.crt").absolutePath)
                candidates.add(File(root, "https/local/rootCA.crt").absolutePath)
                candidates.add(File(root, "https/rootCA.crt").absolutePath)
            }
            candidates.add(File(configDir, "rootCA.crt").absolutePath)
        }

        if (storageDir.isNotBlank()) {
            candidates.add(File(storageDir, "https/private/rootCA.crt").absolutePath)
            candidates.add(File(storageDir, "https/local/rootCA.crt").absolutePath)
            candidates.add(File(storageDir, "https/rootCA.crt").absolutePath)
        }

        return candidates.firstOrNull { candidate ->
            runCatching { File(candidate).exists() && File(candidate).isFile }.getOrDefault(false)
        } ?: candidates.firstOrNull().orEmpty()
    }

    private fun resolveConfigDirPath(): String {
        val raw = config.configPath.trim()
        if (raw.isBlank()) return ""
        val normalized = raw.removePrefix("fs:").removePrefix("file:").trim()
        if (normalized.isBlank()) return ""
        return normalized
    }

    private fun trustManagerFromCustomCa(path: String): X509TrustManager {
        val certFactory = CertificateFactory.getInstance("X.509")
        val certificate = FileInputStream(path).use { stream ->
            certFactory.generateCertificate(stream)
        }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("cws-custom-ca", certificate)
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        val trustManager = trustManagerFactory.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
        return trustManager ?: throw IllegalStateException("No X509 trust manager for custom CA")
    }

    /** Skip secure endpoints already known to fail TLS validation for this session. */
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

    /**
     * Convert a server/api endpoint into the canonical websocket URL plus query.
     *
     * WHY: settings may start as HTTP API URLs or logical route targets, but
     * the live socket must always converge on the explicit `/ws` handshake URL.
     */
    private fun toCanonicalWsUrl(endpoint: String, identity: ServerV2WireIdentity): String {
        val source = try {
            URI(endpoint)
        } catch (_: Exception) {
            null
        }
        if (source == null) {
            return "ws://127.0.0.1:8080/ws"
        }
        val sourceScheme = source.scheme?.lowercase().orEmpty()
        val authorityHost = source.rawAuthority
            ?.substringAfterLast("@")
            ?.substringBefore(":")
            ?.trim()
            .orEmpty()
        val hostCandidate = source.host?.takeIf { it.isNotBlank() } ?: authorityHost
        val normalizedHost = when {
            hostCandidate.isBlank() -> ""
            EndpointIdentity.isLikelyNodeTarget(hostCandidate) -> {
                val canonical = EndpointIdentity.canonical(hostCandidate).substringBefore(":")
                if (canonical.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$"))) canonical else hostCandidate
            }
            else -> hostCandidate
        }
        val host = normalizedHost.ifBlank { "127.0.0.1" }
        val isLocalLoopback = host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)
        val scheme = when (sourceScheme) {
            "https", "wss" -> "wss"
            "http", "ws" -> if (isLocalLoopback) "ws" else "wss"
            else -> "wss"
        }
        val port = when {
            source.port > 0 && scheme == "wss" && source.port == 8080 -> 8443
            source.port > 0 -> source.port
            scheme == "wss" -> 8443
            else -> 8080
        }
        val path = source.path?.takeIf { it.isNotBlank() && it != "/" } ?: "/ws"

        val query = linkedMapOf<String, String>()
        source.query?.split("&")?.forEach { part ->
            val key = part.substringBefore("=").trim()
            val value = part.substringAfter("=", "").trim()
            if (key.isNotBlank()) query[key] = value
        }
        ServerV2WireContract.buildQuery(identity).forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) query[key] = value
        }
        val renderedQuery = query.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }

        return URI(
            scheme,
            null,
            host,
            port,
            path,
            renderedQuery.ifBlank { null },
            null
        ).toString()
    }

    /** Render a safe diagnostic endpoint string with secret-bearing query params redacted. */
    private fun endpointForDiagnostics(rawEndpoint: String): String {
        return try {
            val uri = URI(rawEndpoint)
            val safeQuery = uri.rawQuery
                ?.split("&")
                ?.mapNotNull { token ->
                    val key = token.substringBefore("=").trim()
                    if (key.isBlank()) return@mapNotNull null
                    val value = token.substringAfter("=", "")
                    if (hiddenQueryKeys.contains(key.lowercase())) {
                        "$key=***"
                    } else {
                        "$key=$value"
                    }
                }
                ?.joinToString("&")
                ?.ifBlank { null }
            URI(
                uri.scheme,
                uri.rawAuthority,
                uri.rawPath,
                safeQuery,
                uri.rawFragment
            ).toString()
        } catch (_: Exception) {
            rawEndpoint
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
