package space.u2re.cws.daemon

/** One pending HTTP dispatch item plus an optional direct-fallback URL hint. */
data class HubDispatchPayloadItem(
    val request: Map<String, Any>,
    val directFallbackUrl: String? = null
)

/** Compact runtime snapshot surfaced by daemon/UI code for transport diagnostics. */
data class TransportRuntimeDiagnostics(
    val configured: Boolean,
    val enabled: Boolean,
    val connected: Boolean,
    val state: String,
    val stateDetail: String?,
    val endpoint: String,
    val candidateState: String?,
    val activeCandidate: String?,
    val candidateList: String?,
    val lastError: String?
)

/** Counters used to understand relay usage, fallback frequency, and reconnect behavior. */
data class TransportRuntimeMetrics(
    val relayAttempts: Long,
    val relaySuccess: Long,
    val relayFallback: Long,
    val relayFailures: Long,
    val httpAttempts: Long,
    val httpSuccesses: Long,
    val httpFailures: Long,
    val reconnectRequests: Long,
    val reconnectSuccesses: Long
)
