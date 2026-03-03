package space.u2re.service.daemon

import android.app.Application
import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.u2re.service.notifications.NotificationEvent
import space.u2re.service.notifications.NotificationEventStore
import space.u2re.service.notifications.NotificationSpeaker
import space.u2re.service.network.DispatchResult
import space.u2re.service.network.sendSmsAndroid
import space.u2re.service.network.DispatchRequest
import space.u2re.service.network.dispatchHttpRequests
import space.u2re.service.network.normalizeDestinationUrl
import space.u2re.service.network.normalizeDestinationHost
import space.u2re.service.network.postText
import space.u2re.service.network.postJson
import space.u2re.service.network.normalizeHubDispatchUrl
import space.u2re.service.network.TlsConfig
import space.u2re.service.network.HttpServerOptions
import space.u2re.service.network.LocalHttpServer
import space.u2re.service.network.ReverseGatewayClient
import space.u2re.service.reverse.ReverseGatewayConfigProvider
import space.u2re.service.reverse.AssistantNetworkBridge

class Daemon(
    private val application: Application,
    private val activityProvider: () -> Activity?
) {
    private var settings: Settings = SettingsStore.load(application).resolve()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var clipboardWatcher: ClipboardSyncWatcher? = null
    private var syncTimer: Job? = null
    private var clipboardFallbackTimer: Job? = null
    private var httpServerHttp: LocalHttpServer? = null
    private var httpServerHttps: LocalHttpServer? = null
    private var reverseGateway: ReverseGatewayClient? = null
    private val clipboardTextFallback = ClipboardSyncWatcher(application, ::setClipboardBestEffort)
    private var running = false

    init {
        DaemonLog.setLogLevel(settings.logLevel)
    }

    fun start() {
        if (running) return
        running = true
        val topActivity = activityProvider()
        val activity = topActivity as? Activity

        scope.launch {
            try {
                settings = SettingsStore.load(application).resolve()
                DaemonLog.setLogLevel(settings.logLevel)
                val syncCallbacks = createSyncCallbacks(activity)
                startServers(syncCallbacks)
                startReverseGateway(syncCallbacks)
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
            reverseGateway?.stop()
            reverseGateway = null
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
        val baseReverseConfig = ReverseGatewayConfigProvider.load(application).copy(deviceId = settings.deviceId)
        val reverseConfig = baseReverseConfig.copy(
            endpointUrl = baseReverseConfig.endpointUrl.ifBlank { settings.hubDispatchUrl },
            userId = baseReverseConfig.userId.ifBlank { settings.hubClientId.ifBlank { settings.deviceId } },
            userKey = baseReverseConfig.userKey.ifBlank { settings.hubToken.ifBlank { settings.authToken } }
        )
        val client = ReverseGatewayClient(reverseConfig) { messageType, text, _ ->
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
                    DaemonLog.warn("Daemon", "reverse bridge failed", e)
                }
            }
        }
        reverseGateway = client
        client.start()
        DaemonLog.info("Daemon", "reverse gateway client started")
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
            listSms = syncCallbacks.listSms,
            listNotifications = syncCallbacks.listNotifications,
            listDestinations = { settings.destinations },
            speakNotificationText = syncCallbacks.speakNotificationText
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
            setClipboardTextSync = { text ->
                clipboardTextFallback.setTextSilentlySync(text)
            },
            setClipboardText = { text ->
                clipboardTextFallback.setTextSilently(text)
            },
            dispatch = { requests ->
                dispatchHttpRequests(requests)
            },
            sendSms = { number, content ->
                if (activity == null) throw IllegalStateException("activity is unavailable")
                sendSmsAndroid(activity, number, content)
            },
            listSms = { limit ->
                if (activity == null) return@SyncCallbacks emptyList()
                readSmsInbox(activity, limit)
            },
            listNotifications = { limit ->
                NotificationEventStore.snapshot(limit)
            },
            speakNotificationText = { text ->
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

    private suspend fun postClipboardToPeers(text: String) {
        val hubItems = buildHubDispatchItems(text)
        if (hubItems.isEmpty()) return
        val urls = hubItems.mapNotNull { it.directFallbackUrl }

        val headers = buildMap {
            put("Content-Type", "text/plain; charset=utf-8")
            if (settings.authToken.isNotBlank()) {
                put("x-auth-token", settings.authToken)
            }
        }

        val hub = normalizeHubDispatchUrl(settings.hubDispatchUrl)
        if (!hub.isNullOrBlank()) {
            val resolvedHubClientId = settings.hubClientId.ifBlank { settings.deviceId }
            val resolvedHubToken = settings.hubToken.ifBlank { settings.authToken }
            val requestBodyEntries = hubItems.map { it.request }.filter { it.isNotEmpty() }
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
                            val directResult = postText(url, text, headers, settings.allowInsecureTls, 10_000)
                            if (!directResult.ok) {
                                DaemonLog.warn("Daemon", "hub fallback dispatch failed ${directResult.status} $url")
                            }
                        } catch (e: Exception) {
                            DaemonLog.warn("Daemon", "hub fallback dispatch error $url", e)
                        }
                    }
                }
            }
            return
        }

        urls.forEach { url ->
            scope.launch {
                try {
                    val result = postText(url, text, headers, settings.allowInsecureTls, 10_000)
                    if (!result.ok) {
                        DaemonLog.warn("Daemon", "clipboard broadcast failed ${result.status} $url")
                    }
                } catch (e: Exception) {
                    DaemonLog.warn("Daemon", "clipboard broadcast error $url", e)
                }
            }
        }
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

    private fun buildHubDispatchItems(text: String): List<HubDispatchPayloadItem> {
        val associations = PeerAssociationStore.load(application)
        val out = mutableListOf<HubDispatchPayloadItem>()
        for (raw in settings.destinations) {
            val target = raw.trim()
            if (target.isBlank()) continue

            val lower = target.lowercase()
            val isDeviceTarget = lower.startsWith("device:") || lower.startsWith("local-device:") || lower.startsWith("id:")
            val normalizedTarget = normalizeDestinationHost(target).trim()
            if (normalizedTarget.isBlank()) continue
            val maybeCachedUrl = associations[normalizedTarget.lowercase()]
            val directCandidate = if (isDeviceTarget && isAddressLikePeerTarget(normalizedTarget)) {
                normalizeDestinationUrl(target, "/clipboard")
            } else {
                null
            }
            if (directCandidate != null && directCandidate.isNotBlank()) {
                PeerAssociationStore.save(application, normalizedTarget, directCandidate)
            }

            if (isDeviceTarget) {
                val request = mutableMapOf<String, Any>(
                    "deviceId" to normalizedTarget.lowercase(),
                    "body" to text,
                    "method" to "POST",
                    "headers" to buildMap {
                        put("Content-Type", "text/plain; charset=utf-8")
                        if (settings.authToken.isNotBlank()) put("x-auth-token", settings.authToken)
                    }
                )
                if (!maybeCachedUrl.isNullOrBlank()) {
                    request["url"] = maybeCachedUrl
                    if (maybeCachedUrl.startsWith("http://")) {
                        request["unencrypted"] = true
                    }
                }
                if (isAddressLikePeerTarget(normalizedTarget) && maybeCachedUrl == null && directCandidate != null) {
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

    private fun isAddressLikePeerTarget(target: String): Boolean {
        return ADDRESS_LIKE_PEER_TARGET.containsMatchIn(target)
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
        val listSms: suspend (Int) -> List<SmsItem>,
        val listNotifications: suspend (Int) -> List<NotificationEvent>,
        val speakNotificationText: suspend (String) -> Unit
    )
}

private val ADDRESS_LIKE_PEER_TARGET = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?$")
