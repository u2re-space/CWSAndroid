package space.u2re.cws.daemon

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import space.u2re.cws.network.postJson
import space.u2re.cws.network.normalizeHubDispatchUrl
import space.u2re.cws.network.TlsConfig
import space.u2re.cws.network.HttpServerOptions
import space.u2re.cws.network.LocalHttpServer
import space.u2re.cws.network.ReverseGatewayClient
import space.u2re.cws.network.EndpointIdentity
import space.u2re.cws.reverse.ReverseGatewayConfigProvider
import space.u2re.cws.reverse.ReverseGatewayConfig
import space.u2re.cws.reverse.AssistantNetworkBridge
import java.net.URI
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
    private var httpServerHttp: LocalHttpServer? = null
    private var httpServerHttps: LocalHttpServer? = null
    private var reverseGateway: ReverseGatewayClient? = null
    private var activeReverseGatewayConfig: ReverseGatewayConfig? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null
    private var lastNetworkReconnectAtMs = 0L
    private val clipboardTextFallback = ClipboardSyncWatcher(application, ::setClipboardBestEffort)
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

    init {
        DaemonLog.setLogLevel(settings.logLevel)
    }

    fun start() {
        if (running) return
        running = true
        setReverseGatewayState("starting", "daemon started")
        val topActivity = activityProvider()
        val activity = topActivity as? Activity

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
            reverseGateway?.stop()
            reverseGateway = null
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
        val reverseDeviceId = EndpointIdentity.bestRouteTarget(
            settings.hubClientId.ifBlank { settings.authToken.ifBlank { baseReverseConfig.deviceId } }
        ).ifBlank { baseReverseConfig.deviceId }
        val reverseClientId = EndpointIdentity.bestRouteTarget(
            settings.hubClientId.ifBlank { settings.authToken.ifBlank { settings.deviceId } }
        ).ifBlank { settings.deviceId }
        val resolvedEndpointUrl = baseReverseConfig.endpointUrl.ifBlank { settings.hubDispatchUrl }
        val resolvedUserId = baseReverseConfig.userId.ifBlank { reverseClientId }
        val resolvedUserKey = baseReverseConfig.userKey.ifBlank { settings.hubToken.ifBlank { settings.authToken } }
        val shouldEnableReverse = resolvedEndpointUrl.isNotBlank() && resolvedUserId.isNotBlank() && resolvedUserKey.isNotBlank()
        val reverseConfig = baseReverseConfig.copy(
            deviceId = reverseDeviceId,
            endpointUrl = resolvedEndpointUrl,
            userId = resolvedUserId,
            userKey = resolvedUserKey,
            enabled = baseReverseConfig.enabled || shouldEnableReverse,
                allowInsecureTls = settings.allowInsecureTls,
                trustedCa = settings.reverseTrustedCa
        )
        activeReverseGatewayConfig = reverseConfig
        reverseGatewayConfigured = reverseConfig.enabled && reverseConfig.endpointUrl.isNotBlank() && reverseConfig.userId.isNotBlank() && reverseConfig.userKey.isNotBlank()
        if (!reverseGatewayConfigured) {
            val missingFields = mutableListOf<String>().apply {
                if (reverseConfig.endpointUrl.isBlank()) add("endpointUrl (hubDispatchUrl)")
                if (reverseConfig.userId.isBlank()) add("userId (hubClientId/deviceId)")
                if (reverseConfig.userKey.isBlank()) add("userKey (hubToken)")
                if (!reverseConfig.enabled) add("reverseEnabled flag")
            }
            val reason = if (missingFields.isNotEmpty()) {
                "reverse gateway config missing: ${missingFields.joinToString(", ")}"
            } else {
                "reverse gateway disabled"
            }
            setReverseGatewayState("disabled", reason)
            DaemonLog.info("Daemon", reason)
            return
        }
        val client = ReverseGatewayClient(
            reverseConfig,
            onMessage = { messageType, text, _ ->
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
                if (event == "failure" || event == "disconnected") {
                    logSuppressedReverseGatewayError(
                        category = "reverse-connect",
                        signature = detailText,
                        cooldownMs = REVERSE_CONNECT_ERROR_COOLDOWN_MS
                    ) {
                        DaemonLog.warn("Daemon", "reverse gateway state $detailText")
                    }
                } else if (shouldLogReverseGatewayState(detailText)) {
                    when (event) {
                        "connected" -> {
                            reverseWsReconnectSuccess.incrementAndGet()
                            DaemonLog.info("Daemon", "reverse gateway state $detailText")
                        }
                        "connecting" -> DaemonLog.debug("Daemon", "reverse gateway state $detailText")
                        "reconnect-requested" -> DaemonLog.info("Daemon", "reverse gateway state $detailText")
                        "stopped", "started" -> DaemonLog.debug("Daemon", "reverse gateway state $detailText")
                    }
                } else {
                    lastReverseGatewaySuppressedCount++
                    if (lastReverseGatewaySuppressedCount % 10 == 0) {
                        DaemonLog.debug("Daemon", "reverse gateway state suppressed x$lastReverseGatewaySuppressedCount")
                    }
                }
            }
        )
        reverseGateway = client
        client.start()
        DaemonLog.info("Daemon", "reverse gateway client started")
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
        val now = System.currentTimeMillis()
        if (now - lastNetworkReconnectAtMs < 3_000L) return
        lastNetworkReconnectAtMs = now
        val client = reverseGateway ?: return
        setReverseGatewayState("reconnect-requested", reason)
        reverseWsReconnectRequests.incrementAndGet()
        DaemonLog.debug("Daemon", "reverse gateway reconnect triggered by network event: $reason")
        client.requestReconnect()
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

    private fun extractReverseEndpoint(): String {
        return activeReverseGatewayConfig?.endpointUrl?.trim() ?: settings.hubDispatchUrl
    }

    fun getConnectionSnapshot(): DaemonConnectionSnapshot {
        val now = System.currentTimeMillis()
        val reverseGatewayDiagnostics = reverseGateway?.getDiagnostics()
        return DaemonConnectionSnapshot(
            daemonRunning = running,
            localHttpServer = httpServerHttp != null,
            localHttpsServer = httpServerHttps != null,
            reverseGatewayConfigured = reverseGatewayConfigured,
            reverseGatewayEnabled = isReverseGatewayEnabled(),
            reverseGatewayConnected = reverseGateway?.isConnected() == true,
            reverseGatewayState = reverseGatewayState,
            reverseGatewayStateDetail = reverseGatewayStateDetail,
            reverseGatewayStateUpdatedAtMs = if (reverseGatewayStateUpdatedAtMs > 0L) reverseGatewayStateUpdatedAtMs else now,
            transportEndpoint = extractReverseEndpoint(),
            reverseRelayAttempts = reverseRelayAttempts.get(),
            reverseRelaySuccess = reverseRelaySuccess.get(),
            reverseRelayFallback = reverseRelayFallback.get(),
            reverseRelayFailures = reverseRelayFailures.get(),
            reverseHttpAttempts = reverseHttpAttempts.get(),
            reverseHttpSuccesses = reverseHttpSuccesses.get(),
            reverseHttpFailures = reverseHttpFailures.get(),
            reverseGatewayCandidateState = reverseGatewayDiagnostics?.candidateState,
            reverseGatewayActiveCandidate = reverseGatewayDiagnostics?.activeCandidate,
            reverseGatewayCandidateList = reverseGatewayDiagnostics?.candidateListText,
            reverseGatewayLastError = reverseGatewayDiagnostics?.lastFailureReason
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
            dispatch = syncCallbacks.dispatch,
            sendSms = syncCallbacks.sendSms,
            postClipboard = syncCallbacks.postClipboard,
            listSms = syncCallbacks.listSms,
            listNotifications = syncCallbacks.listNotifications,
            listDestinations = { settings.destinations },
            speakNotificationText = syncCallbacks.speakNotificationText,
            getConfigContent = { filename ->
                val base = settings.configPath.trim().removePrefix("fs:").removePrefix("file:")
                if (base.isBlank()) null
                else {
                    val file = space.u2re.cws.daemon.ConfigResolver.resolveFile(base)
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
                postClipboardToPeers(text)
            }
        }
        clipboardWatcher = watcher
        watcher.start()
    }

    private fun startClipboardPollingFallback() {
        if (!settings.clipboardSync) return
        clipboardFallbackTimer?.cancel()
        val intervalMs = (maxOf(1, settings.clipboardSyncIntervalSec) * 1000L).coerceAtLeast(1_000L)
        clipboardFallbackTimer = scope.launch {
            var lastSnapshot = clipboardTextFallback.lastSeenText().ifBlank {
                clipboardTextFallback.readCurrentText().trim()
            }
            while (isActive) {
                delay(intervalMs)
                val snapshot = clipboardTextFallback.readCurrentText().trim()
                if (snapshot.isNotBlank() && snapshot != lastSnapshot) {
                    lastSnapshot = snapshot
                    postClipboardToPeers(snapshot)
                }
            }
        }
    }

    fun forceClipboardSyncNow(providedText: String? = null) {
        scope.launch {
            val text = providedText?.trim() ?: clipboardTextFallback.readCurrentText().trim()
            if (text.isBlank()) {
                DaemonLog.warn("Daemon", "manual clipboard sync skipped: clipboard is empty")
                return@launch
            }
            postClipboardToPeers(text)
        }
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
            dispatch = { requests: List<DispatchRequest> ->
                dispatchHttpRequests(requests)
            },
            sendSms = { number: String, content: String ->
                if (activity == null) throw IllegalStateException("activity is unavailable")
                sendSmsAndroid(activity, number, content)
            },
            postClipboard = { text: String, targets: List<String> ->
                postClipboardToPeers(text, targets)
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
            speakNotificationText = { text: String ->
                NotificationSpeaker.speak(application, text)
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

    private suspend fun postClipboardToPeers(text: String, requestedTargets: List<String>? = null) {
        val hubItems = buildHubDispatchItems(text, requestedTargets)
        if (hubItems.isEmpty()) return
        val pendingItems = trySendClipboardViaReverseGateway(text, hubItems)
        if (pendingItems.isEmpty()) return
        val urls = pendingItems.mapNotNull { it.directFallbackUrl }

        val headers = buildMap {
            put("Content-Type", "text/plain; charset=utf-8")
            if (settings.authToken.isNotBlank()) {
                put("x-auth-token", settings.authToken)
            }
        }

        val hub = normalizeHubDispatchUrl(settings.hubDispatchUrl)
        if (!hub.isNullOrBlank()) {
            val resolvedHubClientId = settings.hubClientId.ifBlank { settings.authToken.ifBlank { settings.deviceId } }
            val resolvedHubToken = settings.hubToken.ifBlank { settings.authToken }
            val requestBodyEntries = pendingItems.map { it.request }.filter { it.isNotEmpty() }
            if (requestBodyEntries.isEmpty()) return
            val fallbackUrls = urls.toSet()
            val requestBody = buildMap<String, Any> {
                put("broadcastForceHttps", !settings.allowInsecureTls)
                put("requests", requestBodyEntries)
                if (resolvedHubClientId.isNotBlank()) {
                    put("clientId", resolvedHubClientId)
                }
                if (resolvedHubToken.isNotBlank()) {
                    put("token", resolvedHubToken)
                }
            }
            var hubDispatched = false
            try {
                val result = postJson(hub, requestBody, settings.allowInsecureTls, 10_000)
                hubDispatched = result.ok
                if (result.ok) {
                    DaemonLog.info("Daemon", "hub dispatch ok")
                    reverseWsReconnectSuccess.incrementAndGet()
                    return
                }
                DaemonLog.warn("Daemon", "hub dispatch failed ${result.status}")
            } catch (e: Exception) {
                DaemonLog.warn("Daemon", "hub dispatch error", e)
            }
            if (!hubDispatched && fallbackUrls.isNotEmpty()) {
                fallbackUrls.forEach { url ->
                    scope.launch {
                        try {
                            reverseHttpAttempts.incrementAndGet()
                            val directResult = postText(url, text, headers, settings.allowInsecureTls, 10_000)
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
                    val result = postText(url, text, headers, settings.allowInsecureTls, 10_000)
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

    private fun buildClipboardRelayPayload(text: String, request: Map<String, Any>): Map<String, Any> = buildMap {
        val normalizedTarget = extractClipboardTarget(request)
        put("text", text)
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

    private fun trySendClipboardViaReverseGateway(text: String, items: List<HubDispatchPayloadItem>): List<HubDispatchPayloadItem> {
        val gateway = reverseGateway ?: return items
        if (!gateway.isConnected()) {
            reverseRelayFallback.addAndGet(items.size.toLong())
            logReverseRoutingMetricsIfNeeded()
            return items
        }
        val config = activeReverseGatewayConfig
        val sender = listOf(
            config?.userId,
            settings.hubClientId,
            settings.authToken,
            settings.deviceId
        ).firstOrNull { !it.isNullOrBlank() } ?: "android"

        val pendingItems = mutableListOf<HubDispatchPayloadItem>()
        for (item in items) {
            reverseRelayAttempts.incrementAndGet()
            val target = extractClipboardTarget(item.request)
            if (target == null) {
                pendingItems.add(item)
                reverseRelayFallback.incrementAndGet()
                logReverseRoutingMetricsIfNeeded()
                continue
            }
            val sent = gateway.sendRelay(
                type = "clipboard",
                data = buildClipboardRelayPayload(text, item.request),
                target = target,
                route = "local",
                namespace = config?.namespace,
                from = sender
            )
            if (!sent) {
                pendingItems.add(item)
                reverseRelayFailures.incrementAndGet()
                reverseRelayFallback.incrementAndGet()
                logReverseRoutingMetricsIfNeeded()
                continue
            }
            reverseRelaySuccess.incrementAndGet()
            DaemonLog.debug("Daemon", "clipboard forwarded via reverse WS target=$target")
        }
        logReverseRoutingMetricsIfNeeded()
        return pendingItems
    }

    private fun handleShareTarget(activity: Activity?) {
        if (!settings.shareTarget || activity == null) return
        val intent = activity.intent ?: return
        val action = intent.action
        val type = intent.type
        val isSend = action == Intent.ACTION_SEND && type == "text/plain"
        val isProcessText = action == Intent.ACTION_PROCESS_TEXT && type == "text/plain"
        if (!isSend && !isProcessText) return
        val text = extractIntentText(intent)
        if (text.isBlank()) return
        scope.launch {
            if (clipboardWatcher != null) {
                clipboardWatcher?.setTextSilently(text)
            } else {
                setClipboardBestEffort(text)
            }
            postClipboardToPeers(text)
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

    private fun buildHubDispatchItems(text: String, requestedTargets: List<String>? = null): List<HubDispatchPayloadItem> {
        val associations = PeerAssociationStore.load(application)
        val out = mutableListOf<HubDispatchPayloadItem>()
        val resolvedTargets = resolveClipboardDestinations(requestedTargets)
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
                    "body" to text,
                    "method" to "POST",
                    "headers" to buildMap {
                        put("Content-Type", "text/plain; charset=utf-8")
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
                put("body", text)
                put("method", "POST")
                put("headers", buildMap {
                    put("Content-Type", "text/plain; charset=utf-8")
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
        return settings.hubClientId.ifBlank { settings.authToken.ifBlank { settings.deviceId } }
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

    private data class HubDispatchPayloadItem(
        val request: Map<String, Any>,
        val directFallbackUrl: String? = null
    )

data class SyncCallbacks(
        val setClipboardTextSync: (String) -> Unit,
        val setClipboardText: suspend (String) -> Unit,
        val dispatch: suspend (List<DispatchRequest>) -> List<DispatchResult>,
        val sendSms: suspend (String, String) -> Unit,
        val postClipboard: suspend (String, List<String>) -> Unit,
        val listSms: suspend (Int) -> List<SmsItem>,
        val listNotifications: suspend (Int) -> List<NotificationEvent>,
        val speakNotificationText: suspend (String) -> Unit
    )
}

private val ADDRESS_LIKE_PEER_TARGET = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?$")
