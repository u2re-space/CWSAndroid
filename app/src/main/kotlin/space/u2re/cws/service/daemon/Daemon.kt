package space.u2re.cws.daemon

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.u2re.cws.data.ClipboardEnvelope
import space.u2re.cws.data.ClipboardEnvelopeCodec
import space.u2re.cws.history.HistoryChannel
import space.u2re.cws.history.HistoryOrigin
import space.u2re.cws.history.HistoryRepository
import space.u2re.cws.notifications.NotificationEvent
import space.u2re.cws.notifications.NotificationEventStore
import space.u2re.cws.notifications.NotificationSpeaker
import space.u2re.cws.network.DispatchResult
import space.u2re.cws.network.sendSmsAndroid
import space.u2re.cws.network.DispatchRequest
import space.u2re.cws.network.dispatchHttpRequests
import space.u2re.cws.network.normalizeDestinationUrl
import space.u2re.cws.network.normalizeDestinationHost
import space.u2re.cws.network.postText
import space.u2re.cws.network.normalizeHubDispatchUrl
import space.u2re.cws.network.toEndpointCoreConfig
import space.u2re.cws.network.ServerV2Packet
import space.u2re.cws.network.ServerV2PacketCodec
import space.u2re.cws.network.TlsConfig
import space.u2re.cws.network.HttpServerOptions
import space.u2re.cws.network.LocalHttpServer
import space.u2re.cws.network.EndpointIdentity
import space.u2re.cws.reverse.ReverseGatewayConfigProvider
import space.u2re.cws.reverse.ReverseGatewayConfig
import space.u2re.cws.reverse.AssistantNetworkBridge
import space.u2re.cws.settings.ConfigResolver
import space.u2re.cws.settings.Settings
import space.u2re.cws.settings.SettingsPatch
import space.u2re.cws.settings.SettingsStore
import space.u2re.cws.settings.resolve
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class Daemon(
    private val application: Application,
    private val activityProvider: () -> Activity?
) {
    companion object {
        private const val REVERSE_CONNECT_ERROR_COOLDOWN_MS = 10_000L
        private const val REVERSE_BRIDGE_ERROR_COOLDOWN_MS = 10_000L
    }

    private var settings: Settings = SettingsStore.load(application).resolve()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var clipboardWatcher: ClipboardSyncWatcher? = null
    private var syncTimer: Job? = null
    private var clipboardFallbackTimer: Job? = null
    private var clipboardExpiryTimer: Job? = null
    private var httpServerHttp: LocalHttpServer? = null
    private var httpServerHttps: LocalHttpServer? = null
    private var transportRuntime: DaemonTransportRuntime? = null
    private var activeReverseGatewayConfig: ReverseGatewayConfig? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null
    private var lastNetworkReconnectAtMs = 0L
    private val clipboardTextFallback = ClipboardSyncWatcher(application, ::setClipboardBestEffort)
    private val clipboardRuntime = SharedChannelRuntime<ClipboardEnvelope>(
        channelName = "clipboard",
        fingerprintOf = { it.fingerprint() }
    )
    private var running = false
    private val reverseRelayAttempts = AtomicLong(0)
    private val reverseRelaySuccess = AtomicLong(0)
    private val reverseRelayFallback = AtomicLong(0)
    private val reverseRelayFailures = AtomicLong(0)
    private val reverseWsReconnectRequests = AtomicLong(0)
    private val reverseWsReconnectSuccess = AtomicLong(0)
    private @Volatile var reverseGatewayState = "stopped"
    private @Volatile var reverseGatewayStateDetail: String? = "not-started"
    private @Volatile var reverseGatewayStateUpdatedAtMs = 0L
    private @Volatile var reverseGatewayConfigured = false
    private var lastReverseGatewayLogKey: String? = null
    private var lastReverseGatewayLogAtMs = 0L
    private var lastReverseGatewaySuppressedCount = 0
    private val reversePathErrorLogAt = HashMap<String, Long>()
    private val reversePathErrorSuppressedCount = HashMap<String, Int>()
    private val reverseGatewayLogCooldownMs = 2_500L
    private val reverseHttpAttempts = AtomicLong(0)
    private val reverseHttpSuccesses = AtomicLong(0)
    private val reverseHttpFailures = AtomicLong(0)
    private val clipboardNoiseGson = Gson()

    init {
        DaemonLog.setLogLevel(settings.logLevel)
    }

    fun start() {
        if (running) return
        running = true
        setReverseGatewayState("starting", "daemon started")
        val topActivity = activityProvider()
        val activity = topActivity

        scope.launch {
            try {
                settings = SettingsStore.load(application).resolve()
                DaemonLog.setLogLevel(settings.logLevel)
                val syncCallbacks = createSyncCallbacks(activity)
                startServers(syncCallbacks)
                startReverseGateway(syncCallbacks)
                ensureReverseNetworkMonitoring()
                startClipboardSync()
                startClipboardPollingFallback()
                startClipboardExpiryCleaner()
                startPeriodicSync()
                handleShareTarget(activity)
                DaemonLog.info("Daemon", "daemon started")
            } catch (e: Exception) {
                DaemonLog.error("Daemon", "daemon start failed", e)
                running = false
            }
        }
    }

    fun stop() {
        running = false
        scope.launch {
            setReverseGatewayState("stopped", "daemon stopped")
            transportRuntime?.stop()
            transportRuntime = null
            reverseGatewayConfigured = false
            stopReverseNetworkMonitoring()
            clipboardWatcher?.stop()
            clipboardWatcher = null
            httpServerHttp?.stop()
            httpServerHttps?.stop()
            httpServerHttp = null
            httpServerHttps = null
            syncTimer?.cancel()
            syncTimer = null
            clipboardFallbackTimer?.cancel()
            clipboardFallbackTimer = null
            clipboardExpiryTimer?.cancel()
            clipboardExpiryTimer = null
            reversePathErrorLogAt.clear()
            reversePathErrorSuppressedCount.clear()
            lastReverseGatewaySuppressedCount = 0
            DaemonLog.info("Daemon", "daemon stopped")
        }
    }

    suspend fun restart() {
        stop()
        delay(50)
        start()
    }

    fun stopServers() {
        scope.launch {
            httpServerHttp?.stop()
            httpServerHttps?.stop()
            httpServerHttp = null
            httpServerHttps = null
            DaemonLog.info("Daemon", "local servers stopped")
        }
    }

    suspend fun restartServers() {
        stopServers()
        delay(50)
        val activity = activityProvider()
        startServers(createSyncCallbacks(activity))
    }

    private fun startReverseGateway(syncCallbacks: SyncCallbacks) {
        val baseReverseConfig = ReverseGatewayConfigProvider.load(application)
        val endpointConfig = settings.toEndpointCoreConfig(baseReverseConfig)
        val shouldEnableReverse = endpointConfig.isRemoteReady()
        val reverseCandidates = endpointConfig.endpointCandidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val reverseEndpoint = reverseCandidates.take(6).joinToString(",").ifBlank {
            endpointConfig.endpointUrl.ifBlank { endpointConfig.dispatchUrl }
        }
        val reverseConfig = baseReverseConfig.copy(
            deviceId = endpointConfig.deviceId,
            endpointUrl = reverseEndpoint,
            userId = endpointConfig.userId,
            userKey = endpointConfig.userKey,
            namespace = endpointConfig.namespace,
            roles = endpointConfig.roles,
            enabled = baseReverseConfig.enabled || shouldEnableReverse,
            allowInsecureTls = endpointConfig.allowInsecureTls,
            trustedCa = endpointConfig.trustedCa
        )
        if (baseReverseConfig.deviceId != reverseConfig.deviceId && reverseConfig.deviceId.isNotBlank()) {
            ReverseGatewayConfigProvider.saveDeviceId(application, reverseConfig.deviceId)
        }
        activeReverseGatewayConfig = reverseConfig
        reverseGatewayConfigured = endpointConfig.isRemoteReady()
        if (!reverseGatewayConfigured) {
            val missingFields = mutableListOf<String>().apply {
                if (endpointConfig.endpointUrl.isBlank() && endpointConfig.dispatchUrl.isBlank()) add("endpointUrl")
                if (endpointConfig.userId.isBlank()) add("userId")
                if (endpointConfig.userKey.isBlank()) add("userKey")
            }
            val reason = if (missingFields.isNotEmpty()) {
                "endpoint transport config missing: ${missingFields.joinToString(", ")}"
            } else {
                "endpoint transport disabled"
            }
            setReverseGatewayState("disabled", reason)
            DaemonLog.info("Daemon", reason)
            return
        }
        transportRuntime?.stop()
        transportRuntime = DaemonTransportRuntime(
            endpointConfig = endpointConfig,
            reverseConfig = reverseConfig,
            onV2Packet = { packet ->
                scope.launch {
                    runCatching {
                        AssistantNetworkBridge.handleServerV2Packet(
                            context = application,
                            packet = packet,
                            config = reverseConfig,
                            callbacks = syncCallbacks
                        )
                    }.onFailure { e ->
                        logSuppressedReverseGatewayError(
                            category = "server-v2-bridge",
                            signature = "server-v2 bridge failed",
                            cooldownMs = REVERSE_BRIDGE_ERROR_COOLDOWN_MS
                        ) {
                            DaemonLog.warn("Daemon", "server-v2 bridge failed", e)
                        }
                    }
                }
            },
            onLegacyMessage = { messageType, text ->
                scope.launch {
                    runCatching {
                        AssistantNetworkBridge.handleReverseMessage(
                            context = application,
                            messageType = messageType,
                            rawPayload = text,
                            config = reverseConfig,
                            callbacks = syncCallbacks
                        )
                    }.onFailure { e ->
                        logSuppressedReverseGatewayError(
                            category = "reverse-bridge",
                            signature = "reverse bridge failed",
                            cooldownMs = REVERSE_BRIDGE_ERROR_COOLDOWN_MS
                        ) {
                            DaemonLog.warn("Daemon", "reverse bridge failed", e)
                        }
                    }
                }
            },
            onState = { event, details ->
                setReverseGatewayState(event, details)
                val detailText = normalizeReverseGatewayLogLine(event, details)
                if (event.contains("failure") || event.contains("disconnect") || event.contains("error")) {
                    logSuppressedReverseGatewayError(
                        category = "reverse-connect",
                        signature = detailText,
                        cooldownMs = REVERSE_CONNECT_ERROR_COOLDOWN_MS
                    ) {
                        DaemonLog.warn("Daemon", "transport state $detailText")
                    }
                } else if (shouldLogReverseGatewayState(detailText)) {
                    when {
                        event.contains("connected") -> {
                            reverseWsReconnectSuccess.incrementAndGet()
                            DaemonLog.info("Daemon", "transport state $detailText")
                        }
                        event.contains("connecting") -> DaemonLog.debug("Daemon", "transport state $detailText")
                        event.contains("reconnect-requested") -> DaemonLog.info("Daemon", "transport state $detailText")
                        event.contains("stopped") || event.contains("started") -> DaemonLog.debug("Daemon", "transport state $detailText")
                    }
                } else {
                    lastReverseGatewaySuppressedCount++
                    if (lastReverseGatewaySuppressedCount % 10 == 0) {
                        DaemonLog.debug("Daemon", "transport state suppressed x$lastReverseGatewaySuppressedCount")
                    }
                }
            }
        ).also { it.start() }
        DaemonLog.info("Daemon", "transport runtime started")
    }

    private fun ensureReverseNetworkMonitoring() {
        stopReverseNetworkMonitoring()
        val manager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                requestReverseGatewayReconnect("network available")
            }

            override fun onLost(network: Network) {
                DaemonLog.debug("Daemon", "network lost: $network")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                requestReverseGatewayReconnect("internet restored")
            }
        }
        try {
            manager.registerNetworkCallback(request, callback)
            connectivityManager = manager
            networkCallback = callback
        } catch (_: Exception) {
            DaemonLog.warn("Daemon", "reverse network monitoring registration failed")
        }
    }

    private fun stopReverseNetworkMonitoring() {
        connectivityManager?.let { manager ->
            networkCallback?.let { callback ->
                try {
                    manager.unregisterNetworkCallback(callback)
                } catch (_: Exception) {
                    // no-op
                }
            }
        }
        connectivityManager = null
        networkCallback = null
    }

    private fun requestReverseGatewayReconnect(reason: String) {
        if (!running) return
        val runtime = transportRuntime ?: return
        val diagnostics = runtime.getDiagnostics()
        if (diagnostics.connected) {
            DaemonLog.debug("Daemon", "skip reverse gateway reconnect: already connected ($reason)")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastNetworkReconnectAtMs < 1_000L) return
        lastNetworkReconnectAtMs = now
        setReverseGatewayState("reconnect-requested", reason)
        reverseWsReconnectRequests.incrementAndGet()
        DaemonLog.debug("Daemon", "reverse gateway reconnect triggered by network event: $reason")
        runtime.requestReconnect()
    }

    private inline fun logSuppressedReverseGatewayError(
        category: String,
        signature: String,
        cooldownMs: Long,
        crossinline log: () -> Unit
    ) {
        if (shouldLogReverseGatewayError(category, signature, cooldownMs)) {
            log()
        }
    }

    private fun shouldLogReverseGatewayError(category: String, signature: String, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val key = "$category|$signature"
        val suppressed = reversePathErrorSuppressedCount[key] ?: 0
        val last = reversePathErrorLogAt[key] ?: 0L
        if (now - last < cooldownMs) {
            reversePathErrorSuppressedCount[key] = suppressed + 1
            return false
        }
        if (suppressed > 0) {
            DaemonLog.debug("Daemon", "reverse $category errors suppressed x$suppressed for $signature")
            reversePathErrorSuppressedCount.remove(key)
        }
        reversePathErrorLogAt[key] = now
        return true
    }

    private fun shouldLogReverseGatewayState(detailText: String): Boolean {
        val now = System.currentTimeMillis()
        val key = detailText.trim()
        if (key.isEmpty()) return true
        val sameKey = lastReverseGatewayLogKey == key
        val elapsed = now - lastReverseGatewayLogAtMs
        if (!sameKey || elapsed >= reverseGatewayLogCooldownMs) {
            lastReverseGatewayLogKey = key
            lastReverseGatewayLogAtMs = now
            lastReverseGatewaySuppressedCount = 0
            return true
        }
        return false
    }

    private fun setReverseGatewayState(state: String, detail: String? = null) {
        reverseGatewayState = state
        reverseGatewayStateDetail = detail
        reverseGatewayStateUpdatedAtMs = System.currentTimeMillis()
    }

    private fun isReverseGatewayEnabled(): Boolean {
        val config = activeReverseGatewayConfig
        return config != null && config.enabled && config.endpointUrl.isNotBlank() && config.userId.isNotBlank() && config.userKey.isNotBlank()
    }

    private fun currentEndpointConfig() = settings.toEndpointCoreConfig(activeReverseGatewayConfig)

    private fun extractReverseEndpoint(): String {
        return currentEndpointConfig().endpointUrl.ifBlank { currentEndpointConfig().dispatchUrl }
    }

    fun getConnectionSnapshot(): DaemonConnectionSnapshot {
        val now = System.currentTimeMillis()
        val transportDiagnostics = transportRuntime?.getDiagnostics()
        val transportMetrics = transportRuntime?.getMetrics()
        return DaemonConnectionSnapshot(
            daemonRunning = running,
            localHttpServer = httpServerHttp != null,
            localHttpsServer = httpServerHttps != null,
            reverseGatewayConfigured = transportDiagnostics?.configured ?: reverseGatewayConfigured,
            reverseGatewayEnabled = transportDiagnostics?.enabled ?: isReverseGatewayEnabled(),
            reverseGatewayConnected = transportDiagnostics?.connected ?: false,
            reverseGatewayState = transportDiagnostics?.state ?: reverseGatewayState,
            reverseGatewayStateDetail = transportDiagnostics?.stateDetail ?: reverseGatewayStateDetail,
            reverseGatewayStateUpdatedAtMs = if (reverseGatewayStateUpdatedAtMs > 0L) reverseGatewayStateUpdatedAtMs else now,
            transportEndpoint = transportDiagnostics?.endpoint ?: extractReverseEndpoint(),
            reverseRelayAttempts = transportMetrics?.relayAttempts ?: reverseRelayAttempts.get(),
            reverseRelaySuccess = transportMetrics?.relaySuccess ?: reverseRelaySuccess.get(),
            reverseRelayFallback = transportMetrics?.relayFallback ?: reverseRelayFallback.get(),
            reverseRelayFailures = transportMetrics?.relayFailures ?: reverseRelayFailures.get(),
            reverseHttpAttempts = transportMetrics?.httpAttempts ?: reverseHttpAttempts.get(),
            reverseHttpSuccesses = transportMetrics?.httpSuccesses ?: reverseHttpSuccesses.get(),
            reverseHttpFailures = transportMetrics?.httpFailures ?: reverseHttpFailures.get(),
            reverseGatewayCandidateState = transportDiagnostics?.candidateState,
            reverseGatewayActiveCandidate = transportDiagnostics?.activeCandidate,
            reverseGatewayCandidateList = transportDiagnostics?.candidateList,
            reverseGatewayLastError = transportDiagnostics?.lastError
        )
    }

    private fun logReverseRoutingMetricsIfNeeded() {
        val eventCount = reverseRelayAttempts.get() + reverseHttpAttempts.get()
        if (eventCount % 10L != 1L) return
        DaemonLog.info(
            "Daemon",
            "clipboard routing metrics wsAttempts=${reverseRelayAttempts.get()} wsSuccess=${reverseRelaySuccess.get()} wsFallback=${reverseRelayFallback.get()} wsFail=${reverseRelayFailures.get()} httpAttempts=${reverseHttpAttempts.get()} httpSuccess=${reverseHttpSuccesses.get()} httpFail=${reverseHttpFailures.get()} reconnectReq=${reverseWsReconnectRequests.get()} reconnectOk=${reverseWsReconnectSuccess.get()}"
        )
    }

    private fun startServers(syncCallbacks: SyncCallbacks) {
        if (!settings.enableLocalServer) {
            DaemonLog.info("Daemon", "local servers are disabled in settings")
            return
        }

        val commonOptions = HttpServerOptions(
            port = settings.listenPortHttp,
            authToken = settings.authToken,
            tls = null,
            context = application,
            setClipboardTextSync = syncCallbacks.setClipboardTextSync,
            setClipboardText = syncCallbacks.setClipboardText,
            applyClipboardEnvelope = syncCallbacks.applyClipboardEnvelope,
            readClipboardEnvelope = syncCallbacks.readClipboardEnvelope,
            dispatch = syncCallbacks.dispatch,
            sendSms = syncCallbacks.sendSms,
            postClipboard = syncCallbacks.postClipboard,
            postClipboardEnvelope = syncCallbacks.postClipboardEnvelope,
            listSms = syncCallbacks.listSms,
            listNotifications = syncCallbacks.listNotifications,
            listContacts = syncCallbacks.listContacts,
            listDestinations = { settings.destinations },
            speakNotificationText = syncCallbacks.speakNotificationText,
            getConfigContent = { filename ->
                val base = settings.configPath.trim().removePrefix("fs:").removePrefix("file:")
                if (base.isBlank()) null
                else {
                    val file = ConfigResolver.resolveFile(base)
                    val target = if (file.isDirectory) java.io.File(file, filename) else if (file.name == filename) file else null
                    if (target?.exists() == true && target.isFile) target.readText() else null
                }
            }
        )

        val httpsOptions = commonOptions.copy(
            port = settings.listenPortHttps,
            tls = TlsConfig(
                enabled = settings.tlsEnabled,
                keystoreAssetPath = settings.tlsKeystoreAssetPath,
                keystoreType = settings.tlsKeystoreType,
                keystorePassword = settings.tlsKeystorePassword
            )
        )

        httpServerHttp = startLocalServer(commonOptions, "HTTP")

        httpServerHttps = if (settings.tlsEnabled) {
            if (settings.listenPortHttps == settings.listenPortHttp) {
                DaemonLog.warn(
                    "Daemon",
                    "TLS port matches HTTP port (${settings.listenPortHttp}); skipping HTTPS listener to avoid bind conflict"
                )
                null
            } else {
                startLocalServer(httpsOptions, "HTTPS")
            }
        } else {
            null
        }

        if (httpServerHttp == null && httpServerHttps == null) {
            throw IllegalStateException("Both HTTP and HTTPS local servers failed to start")
        }

        DaemonLog.info(
            "Daemon",
            "http servers started (http=${httpServerHttp != null}, https=${httpServerHttps != null})"
        )
    }

    private fun startLocalServer(options: HttpServerOptions, label: String): LocalHttpServer? {
        return try {
            val server = LocalHttpServer(options)
            server.start()
            server
        } catch (e: Exception) {
            DaemonLog.warn(
                "Daemon",
                "$label local server failed on port=${options.port}",
                e
            )
            null
        }
    }

    private fun startClipboardSync() {
        if (!settings.clipboardSync) return
        val watcher = ClipboardSyncWatcher(application) { text ->
            scope.launch {
                handleLocalClipboardObservation(text)
            }
        }
        clipboardWatcher = watcher
        watcher.start()
    }

    private fun startClipboardPollingFallback() {
        if (!settings.clipboardSync) return
        clipboardFallbackTimer?.cancel()
        // Repeated background clipboard reads trigger Android privacy denials on
        // modern devices. Listener-driven updates are the primary mechanism now.
        clipboardFallbackTimer = null
    }

    fun forceClipboardSyncNow(providedText: String? = null) {
        scope.launch {
            val envelope = providedText?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { ClipboardEnvelope(text = it, source = "manual-force") }
                ?: readClipboardEnvelope()
            if (envelope == null || !envelope.hasContent()) {
                DaemonLog.warn("Daemon", "manual clipboard sync skipped: clipboard is empty")
                return@launch
            }
            val decision = clipboardRuntime.recordLocalRead(envelope)
            if (!decision.accepted) return@launch
            HistoryRepository.recordClipboard(
                envelope = envelope,
                sourceId = currentLocalHistorySourceId(),
                origin = HistoryOrigin.LOCAL
            )
            postClipboardToPeers(envelope)
        }
    }

    fun forceClipboardEnvelopeSyncNow(envelope: ClipboardEnvelope) {
        scope.launch {
            if (!envelope.hasContent()) return@launch
            val decision = clipboardRuntime.recordLocalRead(envelope)
            if (!decision.accepted) return@launch
            HistoryRepository.recordClipboard(
                envelope = envelope,
                sourceId = currentLocalHistorySourceId(),
                origin = HistoryOrigin.LOCAL
            )
            postClipboardToPeers(envelope)
        }
    }

    fun snapshotClipboardEnvelope(): ClipboardEnvelope? = readClipboardEnvelope()

    fun requestClipboardHistory(target: String): Boolean {
        return requestRemoteHistoryQuery(
            target = target,
            channel = HistoryChannel.CLIPBOARD,
            what = "clipboard:get",
            payload = mapOf("request" to "history")
        )
    }

    fun requestSmsHistory(target: String, limit: Int = 50): Boolean {
        return requestRemoteHistoryQuery(
            target = target,
            channel = HistoryChannel.SMS,
            what = "sms:list",
            payload = mapOf("limit" to limit.coerceIn(1, 200))
        )
    }

    fun requestNotificationHistory(target: String, limit: Int = 50): Boolean {
        return requestRemoteHistoryQuery(
            target = target,
            channel = HistoryChannel.NOTIFICATIONS,
            what = "notifications:list",
            payload = mapOf("limit" to limit.coerceIn(1, 200))
        )
    }

    private fun startPeriodicSync() {
        if (!settings.contactsSync && !settings.smsSync) return
        syncTimer?.cancel()
        syncTimer = scope.launch {
            tickPeriodic()
            while (isActive) {
                delay((maxOf(10, settings.syncIntervalSec) * 1000L).coerceAtLeast(10_000))
                tickPeriodic()
            }
        }
    }

    private suspend fun tickPeriodic() {
        val activity = activityProvider()
        if (!settings.contactsSync && !settings.smsSync) return
        try {
            if (activity != null && settings.contactsSync) {
                val contacts = readContacts(activity)
                DaemonLog.info("Daemon", "synced contacts ${contacts.size}")
            }
            if (activity != null && settings.smsSync) {
                val sms = readSmsInbox(activity)
                DaemonLog.info("Daemon", "synced sms ${sms.size}")
            }
        } catch (e: Exception) {
            DaemonLog.warn("Daemon", "sync tick failed", e)
        }
    }

    private fun createSyncCallbacks(activity: Activity?): SyncCallbacks {
        return SyncCallbacks(
            setClipboardTextSync = { text: String ->
                clipboardTextFallback.setTextSilentlySync(text)
            },
            setClipboardText = { text: String ->
                clipboardTextFallback.setTextSilently(text)
            },
            applyClipboardEnvelope = { envelope: ClipboardEnvelope, uuid: String?, sourceId: String?, targetId: String? ->
                applyClipboardEnvelope(envelope, uuid, sourceId, targetId)
            },
            readClipboardEnvelope = {
                readClipboardEnvelope()
            },
            dispatch = { requests: List<DispatchRequest> ->
                dispatchHttpRequests(requests)
            },
            sendSms = { number: String, content: String ->
                if (activity == null) throw IllegalStateException("activity is unavailable")
                sendSmsAndroid(activity, number, content)
            },
            postClipboard = { text: String, targets: List<String> ->
                postClipboardToPeers(ClipboardEnvelope(text = text, source = "local-http"), targets)
            },
            postClipboardEnvelope = { envelope: ClipboardEnvelope, targets: List<String> ->
                postClipboardToPeers(envelope, targets)
            },
            listSms = { limit: Int ->
                val activeActivity = activity
                if (activeActivity == null) {
                    emptyList()
                } else {
                    readSmsInbox(activeActivity, limit)
                }
            },
            listNotifications = { limit: Int ->
                NotificationEventStore.snapshot(limit)
            },
            listContacts = { limit: Int ->
                val activeActivity = activity
                if (activeActivity == null) {
                    emptyList()
                } else {
                    readContacts(activeActivity).take(limit.coerceAtLeast(1))
                }
            },
            speakNotificationText = { text: String ->
                NotificationSpeaker.speak(application, text)
            },
            sendPacket = { packet ->
                transportRuntime?.sendPacket(packet) == true
            }
        )
    }

    private fun setClipboardBestEffort(text: String) {
        try {
            clipboardTextFallback.setTextSilentlySync(text)
        } catch (e: Exception) {
            DaemonLog.warn("Daemon", "setClipboardBestEffort failed", e)
        }
    }

    private fun isProtocolClipboardNoise(text: String): Boolean {
        val raw = text.trim()
        if (raw.isBlank()) return false
        if (raw.startsWith("{text=") && raw.endsWith("}")) return true
        val parsed = runCatching {
            clipboardNoiseGson.fromJson(raw, Map::class.java) as? Map<*, *>
        }.getOrNull() ?: return false
        val keys = parsed.keys.mapNotNull { it?.toString()?.trim()?.lowercase() }.toSet()
        if (keys.isEmpty()) return false
        val protocolKeys = setOf("op", "what", "nodes", "byid", "from", "uuid", "event", "payload", "result", "error")
        val directText = (parsed["text"] as? String)?.trim().orEmpty()
        val directContent = (parsed["content"] as? String)?.trim().orEmpty()
        val directBody = (parsed["body"] as? String)?.trim().orEmpty()
        val hasReadableText = directText.isNotBlank() || directContent.isNotBlank() || directBody.isNotBlank()
        val clipboardOnlyKeys = setOf("text", "content", "body", "mime", "mimetype", "type", "source", "uuid", "id")
        val onlyClipboardEnvelopeKeys = keys.all { clipboardOnlyKeys.contains(it) }
        if (onlyClipboardEnvelopeKeys && !hasReadableText) return true
        return keys.any { protocolKeys.contains(it) } && !hasReadableText
    }

    private fun localIdentityAliases(): Set<String> {
        val config = currentEndpointConfig()
        val candidates = listOf(
            currentLocalHistorySourceId(),
            config.userId,
            config.deviceId,
            settings.hubClientId,
            settings.deviceId
        )
        return candidates
            .flatMap { EndpointIdentity.aliases(it).toList() }
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun isSelfSourceClipboard(sourceId: String?): Boolean {
        val normalized = sourceId?.trim().orEmpty()
        if (normalized.isBlank()) return false
        val sourceAliases = EndpointIdentity.aliases(normalized)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        if (sourceAliases.isEmpty()) return false
        val localAliases = localIdentityAliases()
        return sourceAliases.any { localAliases.contains(it) }
    }

    private suspend fun handleLocalClipboardObservation(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (isProtocolClipboardNoise(trimmed)) {
            DaemonLog.debug("Daemon", "skip local clipboard reason=protocol-noise")
            return
        }
        val envelope = ClipboardEnvelope(text = trimmed, source = "local-clipboard")
        val decision = clipboardRuntime.recordLocalRead(envelope)
        if (!decision.accepted) {
            DaemonLog.debug("Daemon", "skip duplicate clipboard read reason=${decision.reason}")
            return
        }
        HistoryRepository.recordClipboard(
            envelope = envelope,
            sourceId = currentLocalHistorySourceId(),
            origin = HistoryOrigin.LOCAL
        )
        postClipboardToPeers(envelope)
    }

    private suspend fun applyClipboardEnvelope(
        envelope: ClipboardEnvelope,
        uuid: String? = null,
        sourceId: String? = null,
        targetId: String? = null
    ): Boolean {
        if (!envelope.hasContent()) {
            DaemonLog.debug("Daemon", "skip inbound clipboard reason=empty-envelope")
            return false
        }
        if (isSelfSourceClipboard(sourceId)) {
            DaemonLog.debug("Daemon", "skip inbound clipboard reason=self-source-loop")
            return false
        }
        val inbound = clipboardRuntime.evaluateInbound(envelope, uuid, sourceId, targetId)
        if (!inbound.accepted) {
            DaemonLog.debug("Daemon", "skip inbound clipboard reason=${inbound.reason}")
            return false
        }
        HistoryRepository.recordClipboard(
            envelope = envelope,
            sourceId = sourceId,
            targetId = targetId,
            origin = HistoryOrigin.INBOUND
        )
        val writeDecision = clipboardRuntime.allowPlatformWrite(envelope, uuid, sourceId, targetId)
        if (!writeDecision.accepted) {
            DaemonLog.debug("Daemon", "skip clipboard write reason=${writeDecision.reason}")
            return false
        }
        val bestText = envelope.bestText()
        if (!bestText.isNullOrBlank()) {
            val normalizedText = bestText.trim()
            try {
                clipboardTextFallback.setTextSilently(normalizedText)
            } catch (error: Exception) {
                DaemonLog.warn(
                    "Daemon",
                    "async clipboard write failed; retrying sync source=${sourceId ?: "-"} target=${targetId ?: "-"} uuid=${uuid ?: "-"} text=${previewClipboardText(normalizedText)}",
                    error
                )
                try {
                    clipboardTextFallback.setTextSilentlySync(normalizedText)
                } catch (retryError: Exception) {
                    DaemonLog.warn(
                        "Daemon",
                        "sync clipboard retry failed source=${sourceId ?: "-"} target=${targetId ?: "-"} uuid=${uuid ?: "-"} text=${previewClipboardText(normalizedText)}",
                        retryError
                    )
                    return false
                }
            }
            DaemonLog.info(
                "Daemon",
                "applied inbound clipboard source=${sourceId ?: "-"} target=${targetId ?: "-"} uuid=${uuid ?: "-"} text=${previewClipboardText(normalizedText)}"
            )
        } else {
            DaemonLog.debug(
                "Daemon",
                "accepted inbound clipboard without text source=${sourceId ?: "-"} target=${targetId ?: "-"} uuid=${uuid ?: "-"}"
            )
        }
        return true
    }

    private fun previewClipboardText(value: String): String {
        val normalized = value.replace("\n", "\\n").replace("\r", "\\r")
        return if (normalized.length <= 80) normalized else normalized.take(80) + "..."
    }

    private fun readClipboardEnvelope(): ClipboardEnvelope? {
        val currentText = clipboardTextFallback.lastSeenText().trim().ifBlank {
            clipboardTextFallback.readCurrentText().trim()
        }
        if (currentText.isNotBlank()) {
            return ClipboardEnvelope(text = currentText, source = "read-clipboard")
        }
        return clipboardRuntime.cache()
    }

    private fun requestRemoteHistoryQuery(
        target: String,
        channel: HistoryChannel,
        what: String,
        payload: Map<String, Any?>
    ): Boolean {
        val runtime = transportRuntime ?: return false
        val normalizedTarget = EndpointIdentity.bestRouteTarget(target).ifBlank { target.trim() }
        if (normalizedTarget.isBlank()) return false
        val uuid = UUID.randomUUID().toString()
        HistoryRepository.registerPendingQuery(uuid, channel, normalizedTarget)
        val sent = runtime.sendPacket(
            ServerV2Packet(
                op = "ask",
                what = what,
                type = what,
                purpose = when {
                    what.startsWith("clipboard:") -> "clipboard"
                    what.startsWith("sms:") -> "sms"
                    what.startsWith("notifications:") || what.startsWith("notification:") -> "generic"
                    what.startsWith("contacts:") || what.startsWith("contact:") -> "contact"
                    else -> "generic"
                },
                protocol = "socket",
                payload = payload,
                nodes = listOf(normalizedTarget),
                destinations = listOf(normalizedTarget),
                uuid = uuid
            )
        )
        if (!sent) {
            HistoryRepository.clearPendingQuery(uuid)
        }
        return sent
    }

    private fun currentLocalHistorySourceId(): String {
        val endpointConfig = currentEndpointConfig()
        return EndpointIdentity.bestRouteTarget(
            endpointConfig.userId.ifBlank {
                settings.hubClientId.ifBlank { settings.authToken.ifBlank { settings.deviceId } }
            }
        ).ifBlank { "local-device" }
    }

    private fun startClipboardExpiryCleaner() {
        if (!settings.clipboardSync) return
        clipboardExpiryTimer?.cancel()
        clipboardExpiryTimer = scope.launch {
            while (isActive) {
                delay(1_000L)
                val currentText = clipboardTextFallback.lastSeenText().trim()
                val currentEnvelope = currentText.takeIf { it.isNotBlank() }?.let {
                    ClipboardEnvelope(text = it, source = "clipboard-expiry-check")
                }
                if (clipboardRuntime.shouldClearExpiredRemote(currentEnvelope)) {
                    setClipboardBestEffort("")
                    DaemonLog.info("Daemon", "expired remote clipboard entry cleared")
                }
            }
        }
    }

    private suspend fun postClipboardToPeers(envelope: ClipboardEnvelope, requestedTargets: List<String>? = null) {
        if (!envelope.hasContent()) return
        clipboardRuntime.recordOutbound(
            payload = envelope,
            uuid = envelope.uuid,
            sourceId = "android",
            targetId = requestedTargets?.joinToString(",")
        )
        val hubItems = buildHubDispatchItems(envelope, requestedTargets)
        if (hubItems.isEmpty()) return
        val pendingItems = trySendClipboardViaReverseGateway(envelope, hubItems)
        if (pendingItems.isEmpty()) return
        val urls = pendingItems.mapNotNull { it.directFallbackUrl }
        val payloadJson = ClipboardEnvelopeCodec.canonicalJson(envelope.toMap())

        val headers = buildMap {
            put("Content-Type", "application/json; charset=utf-8")
            val endpointConfig = currentEndpointConfig()
            val authToken = listOf(
                settings.authToken,
                settings.hubToken,
                endpointConfig.userKey
            ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
            if (authToken.isNotBlank()) {
                put("x-auth-token", authToken)
            }
        }

        val endpointConfig = currentEndpointConfig()
        val hub = endpointConfig.dispatchUrl.ifBlank { normalizeHubDispatchUrl(settings.hubDispatchUrl).orEmpty() }
        if (!hub.isNullOrBlank()) {
            val fallbackUrls = urls.toSet()
            val hubDispatched = runCatching {
                transportRuntime?.dispatchClipboardRequests(pendingItems) == true
            }.getOrElse { error ->
                DaemonLog.warn("Daemon", "hub dispatch error", error)
                false
            }
            if (hubDispatched) {
                DaemonLog.info("Daemon", "hub dispatch ok")
                reverseWsReconnectSuccess.incrementAndGet()
                return
            }
            if (!hubDispatched && fallbackUrls.isNotEmpty()) {
                fallbackUrls.forEach { url ->
                    scope.launch {
                        try {
                            reverseHttpAttempts.incrementAndGet()
                            val directResult = postText(url, payloadJson, headers, endpointConfig.allowInsecureTls, 10_000)
                            if (directResult.ok) {
                                reverseHttpSuccesses.incrementAndGet()
                            } else {
                                reverseHttpFailures.incrementAndGet()
                            }
                            if (!directResult.ok) {
                                DaemonLog.warn("Daemon", "hub fallback dispatch failed ${directResult.status} $url")
                            }
                            logReverseRoutingMetricsIfNeeded()
                        } catch (e: Exception) {
                            DaemonLog.warn("Daemon", "hub fallback dispatch error $url", e)
                            reverseHttpFailures.incrementAndGet()
                            logReverseRoutingMetricsIfNeeded()
                        }
                    }
                }
            }
            return
        }

        urls.forEach { url ->
            scope.launch {
                try {
                    reverseHttpAttempts.incrementAndGet()
                    val result = postText(url, payloadJson, headers, endpointConfig.allowInsecureTls, 10_000)
                    if (result.ok) {
                        reverseHttpSuccesses.incrementAndGet()
                    } else {
                        reverseHttpFailures.incrementAndGet()
                    }
                    if (!result.ok) {
                        DaemonLog.warn("Daemon", "clipboard broadcast failed ${result.status} $url")
                    }
                    logReverseRoutingMetricsIfNeeded()
                } catch (e: Exception) {
                    DaemonLog.warn("Daemon", "clipboard broadcast error $url", e)
                    reverseHttpFailures.incrementAndGet()
                    logReverseRoutingMetricsIfNeeded()
                }
            }
        }
    }

    private fun extractClipboardTarget(request: Map<String, Any>): String? {
        val directTarget = request["deviceId"] ?: request["targetId"] ?: request["target"] ?: request["to"]
        val normalized = EndpointIdentity.bestRouteTarget(directTarget?.toString())
        if (normalized.isBlank() || EndpointIdentity.isBroadcast(normalized)) return null
        return normalized
    }

    private fun buildClipboardRelayPayload(envelope: ClipboardEnvelope, request: Map<String, Any>): Map<String, Any?> = buildMap {
        val normalizedTarget = extractClipboardTarget(request)
        putAll(envelope.toMap())
        envelope.bestText()?.let { put("text", it) }
        put("action", "clipboard")
        put("type", "clipboard")
        normalizedTarget?.let {
            put("deviceId", it)
            put("targetId", it)
            put("target", it)
            put("targetDeviceId", it)
            put("to", it)
            put("targetAliases", EndpointIdentity.aliases(it).toList())
        }
    }

    private fun trySendClipboardViaReverseGateway(envelope: ClipboardEnvelope, items: List<HubDispatchPayloadItem>): List<HubDispatchPayloadItem> {
        val endpointConfig = currentEndpointConfig()
        val sender = listOf(
            activeReverseGatewayConfig?.userId,
            endpointConfig.userId,
            settings.authToken,
            endpointConfig.deviceId
        ).firstOrNull { !it.isNullOrBlank() } ?: "android"
        val pending = transportRuntime?.sendClipboardViaSockets(
            envelope = envelope,
            items = items,
            resolveTarget = ::extractClipboardTarget,
            senderId = sender
        ) ?: items
        logReverseRoutingMetricsIfNeeded()
        return pending
    }

    private fun handleShareTarget(activity: Activity?) {
        if (!settings.shareTarget || activity == null) return
        val intent = activity.intent ?: return
        val action = intent.action
        val type = intent.type
        val isSend = action == Intent.ACTION_SEND
        val isProcessText = action == Intent.ACTION_PROCESS_TEXT && type == "text/plain"
        if (!isSend && !isProcessText) return
        val envelope = ClipboardEnvelopeCodec.fromIntent(application, intent, source = "daemon-share-target")
        if (!envelope.hasContent()) return
        scope.launch {
            envelope.bestText()?.let { bestText ->
                if (clipboardWatcher != null) {
                    clipboardWatcher?.setTextSilently(bestText)
                } else {
                    setClipboardBestEffort(bestText)
                }
            }
            forceClipboardEnvelopeSyncNow(envelope)
            DaemonLog.info("Daemon", "handled share/process-text intent")
        }
    }

    private fun extractIntentText(intent: Intent): String {
        return try {
            intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: intent.getStringExtra("android.intent.extra.PROCESS_TEXT")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: run {
                    val cd = intent.clipData ?: return ""
                    if (cd.itemCount > 0) {
                        cd.getItemAt(0).coerceToText(application).toString().trim()
                    } else {
                        ""
                    }
                }
        } catch (_: Exception) {
            ""
        }
    }

    private fun buildHubDispatchItems(envelope: ClipboardEnvelope, requestedTargets: List<String>? = null): List<HubDispatchPayloadItem> {
        val associations = PeerAssociationStore.load(application)
        val out = mutableListOf<HubDispatchPayloadItem>()
        val resolvedTargets = resolveClipboardDestinations(requestedTargets)
        val requestBody = ClipboardEnvelopeCodec.canonicalJson(envelope.toMap())
        for (raw in resolvedTargets) {
            val target = raw.trim()
            if (target.isBlank()) continue

            val lower = target.lowercase()
            val isDeviceTarget = lower.startsWith("device:") || lower.startsWith("local-device:") || lower.startsWith("id:") || lower.startsWith("l-") || lower.startsWith("p-") || (!lower.contains(".") && !lower.contains(":") && !lower.contains("/"))
            val normalizedTarget = normalizeDestinationHost(target).trim()
            if (normalizedTarget.isBlank()) continue
            val peerAddressHint = normalizedTarget.lowercase().replace(Regex("^[a-z]+-", RegexOption.IGNORE_CASE), "")
            val dispatchDeviceId = resolveDispatchDeviceId(normalizedTarget)
            val maybeCachedUrl = associations[normalizedTarget.lowercase()]
                ?: associations[dispatchDeviceId.lowercase()]
                ?: associations[peerAddressHint.lowercase()]
            val hasAddressHint = isAddressLikePeerTarget(normalizedTarget) || isAddressLikePeerTarget(peerAddressHint)
            val directCandidate = if (isDeviceTarget && isAddressLikePeerTarget(normalizedTarget)) {
                normalizeDestinationUrl(target, "/clipboard")
            } else if (isDeviceTarget && isAddressLikePeerTarget(peerAddressHint)) {
                normalizeDestinationUrl(peerAddressHint, "/clipboard")
            } else {
                null
            }
            if (directCandidate != null && directCandidate.isNotBlank()) {
                PeerAssociationStore.save(application, normalizedTarget, directCandidate)
                if (peerAddressHint.isNotBlank() && peerAddressHint != normalizedTarget) {
                    PeerAssociationStore.save(application, peerAddressHint, directCandidate)
                }
                if (dispatchDeviceId != normalizedTarget) {
                    PeerAssociationStore.save(application, dispatchDeviceId, directCandidate)
                }
            }

            if (isDeviceTarget) {
                val request = mutableMapOf<String, Any>(
                    "deviceId" to dispatchDeviceId,
                    "body" to requestBody,
                    "method" to "POST",
                    "headers" to buildMap {
                        put("Content-Type", "application/json; charset=utf-8")
                        if (settings.authToken.isNotBlank()) put("x-auth-token", settings.authToken)
                    }
                )
                if (!maybeCachedUrl.isNullOrBlank()) {
                    val validatedCacheUrl = maybeCachedUrl.trim()
                    if (validatedCacheUrl.isNotBlank() && isCachedUrlForPeer(validatedCacheUrl, normalizedTarget, dispatchDeviceId, peerAddressHint)) {
                        request["url"] = validatedCacheUrl
                        if (validatedCacheUrl.startsWith("http://")) {
                            request["unencrypted"] = true
                        }
                    }
                }
                if (hasAddressHint && request["url"] == null && directCandidate != null) {
                    request["url"] = directCandidate
                    if (directCandidate.startsWith("http://")) {
                        request["unencrypted"] = true
                    }
                }
                out.add(HubDispatchPayloadItem(request, (request["url"] as? String)))
                continue
            }

            val directUrl = normalizeDestinationUrl(target, "/clipboard")
            if (directUrl.isNullOrBlank()) continue
            out.add(HubDispatchPayloadItem(buildMap {
                put("url", directUrl)
                put("body", requestBody)
                put("method", "POST")
                put("headers", buildMap {
                    put("Content-Type", "application/json; charset=utf-8")
                    if (settings.authToken.isNotBlank()) put("x-auth-token", settings.authToken)
                })
                if (directUrl.startsWith("http://")) put("unencrypted", true)
            }, directUrl))
        }
        return out
    }

    private fun resolveClipboardDestinations(requestedTargets: List<String>?): List<String> {
        val explicitTargets = requestedTargets
            ?.flatMap { splitClipboardTargets(it) }
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        if (explicitTargets.isEmpty()) {
            return settings.destinations
        }

        val hasBroadcast = explicitTargets.any { target ->
            val normalized = target.lowercase()
            normalized == "broadcast" || normalized == "all" || normalized == "*"
        }
        if (hasBroadcast) {
            return settings.destinations
        }

        return explicitTargets
    }

    private fun splitClipboardTargets(value: String): List<String> {
        return value
            .split("[;,]".toRegex())
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun isAddressLikePeerTarget(target: String): Boolean {
        return ADDRESS_LIKE_PEER_TARGET.containsMatchIn(target)
    }

    private fun isCachedUrlForPeer(
        cachedUrl: String,
        normalizedTarget: String,
        dispatchDeviceId: String,
        peerAddressHint: String
    ): Boolean {
        val parsed = parseHostFromUrl(cachedUrl)
        if (parsed.isBlank()) return false
        val hostWithPort = parsed
        val hostOnly = parsed.substringBefore(":")
        val candidates = mutableSetOf<String>()
        addPeerAliasCandidates(normalizedTarget, candidates)
        addPeerAliasCandidates(dispatchDeviceId, candidates)
        addPeerAliasCandidates(peerAddressHint, candidates)
        return candidates.contains(hostWithPort) || candidates.contains(hostOnly)
    }

    private fun parseHostFromUrl(raw: String): String {
        return try {
            val uri = URI(raw)
            val host = uri.host?.trim()?.lowercase() ?: return ""
            val port = uri.port
            if (port > 0) "${host}:${port}" else host
        } catch (_: Exception) {
            ""
        }
    }

    private fun addPeerAliasCandidates(raw: String, out: MutableSet<String>) {
        val aliases = EndpointIdentity.aliases(raw)
        if (aliases.isEmpty()) return
        out.addAll(aliases)
        aliases.forEach { alias ->
            val canonical = EndpointIdentity.canonical(alias)
            if (canonical.isNotBlank()) {
                out.add(canonical)
                out.add(canonical.substringBefore(":"))
            }
        }
    }

    private fun resolveDispatchDeviceId(rawDeviceId: String): String {
        val trimmed = rawDeviceId.trim()
        if (!trimmed.startsWith("ns-")) return trimmed
        if (trimmed != settings.deviceId) return trimmed
        return currentEndpointConfig().userId.ifBlank { settings.authToken.ifBlank { settings.deviceId } }
    }

    data class DaemonConnectionSnapshot(
        val daemonRunning: Boolean,
        val localHttpServer: Boolean,
        val localHttpsServer: Boolean,
        val reverseGatewayConfigured: Boolean,
        val reverseGatewayEnabled: Boolean,
        val reverseGatewayConnected: Boolean,
        val reverseGatewayState: String,
        val reverseGatewayStateDetail: String?,
        val reverseGatewayStateUpdatedAtMs: Long,
        val transportEndpoint: String,
        val reverseRelayAttempts: Long,
        val reverseRelaySuccess: Long,
        val reverseRelayFallback: Long,
        val reverseRelayFailures: Long,
        val reverseHttpAttempts: Long,
        val reverseHttpSuccesses: Long,
        val reverseHttpFailures: Long,
        val reverseGatewayCandidateState: String?,
        val reverseGatewayActiveCandidate: String?,
        val reverseGatewayCandidateList: String?,
        val reverseGatewayLastError: String?
    ) {
        companion object {
            fun stopped(): DaemonConnectionSnapshot = DaemonConnectionSnapshot(
                daemonRunning = false,
                localHttpServer = false,
                localHttpsServer = false,
                reverseGatewayConfigured = false,
                reverseGatewayEnabled = false,
                reverseGatewayConnected = false,
                reverseGatewayState = "stopped",
                reverseGatewayStateDetail = "daemon inactive",
                reverseGatewayStateUpdatedAtMs = System.currentTimeMillis(),
                transportEndpoint = "",
                reverseRelayAttempts = 0,
                reverseRelaySuccess = 0,
                reverseRelayFallback = 0,
                reverseRelayFailures = 0,
                reverseHttpAttempts = 0,
                reverseHttpSuccesses = 0,
                reverseHttpFailures = 0,
                reverseGatewayCandidateState = null,
                reverseGatewayActiveCandidate = null,
                reverseGatewayCandidateList = null,
                reverseGatewayLastError = null
            )

            fun inactiveConfigured(
                endpoint: String,
                configured: Boolean,
                enabled: Boolean,
                stateDetail: String,
                candidateList: String? = null
            ): DaemonConnectionSnapshot = DaemonConnectionSnapshot(
                daemonRunning = false,
                localHttpServer = false,
                localHttpsServer = false,
                reverseGatewayConfigured = configured,
                reverseGatewayEnabled = enabled,
                reverseGatewayConnected = false,
                reverseGatewayState = "inactive",
                reverseGatewayStateDetail = stateDetail,
                reverseGatewayStateUpdatedAtMs = System.currentTimeMillis(),
                transportEndpoint = endpoint,
                reverseRelayAttempts = 0,
                reverseRelaySuccess = 0,
                reverseRelayFallback = 0,
                reverseRelayFailures = 0,
                reverseHttpAttempts = 0,
                reverseHttpSuccesses = 0,
                reverseHttpFailures = 0,
                reverseGatewayCandidateState = if (candidateList.isNullOrBlank()) null else "0/0",
                reverseGatewayActiveCandidate = null,
                reverseGatewayCandidateList = candidateList,
                reverseGatewayLastError = null
            )
        }

        fun asStatusLines(): List<String> {
            val endpointLabel = transportEndpoint.ifBlank { "not configured" }
            val gatewayState = if (reverseGatewayStateDetail.isNullOrBlank()) reverseGatewayState else "$reverseGatewayState (${reverseGatewayStateDetail})"
            val candidateState = reverseGatewayCandidateState ?: "not-initialized"
            val activeCandidate = reverseGatewayActiveCandidate ?: "not-initialized"
            val candidateList = reverseGatewayCandidateList ?: "not-initialized"
            val lastError = reverseGatewayLastError
                ?.takeIf { it.isNotBlank() }
                ?: "none"
            return listOf(
                "Daemon: ${if (daemonRunning) "running" else "stopped"}",
                "Local HTTP: ${if (localHttpServer) "up" else "off"} / Local HTTPS: ${if (localHttpsServer) "up" else "off"}",
                "Reverse gateway: ${if (reverseGatewayConfigured) "configured" else "not configured"} | connected=${reverseGatewayConnected} | state=$gatewayState | endpoint=$endpointLabel",
                "ws-state: candidate=$candidateState | active=$activeCandidate | list=$candidateList",
                "ws-state: last_error=$lastError",
                "Relay: ws=$reverseRelayAttempts/$reverseRelaySuccess/$reverseRelayFallback/$reverseRelayFailures (ok/success/fallback/fail), http=$reverseHttpAttempts/$reverseHttpSuccesses/$reverseHttpFailures"
            )
        }
    }

    private fun normalizeReverseGatewayLogLine(event: String, details: String?): String {
        val normalizedDetails = if (details.isNullOrBlank()) {
            "event=$event"
        } else if (details.contains("event=")) {
            details
        } else {
            "event=$event $details"
        }
        return "[ws-state] $normalizedDetails"
    }

data class SyncCallbacks(
        val setClipboardTextSync: (String) -> Unit,
        val setClipboardText: suspend (String) -> Unit,
        val applyClipboardEnvelope: suspend (ClipboardEnvelope, String?, String?, String?) -> Boolean,
        val readClipboardEnvelope: suspend () -> ClipboardEnvelope?,
        val dispatch: suspend (List<DispatchRequest>) -> List<DispatchResult>,
        val sendSms: suspend (String, String) -> Unit,
        val postClipboard: suspend (String, List<String>) -> Unit,
        val postClipboardEnvelope: suspend (ClipboardEnvelope, List<String>) -> Unit,
        val listSms: suspend (Int) -> List<SmsItem>,
        val listNotifications: suspend (Int) -> List<NotificationEvent>,
        val listContacts: suspend (Int) -> List<ContactItem>,
        val speakNotificationText: suspend (String) -> Unit,
        val sendPacket: (ServerV2Packet) -> Boolean
    )
}

private val ADDRESS_LIKE_PEER_TARGET = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?$")
