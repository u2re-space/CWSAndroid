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
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import com.google.gson.Gson

class ReverseGatewayClient(
    private val config: ReverseGatewayConfig,
    private val onMessage: (String, String, String?) -> Unit = { _, _, _ -> }
) {
    companion object {
        private const val DEFAULT_REVERSE_ROLES = "endpoint,peer,node,app"
        private const val LOG_TAG = "ReverseGateway"
        private val gson = Gson()

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

    private val archetypeCandidates = buildArchetypeCandidates(config.roles)
    private var archetypeAttempt = 0
    private val configuredKeepAliveMs = config.keepAliveIntervalMs.coerceAtLeast(1_000L)
    private val configuredReconnectMs = config.reconnectDelayMs.coerceAtLeast(500L)

    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(configuredKeepAliveMs, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = configuredReconnectMs
    private var running = false

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

    fun sendRelay(type: String, data: Any?, target: String? = null, route: String? = null, namespace: String? = null) {
        val payload = mapOf(
            "type" to type,
            "target" to (target ?: "broadcast"),
            "route" to (route ?: "local"),
            "namespace" to (namespace ?: config.namespace),
            "data" to data,
            "ts" to System.currentTimeMillis()
        )
        val encoded = ReverseRelayCodec.encodeForServer(
            deviceId = config.deviceId,
            payload = payload,
            aesMasterKey = config.masterKey.ifBlank { null },
            signingPrivateKeyPem = config.signingPrivateKeyPem.ifBlank { null }
        )
        socket?.send(encoded)
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
        connect();
    }

    fun stop() {
        running = false;
        heartbeatJob?.cancel();
        reconnectJob?.cancel();
        heartbeatJob = null;
        reconnectJob = null;
        socket?.close(1000, "stopped");
        socket = null;
    }

    private fun connect() {
        if (!running) return;
        if (config.endpointUrl.isBlank()) return;

        val request = buildRequest() ?: run {
            Log.e(LOG_TAG, "Reverse gateway URL is invalid");
            return;
        };
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val helloClientId = (config.userId.ifBlank { config.deviceId }).ifBlank { "android-client" }
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
                Log.i(LOG_TAG, "Reverse gateway connected");
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
                    Log.w(LOG_TAG, "Reverse gateway message parse failure", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (t is CancellationException) return
                Log.w(LOG_TAG, "Reverse gateway failed", t)
                webSocket.close(1000, "failure")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(LOG_TAG, "Reverse gateway closed: code=$code reason=$reason")
                if (running && isArchetypeReject(code, reason) && nextArchetypeCandidate()) {
                    Log.i(LOG_TAG, "Trying fallback archetype ${getCurrentArchetype()}")
                    connect()
                    return
                }
                if (running) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running) return
        heartbeatJob?.cancel()
        heartbeatJob = null
        val delayMs = reconnectDelayMs.coerceAtLeast(1_000).coerceAtMost(30_000)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            connect()
        }
    }

    private fun buildRequest(): Request? {
        val encodedUserId = encodeQuery(config.userId)
        val encodedUserKey = encodeQuery(config.userKey)
        val encodedDeviceId = encodeQuery(config.deviceId)
        val encodedNamespace = encodeQuery(config.namespace.ifBlank { "default" })
        val normalizedRoles = normalizeRolesForWire(config.roles.ifBlank { "endpoint,peer,node,app" })
        val encodedRoles = encodeQuery(normalizedRoles)
        val encodedArchetype = encodeQuery(getCurrentArchetype())
        val encodedClientId = encodeQuery((config.userId.ifBlank { config.deviceId }).ifBlank { "android-client" })
        val wsMode = if (getCurrentArchetype().contains("forward")) "push" else "reverse"
        val trimmed = config.endpointUrl.trim().trimStart('/')
        if (trimmed.isBlank()) return null

        val websocketBase = when {
            trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
            trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "ws://")
            trimmed.startsWith("https://") -> trimmed.replaceFirst("https://", "wss://")
            else -> "wss://$trimmed"
        }.trimEnd('/')
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
