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

class AutomataDaemon(
    private val application: Application,
    private val activityProvider: () -> Activity?
) {
    private var settings: AutomataSettings = AutomataSettingsStore.load(application)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var clipboardWatcher: ClipboardSyncWatcher? = null
    private var syncTimer: Job? = null
    private var httpServerHttp: LocalHttpServer? = null
    private var httpServerHttps: LocalHttpServer? = null
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
                settings = AutomataSettingsStore.load(application)
                DaemonLog.setLogLevel(settings.logLevel)
                startServers(createSyncCallbacks(activity))
                startClipboardSync()
                startPeriodicSync()
                handleShareTarget(activity)
                DaemonLog.info("AutomataDaemon", "daemon started")
            } catch (e: Exception) {
                DaemonLog.error("AutomataDaemon", "daemon start failed", e)
                running = false
            }
        }
    }

    fun stop() {
        running = false
        scope.launch {
            clipboardWatcher?.stop()
            clipboardWatcher = null
            httpServerHttp?.stop()
            httpServerHttps?.stop()
            httpServerHttp = null
            httpServerHttps = null
            syncTimer?.cancel()
            syncTimer = null
            DaemonLog.info("AutomataDaemon", "daemon stopped")
        }
    }

    suspend fun restart() {
        stop()
        delay(50)
        start()
    }

    private fun startServers(syncCallbacks: SyncCallbacks) {
        val commonOptions = HttpServerOptions(
            port = settings.listenPortHttp,
            authToken = settings.authToken,
            tls = null,
            context = application,
            setClipboardTextSync = syncCallbacks.setClipboardTextSync,
            setClipboardText = syncCallbacks.setClipboardText,
            dispatch = syncCallbacks.dispatch,
            sendSms = syncCallbacks.sendSms
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

        httpServerHttp = LocalHttpServer(commonOptions)
        httpServerHttps = LocalHttpServer(httpsOptions)
        httpServerHttp?.start()
        httpServerHttps?.start()
        DaemonLog.info("AutomataDaemon", "http servers started")
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
                DaemonLog.info("AutomataDaemon", "synced contacts ${contacts.size}")
            }
            if (activity != null && settings.smsSync) {
                val sms = readSmsInbox(activity)
                DaemonLog.info("AutomataDaemon", "synced sms ${sms.size}")
            }
        } catch (e: Exception) {
            DaemonLog.warn("AutomataDaemon", "sync tick failed", e)
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
            }
        )
    }

    private fun setClipboardBestEffort(text: String) {
        try {
            clipboardTextFallback.setTextSilentlySync(text)
        } catch (e: Exception) {
            DaemonLog.warn("AutomataDaemon", "setClipboardBestEffort failed", e)
        }
    }

    private suspend fun postClipboardToPeers(text: String) {
        val urls = settings.destinations
            .mapNotNull { normalizeEndpointUrl(it, "/clipboard") }
            .filter { it.isNotBlank() }
        if (urls.isEmpty()) return

        val headers = buildMap {
            put("Content-Type", "text/plain; charset=utf-8")
            if (settings.authToken.isNotBlank()) {
                put("x-auth-token", settings.authToken)
            }
        }

        val hub = normalizeHubDispatchUrl(settings.hubDispatchUrl)
        if (!hub.isNullOrBlank()) {
            val payload = urls.map { DispatchRequest(url = it, body = text, unencrypted = true) }
            try {
                val result = postJson(hub, mapOf("requests" to payload), settings.allowInsecureTls, 10_000)
                if (result.ok) {
                    DaemonLog.info("AutomataDaemon", "hub dispatch ok")
                } else {
                    DaemonLog.warn("AutomataDaemon", "hub dispatch failed ${result.status}")
                }
            } catch (e: Exception) {
                DaemonLog.warn("AutomataDaemon", "hub dispatch error", e)
            }
            return
        }

        urls.forEach { url ->
            scope.launch {
                try {
                    val result = postText(url, text, headers, settings.allowInsecureTls, 10_000)
                    if (!result.ok) {
                        DaemonLog.warn("AutomataDaemon", "clipboard broadcast failed ${result.status} $url")
                    }
                } catch (e: Exception) {
                    DaemonLog.warn("AutomataDaemon", "clipboard broadcast error $url", e)
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
            DaemonLog.info("AutomataDaemon", "handled share/process-text intent")
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

    private data class SyncCallbacks(
        val setClipboardTextSync: (String) -> Unit,
        val setClipboardText: suspend (String) -> Unit,
        val dispatch: suspend (List<DispatchRequest>) -> List<DispatchResult>,
        val sendSms: suspend (String, String) -> Unit
    )
}
