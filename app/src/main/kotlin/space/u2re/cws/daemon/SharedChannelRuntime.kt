package space.u2re.cws.daemon

data class SharedChannelDecision<T>(
    val accepted: Boolean,
    val reason: String? = null,
    val cached: T? = null
)

private data class SharedChannelSnapshot<T>(
    val payload: T,
    val fingerprint: String,
    val uuid: String?,
    val sourceId: String?,
    val targetId: String?,
    val timestampMs: Long,
    val expiresAtMs: Long,
    val remote: Boolean
)

class SharedChannelRuntime<T>(
    private val channelName: String,
    private val fingerprintOf: (T) -> String,
    private val duplicateWindowMs: Long = 1_000L,
    private val duplicateUuidWindowMs: Long = 2_000L,
    private val ttlMs: Long = 30_000L
) {
    private var cachedSnapshot: SharedChannelSnapshot<T>? = null
    private var lastLocalRead: SharedChannelSnapshot<T>? = null
    private var lastOutbound: SharedChannelSnapshot<T>? = null
    private var lastInbound: SharedChannelSnapshot<T>? = null
    private var remoteClipboard: SharedChannelSnapshot<T>? = null

    @Synchronized
    fun recordLocalRead(payload: T, nowMs: Long = System.currentTimeMillis()): SharedChannelDecision<T> {
        val fingerprint = fingerprintOf(payload)
        if (fingerprint.isBlank()) {
            return SharedChannelDecision(accepted = false, reason = "$channelName empty fingerprint")
        }
        val duplicate = lastLocalRead
        if (duplicate != null && duplicate.fingerprint == fingerprint && nowMs - duplicate.timestampMs < duplicateWindowMs) {
            return SharedChannelDecision(accepted = false, reason = "$channelName duplicate local read", cached = validCached(nowMs)?.payload)
        }
        val snapshot = snapshot(payload, fingerprint, uuid = null, sourceId = "local", targetId = null, remote = false, nowMs = nowMs)
        lastLocalRead = snapshot
        cachedSnapshot = snapshot
        return SharedChannelDecision(accepted = true, cached = payload)
    }

    @Synchronized
    fun evaluateInbound(payload: T, uuid: String?, sourceId: String?, targetId: String?, nowMs: Long = System.currentTimeMillis()): SharedChannelDecision<T> {
        val fingerprint = fingerprintOf(payload)
        if (fingerprint.isBlank()) {
            return SharedChannelDecision(accepted = false, reason = "$channelName empty inbound fingerprint")
        }
        val normalizedUuid = uuid?.trim()?.ifBlank { null }
        val recentInbound = lastInbound
        if (normalizedUuid != null && recentInbound?.uuid == normalizedUuid && nowMs - recentInbound.timestampMs < duplicateUuidWindowMs) {
            return SharedChannelDecision(accepted = false, reason = "$channelName duplicate uuid", cached = validCached(nowMs)?.payload)
        }
        if (recentInbound != null && recentInbound.fingerprint == fingerprint && nowMs - recentInbound.timestampMs < duplicateWindowMs) {
            return SharedChannelDecision(accepted = false, reason = "$channelName duplicate inbound payload", cached = validCached(nowMs)?.payload)
        }
        val snapshot = snapshot(payload, fingerprint, normalizedUuid, sourceId, targetId, remote = true, nowMs = nowMs)
        lastInbound = snapshot
        cachedSnapshot = snapshot
        remoteClipboard = snapshot
        return SharedChannelDecision(accepted = true, cached = payload)
    }

    @Synchronized
    fun allowPlatformWrite(payload: T, uuid: String?, sourceId: String?, targetId: String?, nowMs: Long = System.currentTimeMillis()): SharedChannelDecision<T> {
        val fingerprint = fingerprintOf(payload)
        if (fingerprint.isBlank()) {
            return SharedChannelDecision(accepted = false, reason = "$channelName empty outbound fingerprint")
        }
        val _sourceId = sourceId
        val _targetId = targetId
        val cached = validCached(nowMs)
        if (cached != null && cached.fingerprint == fingerprint && !cached.remote) {
            return SharedChannelDecision(accepted = false, reason = "$channelName matches got cache", cached = cached.payload)
        }
        val previousOutbound = lastOutbound
        if (previousOutbound != null && previousOutbound.fingerprint == fingerprint && nowMs - previousOutbound.timestampMs < duplicateWindowMs) {
            return SharedChannelDecision(accepted = false, reason = "$channelName duplicate outbound payload", cached = cached?.payload)
        }
        if (!uuid.isNullOrBlank()) {
            val previousInbound = lastInbound
            if (previousInbound?.uuid == uuid && nowMs - previousInbound.timestampMs < duplicateUuidWindowMs) {
                return SharedChannelDecision(accepted = false, reason = "$channelName uuid still guarded", cached = cached?.payload)
            }
        }
        if (_sourceId != null || _targetId != null) {
            // Keep source/target available for future channel-specific policy without rejecting current remote writes.
        }
        return SharedChannelDecision(accepted = true, cached = cached?.payload)
    }

    @Synchronized
    fun recordOutbound(payload: T, uuid: String?, sourceId: String?, targetId: String?, nowMs: Long = System.currentTimeMillis()) {
        val fingerprint = fingerprintOf(payload)
        if (fingerprint.isBlank()) return
        val snapshot = snapshot(payload, fingerprint, uuid, sourceId, targetId, remote = false, nowMs = nowMs)
        lastOutbound = snapshot
        cachedSnapshot = snapshot
        remoteClipboard = null
    }

    @Synchronized
    fun cache(): T? = validCached(System.currentTimeMillis())?.payload

    @Synchronized
    fun cachedFingerprint(nowMs: Long = System.currentTimeMillis()): String? = validCached(nowMs)?.fingerprint

    @Synchronized
    fun shouldClearExpiredRemote(currentPayload: T?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val activeRemote = remoteClipboard ?: return false
        if (activeRemote.expiresAtMs > nowMs) return false
        remoteClipboard = null
        if (cachedSnapshot?.fingerprint == activeRemote.fingerprint) {
            cachedSnapshot = null
        }
        val currentFingerprint = currentPayload?.let(fingerprintOf)
        return currentFingerprint != null && currentFingerprint == activeRemote.fingerprint
    }

    private fun snapshot(
        payload: T,
        fingerprint: String,
        uuid: String?,
        sourceId: String?,
        targetId: String?,
        remote: Boolean,
        nowMs: Long
    ): SharedChannelSnapshot<T> {
        return SharedChannelSnapshot(
            payload = payload,
            fingerprint = fingerprint,
            uuid = uuid?.trim()?.ifBlank { null },
            sourceId = sourceId?.trim()?.ifBlank { null },
            targetId = targetId?.trim()?.ifBlank { null },
            timestampMs = nowMs,
            expiresAtMs = nowMs + ttlMs,
            remote = remote
        )
    }

    private fun validCached(nowMs: Long): SharedChannelSnapshot<T>? {
        val snapshot = cachedSnapshot ?: return null
        if (snapshot.expiresAtMs <= nowMs) return null
        return snapshot
    }
}
