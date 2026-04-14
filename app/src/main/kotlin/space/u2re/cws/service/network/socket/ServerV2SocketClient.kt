package space.u2re.cws.network

import java.net.URI
import java.net.URLEncoder
import java.io.FileInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.util.UUID
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
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

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

class ServerV2SocketClient(
    private val config: EndpointCoreConfig,
    private val onPacket: (ServerV2Packet) -> Unit = {},
    private val onState: (String, String?) -> Unit = { _, _ -> }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectMonitorJob: Job? = null
    private var lastReconnectAtMs: Long = 0L
    private val reconnectEveryMs: Long = 1_500L

    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
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
    }

    fun stop() {
        started = false
        connected = false
        reconnectMonitorJob?.cancel()
        reconnectMonitorJob = null
        socket?.close(1001, "stop")
        socket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
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

    fun isConnected(): Boolean = connected && socket != null

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
        socket?.close(1001, "reconnect")
        socket = null
        val identity = currentIdentity()
        val endpoint = toCanonicalWsUrl(identity.endpointUrl, identity)
        if (client == null) {
            client = buildSocketClient()
        }
        updateState("connecting", endpoint)
        val request = Request.Builder().url(endpoint).build()
        socket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket != socket) return
                connected = true
                updateState("connected", endpoint)
                hello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket != socket) return
                val packet = ServerV2PacketCodec.decode(text) ?: return
                onPacket(packet)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != socket) return
                connected = false
                updateState("closing", "$endpoint code=$code reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != socket) return
                connected = false
                updateState("closed", "$endpoint code=$code reason=$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != socket) return
                connected = false
                val details = listOfNotNull(
                    endpoint,
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

    private fun buildSocketClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)

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
            }.onFailure { error ->
                updateState("tls-ca-load-failed", "${trustedCaPath}: ${error.message}")
            }
        }
        return builder.build()
    }

    private fun resolveTrustedCaPath(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return ""
        if (value.startsWith("fs:", ignoreCase = true)) return value.substring(3).trim()
        if (value.startsWith("file:", ignoreCase = true)) return value.removePrefix("file:").trim()
        return value
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

    private fun toCanonicalWsUrl(endpoint: String, identity: ServerV2WireIdentity): String {
        val source = try {
            URI(endpoint)
        } catch (_: Exception) {
            null
        }
        if (source == null) {
            return "ws://127.0.0.1:8080/ws"
        }
        val scheme = when (source.scheme?.lowercase()) {
            "https", "wss" -> "wss"
            else -> "ws"
        }
        val host = source.host?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
        val port = if (source.port > 0) source.port else if (scheme == "wss") 8443 else 8080
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
        if (identity.userKey.isNotBlank()) {
            query["token"] = identity.userKey
            query["airpadToken"] = identity.userKey
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

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
