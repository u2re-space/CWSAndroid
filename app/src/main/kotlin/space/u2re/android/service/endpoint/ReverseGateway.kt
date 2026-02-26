package io.livekit.android.example.voiceassistant.endpoint

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ReverseGatewayConfig(
    val serverUrl: String,
    val deviceId: String,
    val reverseMasterKey: String,
    val privateKeyPem: String,
    val publicKeys: Map<String, String>,
    val keepAliveIntervalMs: Long,
    val reconnectDelayMs: Long,
    val autoReconnect: Boolean,
    val maxReconnectAttempts: Int
) {
    init {
        require(keepAliveIntervalMs >= 0) { "keepAliveIntervalMs must be >= 0" }
        require(reconnectDelayMs >= 0) { "reconnectDelayMs must be >= 0" }
        require(maxReconnectAttempts >= 0) { "maxReconnectAttempts must be >= 0" }
    }

    class Builder {
        private var serverUrl = ""
        private var deviceId = "ns-android"
        private var reverseMasterKey = ""
        private var privateKeyPem = ""
        private var publicKeys: Map<String, String> = emptyMap()
        private var keepAliveIntervalMs = 15_000L
        private var reconnectDelayMs = 4_000L
        private var autoReconnect = true
        private var maxReconnectAttempts = 20

        fun serverUrl(serverUrl: String) = apply { this.serverUrl = serverUrl }
        fun deviceId(deviceId: String) = apply { this.deviceId = deviceId }
        fun reverseMasterKey(reverseMasterKey: String) = apply { this.reverseMasterKey = reverseMasterKey }
        fun privateKeyPem(privateKeyPem: String) = apply { this.privateKeyPem = privateKeyPem }
        fun publicKeys(publicKeys: Map<String, String>?) = apply { this.publicKeys = publicKeys ?: emptyMap() }
        fun keepAliveIntervalMs(keepAliveIntervalMs: Long) = apply { this.keepAliveIntervalMs = if (keepAliveIntervalMs <= 0L) 0L else keepAliveIntervalMs }
        fun reconnectDelayMs(reconnectDelayMs: Long) = apply { this.reconnectDelayMs = if (reconnectDelayMs <= 0L) 0L else reconnectDelayMs }
        fun autoReconnect(autoReconnect: Boolean) = apply { this.autoReconnect = autoReconnect }
        fun maxReconnectAttempts(maxReconnectAttempts: Int) = apply { this.maxReconnectAttempts = maxOf(0, maxReconnectAttempts) }

        fun build(): ReverseGatewayConfig = ReverseGatewayConfig(
            serverUrl = serverUrl,
            deviceId = if (deviceId.isBlank()) "ns-android" else deviceId,
            reverseMasterKey = reverseMasterKey,
            privateKeyPem = privateKeyPem,
            publicKeys = publicKeys,
            keepAliveIntervalMs = keepAliveIntervalMs,
            reconnectDelayMs = reconnectDelayMs,
            autoReconnect = autoReconnect,
            maxReconnectAttempts = maxReconnectAttempts
        )
    }
}

class ReverseRelayCodec(
    masterKey: String,
    publicKeys: Map<String, String>?,
    privateKeyPem: String?
) {
    data class ParsedPayload(val from: String, val inner: JsonElement)

    private companion object {
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_BYTES = 32
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        val GSON = Gson()
        val gson: Gson get() = GSON
    }

    private val masterKeySpec: SecretKeySpec = deriveMasterKey(masterKey)
    private val rawPublicKeys: Map<String, String> = publicKeys?.let { HashMap(it) } ?: emptyMap()
    private val parsedPublicKeys = ConcurrentHashMap<String, PublicKey?>()
    private val privateKeyPemNormalized = privateKeyPem?.trim().orEmpty()

    fun makePayload(from: String, innerJson: String): String {
        return try {
            val iv = ByteArray(GCM_IV_BYTES).apply { SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, masterKeySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            val cipherText = cipher.doFinal(innerJson.toByteArray(StandardCharsets.UTF_8))
            val cipherBlock = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, cipherBlock, 0, iv.size)
            System.arraycopy(cipherText, 0, cipherBlock, iv.size, cipherText.size)

            val cipherB64 = Base64.encodeToString(cipherBlock, Base64.NO_WRAP)
            val sig = sign(from, cipherBlock)
            val sigB64 = Base64.encodeToString(sig, Base64.NO_WRAP)

            val outer = JsonObject().apply {
                addProperty("from", from)
                addProperty("cipher", cipherB64)
                addProperty("sig", sigB64)
            }
            Base64.encodeToString(outer.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Failed to make payload", e)
        }
    }

    fun parsePayload(payloadBase64: String): ParsedPayload {
        return try {
            val outer = parseOuter(payloadBase64)
            val from = outer["from"]?.asString
                ?: throw IllegalArgumentException("Missing 'from'")
            val cipherB64 = outer["cipher"]?.asString
                ?: throw IllegalArgumentException("Missing 'cipher'")
            val sigB64 = outer["sig"]?.asString
                ?: throw IllegalArgumentException("Missing 'sig'")

            val cipherBlock = Base64.decode(cipherB64, Base64.NO_WRAP)
            val sig = Base64.decode(sigB64, Base64.NO_WRAP)

            if (!verifySignature(from, cipherBlock, sig)) {
                throw SecurityException("Signature verify failed for from=$from")
            }

            if (cipherBlock.size <= GCM_IV_BYTES) {
                throw IllegalStateException("Invalid cipher block length")
            }
            val iv = cipherBlock.copyOfRange(0, GCM_IV_BYTES)
            val encrypted = cipherBlock.copyOfRange(GCM_IV_BYTES, cipherBlock.size)

            val decipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            decipher.init(Cipher.DECRYPT_MODE, masterKeySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            val innerJson = decipher.doFinal(encrypted).toString(StandardCharsets.UTF_8)
            val inner = JsonParser.parseString(innerJson)
            ParsedPayload(from, inner)
        } catch (e: RuntimeException) {
            throw
        } catch (e: Exception) {
            throw IllegalStateException("Failed to parse payload", e)
        }
    }

    fun verifyPayload(payloadBase64: String): Boolean {
        return try {
            val outer = parseOuter(payloadBase64)
            val from = outer["from"].asString
            val cipherBlock = Base64.decode(outer["cipher"].asString, Base64.NO_WRAP)
            val sig = Base64.decode(outer["sig"].asString, Base64.NO_WRAP)
            verifySignature(from, cipherBlock, sig)
        } catch (e: Exception) {
            false
        }
    }

    private fun parseOuter(payloadBase64: String): JsonObject {
        val raw = Base64.decode(payloadBase64, Base64.NO_WRAP).toString(StandardCharsets.UTF_8)
        val parsed = JsonParser.parseString(raw)
        if (!parsed.isJsonObject) throw IllegalArgumentException("Payload is not JSON")
        val outer = parsed.asJsonObject
        if (!outer.has("from") || !outer.has("cipher") || !outer.has("sig")) {
            throw IllegalArgumentException("Payload missing required fields")
        }
        return outer
    }

    private fun verifySignature(from: String, cipherBlock: ByteArray, sig: ByteArray): Boolean {
        val publicKey = getPublicKey(from) ?: return false
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update(cipherBlock)
        return signature.verify(sig)
    }

    private fun sign(from: String, cipherBlock: ByteArray): ByteArray {
        if (privateKeyPemNormalized.isEmpty()) {
            throw GeneralSecurityException("No private key configured")
        }
        val privateKey = getPrivateKey()
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(cipherBlock)
        return signature.sign()
    }

    private fun getPrivateKey(): PrivateKey {
        val encoded = decodePem(privateKeyPemNormalized)
        val spec = PKCS8EncodedKeySpec(encoded)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    private fun getPublicKey(deviceId: String): PublicKey? {
        val cached = parsedPublicKeys[deviceId]
        if (cached != null) {
            return cached
        }
        val pem = rawPublicKeys[deviceId] ?: return null
        if (pem.isBlank()) {
            return null
        }
        return try {
            val encoded = decodePem(pem)
            val spec = X509EncodedKeySpec(encoded)
            val key = KeyFactory.getInstance("RSA").generatePublic(spec)
            parsedPublicKeys[deviceId] = key
            key
        } catch (e: Exception) {
            parsedPublicKeys[deviceId] = null
            null
        }
    }

    private fun decodePem(pem: String): ByteArray {
        val cleaned = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s+"), "")
        return Base64.decode(cleaned, Base64.NO_WRAP)
    }

    private fun deriveMasterKey(masterKey: String): SecretKeySpec {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(masterKey.toByteArray(StandardCharsets.UTF_8))
            SecretKeySpec(hash.copyOf(AES_KEY_BYTES), "AES")
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Unable to derive master key", e)
        }
    }

    fun toJson(value: Any): String = gson.toJson(value)
}

class ReverseGatewayClient(
    private val config: ReverseGatewayConfig,
    private val listener: Listener,
    private val httpClient: OkHttpClient? = null
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(code: Int, reason: String?)
        fun onPlainMessage(from: String?, json: String)
        fun onReverseMessage(from: String?, innerJson: String)
        fun onError(message: String)
    }

    private companion object {
        const val WS_CLOSE_REASON_STOP = "client_stop"
    }

    private val codec: ReverseRelayCodec = ReverseRelayCodec(
        config.reverseMasterKey,
        config.publicKeys,
        config.privateKeyPem
    )
    private val client: OkHttpClient = httpClient ?: OkHttpClient()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var webSocket: WebSocket? = null
    private var started = false
    private var connected = false
    private var reconnectAttempts = 0
    private var reconnectTask: ScheduledFuture<*>? = null
    private var keepAliveTask: ScheduledFuture<*>? = null

    fun start() {
        if (started) return
        started = true
        reconnectAttempts = 0
        connect()
    }

    fun stop() {
        started = false
        cancelKeepAlive()
        cancelReconnect()
        closeCurrentSocket(1000, WS_CLOSE_REASON_STOP)
        connected = false
        scheduler.shutdownNow()
    }

    fun isConnected(): Boolean = connected

    fun sendPlainMessage(json: String): Boolean = sendText(json)

    fun sendReverseMessage(to: String?, type: String?, action: String?, data: JsonElement?): Boolean {
        val target = to?.trim().takeUnless { it.isNullOrBlank() } ?: "broadcast"
        val inner = JsonObject().apply {
            addProperty("ts", System.currentTimeMillis())
            if (!type.isNullOrBlank()) addProperty("type", type)
            if (!action.isNullOrBlank()) addProperty("action", action)
            add("data", data ?: JsonNull.INSTANCE)
        }
        val frame = JsonObject().apply {
            addProperty("type", type ?: "clip")
            addProperty("from", config.deviceId)
            addProperty("to", target)
            addProperty("mode", "blind")
            addProperty("action", action.orEmpty())
        }

        if (config.reverseMasterKey.isNotBlank()) {
            val payload = codec.makePayload(config.deviceId, inner.toString())
            frame.addProperty("payload", payload)
        } else {
            frame.add("payload", inner)
        }
        return sendText(frame.toString())
    }

    private fun connect() {
        if (!started) return
        cancelReconnect()
        val socketUrl = normalizeWebSocketUrl(config.serverUrl)
        if (socketUrl.isBlank()) {
            listener.onError("serverUrl is empty")
            return
        }

        val request = Request.Builder().url(socketUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected = true
                reconnectAttempts = 0
                scheduleKeepAlive()
                listener.onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingText(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleIncomingText(bytes.utf8())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connected = false
                cancelKeepAlive()
                listener.onError(t.message ?: "websocket failure")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected = false
                cancelKeepAlive()
                listener.onDisconnected(code, reason)
                scheduleReconnect()
            }
        })
    }

    private fun handleIncomingText(text: String) {
        try {
            val raw = JsonParser.parseString(text).asJsonObject
            val payloadElement = raw["payload"]
            val wrappedPayload = if (payloadElement != null && payloadElement.isJsonPrimitive) payloadElement.asString else null
            if (wrappedPayload != null) {
                val parsed = tryParseReversePayload(wrappedPayload)
                if (parsed != null) {
                    listener.onReverseMessage(parsed.from, parsed.inner.toString())
                    return
                }
            }
            listener.onPlainMessage(raw["from"]?.asString, raw.toString())
        } catch (e: IllegalArgumentException) {
            listener.onPlainMessage(null, text)
        } catch (e: JsonSyntaxException) {
            listener.onPlainMessage(null, text)
        } catch (e: JsonParseException) {
            listener.onPlainMessage(null, text)
        }
    }

    private fun tryParseReversePayload(wrapped: String): ReverseRelayCodec.ParsedPayload? {
        return try {
            codec.parsePayload(wrapped)
        } catch (e: Exception) {
            null
        }
    }

    private fun sendText(text: String): Boolean {
        val ws = webSocket
        if (!connected || ws == null) return false
        return ws.send(text)
    }

    private fun scheduleKeepAlive() {
        cancelKeepAlive()
        if (config.keepAliveIntervalMs <= 0) return
        keepAliveTask = scheduler.scheduleAtFixedRate({
            val ping = JsonObject().apply {
                addProperty("type", "ping")
                addProperty("from", config.deviceId)
                addProperty("ts", System.currentTimeMillis())
            }
            sendText(ping.toString())
        }, config.keepAliveIntervalMs, config.keepAliveIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleReconnect() {
        if (!started || !config.autoReconnect) return
        if (config.maxReconnectAttempts > 0 && reconnectAttempts >= config.maxReconnectAttempts) return

        val nextAttempt = ++reconnectAttempts
        val delay = minOf(config.reconnectDelayMs * nextAttempt, 30_000L)
        reconnectTask = scheduler.schedule({
            connect()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun cancelReconnect() {
        reconnectTask?.let {
            if (!it.isDone) it.cancel(true)
        }
        reconnectTask = null
    }

    private fun cancelKeepAlive() {
        keepAliveTask?.let {
            if (!it.isDone) it.cancel(true)
        }
        keepAliveTask = null
    }

    private fun closeCurrentSocket(code: Int, reason: String) {
        webSocket?.close(code, reason)
        webSocket = null
    }

    private fun normalizeWebSocketUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim()
        if (trimmed.isBlank()) return ""
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("ws://") || lower.startsWith("wss://") -> trimmed
            lower.startsWith("http://") -> "ws://${trimmed.substring("http://".length)}"
            lower.startsWith("https://") -> "wss://${trimmed.substring("https://".length)}"
            else -> "ws://$trimmed"
        }
    }

    companion object {
        val emptyPublicKeys: Map<String, String> = Collections.emptyMap()
    }
}
