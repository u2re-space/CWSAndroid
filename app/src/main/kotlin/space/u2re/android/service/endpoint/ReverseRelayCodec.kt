package io.livekit.android.example.voiceassistant.reverse

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ReverseRelayDecoded(
    val from: String,
    val inner: Any?
)

object ReverseRelayCodec {
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_SIZE_BYTES = 12
    private const val RSA_ALGO = "RSA"
    private const val SIGNATURE_ALGO = "SHA256withRSA"
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Any>>() {}.type

    private data class DenoPayloadEnvelope(
        val from: String,
        val cipher: String,
        val sig: String
    )

    fun decodeIncoming(
        rawText: String,
        aesMasterKey: String?,
        peerPublicKeyPem: String?,
    ): ReverseRelayDecoded? {
        parseEnvelope(rawText)?.let { envelope ->
            return decodeEnvelope(envelope, aesMasterKey, peerPublicKeyPem)
        }

        return try {
            val raw = JsonParser.parseString(rawText).asJsonObject
            val from = raw.get("from")?.takeIf { it.isJsonPrimitive }?.asString ?: "server"
            ReverseRelayDecoded(from, gson.fromJson(raw, mapType))
        } catch (_: Exception) {
            null
        }
    }

    fun encodeForServer(
        deviceId: String,
        payload: Any,
        aesMasterKey: String?,
        signingPrivateKeyPem: String?
    ): String {
        if (aesMasterKey.isNullOrBlank()) {
            return gson.toJson(payload)
        }

        val envelope = createEnvelope(deviceId, payload, aesMasterKey, signingPrivateKeyPem)
        return gson.toJson(envelope)
    }

    private fun createEnvelope(
        deviceId: String,
        payload: Any,
        aesMasterKey: String,
        signingPrivateKeyPem: String?
    ): Map<String, String> {
        val innerBytes = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        val key = buildAesKey(aesMasterKey)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(IV_SIZE_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val encrypted = cipher.doFinal(innerBytes)
        val block = ByteArray(iv.size + encrypted.size).apply {
            iv.copyInto(this)
            encrypted.copyInto(this, iv.size)
        }
        val signature = signBlock(block, signingPrivateKeyPem)
        return mapOf(
            "from" to deviceId,
            "cipher" to Base64.getEncoder().encodeToString(block),
            "sig" to Base64.getEncoder().encodeToString(signature)
        )
    }

    private fun decodeEnvelope(
        envelope: DenoPayloadEnvelope,
        aesMasterKey: String?,
        peerPublicKeyPem: String?
    ): ReverseRelayDecoded? {
        if (aesMasterKey.isNullOrBlank()) return null
        val block = try { Base64.getDecoder().decode(envelope.cipher) } catch (_: Exception) { return null }
        val signature = try { Base64.getDecoder().decode(envelope.sig) } catch (_: Exception) { return null }
        if (!verifySignature(envelope.from, block, signature, peerPublicKeyPem)) {
            return null
        }

        val key = buildAesKey(aesMasterKey)
        if (block.size < IV_SIZE_BYTES + 1) return null
        val iv = block.copyOfRange(0, IV_SIZE_BYTES)
        val encrypted = block.copyOfRange(IV_SIZE_BYTES, block.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val plain = cipher.doFinal(encrypted)

        val inner = JsonParser.parseString(String(plain, StandardCharsets.UTF_8)).let { element ->
            gson.fromJson(element, mapType)
        }
        return ReverseRelayDecoded(envelope.from, inner)
    }

    private fun buildAesKey(secret: String): SecretKey {
        val hash = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(hash, "AES")
    }

    private fun parsePem(pem: String): ByteArray? {
        val normalized = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        if (normalized.isBlank()) return null
        return try { Base64.getDecoder().decode(normalized) } catch (_: Exception) { null }
    }

    private fun signBlock(block: ByteArray, pem: String?): ByteArray {
        if (pem.isNullOrBlank()) return ByteArray(0)
        return try {
            val bytes = parsePem(pem) ?: return ByteArray(0)
            val kf = KeyFactory.getInstance(RSA_ALGO)
            val key = kf.generatePrivate(PKCS8EncodedKeySpec(bytes))
            val signer = Signature.getInstance(SIGNATURE_ALGO)
            signer.initSign(key)
            signer.update(block)
            signer.sign()
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun verifySignature(deviceId: String, block: ByteArray, signature: ByteArray, pem: String?): Boolean {
        if (signature.isEmpty()) return true
        if (pem.isNullOrBlank()) return true
        return try {
            val bytes = parsePem(pem) ?: return true
            val kf = KeyFactory.getInstance(RSA_ALGO)
            val key: PublicKey = kf.generatePublic(X509EncodedKeySpec(bytes))
            val verifier = Signature.getInstance(SIGNATURE_ALGO)
            verifier.initVerify(key)
            verifier.update(block)
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    private fun parseEnvelope(rawText: String): DenoPayloadEnvelope? {
        return try {
            val envelopeText = try {
                val decoded = Base64.getDecoder().decode(rawText)
                String(decoded, StandardCharsets.UTF_8)
            } catch (_: Exception) {
                rawText
            }
            val outer = JsonParser.parseString(envelopeText).asJsonObject
            val from = outer.get("from")?.asString ?: return null
            val cipher = outer.get("cipher")?.asString ?: return null
            val sig = outer.get("sig")?.asString ?: return null
            DenoPayloadEnvelope(from, cipher, sig)
        } catch (_: Exception) {
            null
        }
    }
}
