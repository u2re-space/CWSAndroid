package space.u2re.cws.daemon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import space.u2re.cws.data.ClipboardEnvelopeCodec
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

interface ClipboardWatcher : Closeable {
    fun start()
    fun stop()
    suspend fun setTextSilently(text: String)
    fun setTextSilentlySync(text: String)
    fun readCurrentText(): String
    fun lastSeenText(): String
    override fun close()
}

class ClipboardSyncWatcher(
    private val context: Context,
    private val onChange: suspend (String) -> Unit
) : ClipboardWatcher {
    private var pollJob: Job? = null
    private var suppressNext = false
    private var lastText = ""
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clipboardManager: ClipboardManager? by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    override fun start() {
        if (listener != null) return

        clipboardManager?.let { manager ->
            val nativeListener = ClipboardManager.OnPrimaryClipChangedListener {
                scope.launch {
                    pollOnce()
                }
            }
            listener = nativeListener
            manager.addPrimaryClipChangedListener(nativeListener)
            DaemonLog.info("ClipboardSyncWatcher", "clipboard listener attached")
        }

        // Android 13+ blocks repeated background clipboard reads for non-focused apps.
        // Keep a one-shot initial sync and rely on the native listener for subsequent updates.
        pollJob = scope.launch {
            pollOnce()
        }
    }

    override fun stop() {
        listener?.let { l ->
            clipboardManager?.removePrimaryClipChangedListener(l)
            DaemonLog.info("ClipboardSyncWatcher", "clipboard listener detached")
            listener = null
        }
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        try {
            val text = readClipboardText()
            handleText(text)
        } catch (e: Exception) {
            DaemonLog.warn("ClipboardSyncWatcher", "clipboard poll failed", e)
        }
    }

    private fun readClipboardText(): String {
        val clip = clipboardManager?.primaryClip
        if (clip == null || clip.itemCount == 0) return ""
        val item = clip.getItemAt(0)
        val raw = item?.coerceToText(context)?.toString() ?: ""
        return normalizeClipboardText(raw)
    }

    override fun readCurrentText(): String = try {
        readClipboardText()
    } catch (_: SecurityException) {
        lastText
    } catch (_: Exception) {
        ""
    }

    override fun lastSeenText(): String = lastText

    private suspend fun handleText(raw: String) {
        val text = normalizeClipboardText(raw)
        if (suppressNext) {
            suppressNext = false
            lastText = text
            return
        }
        lastText = text
        onChange(text)
    }

    override suspend fun setTextSilently(text: String) {
        val safeText = normalizeClipboardText(text)
        suppressNext = true
        if (!setTextNowAwait(safeText)) {
            suppressNext = false
            throw IllegalStateException("clipboard write failed")
        }
        lastText = safeText
    }

    override fun setTextSilentlySync(text: String) {
        val safeText = normalizeClipboardText(text)
        suppressNext = true
        if (!setTextNowBlocking(safeText)) {
            suppressNext = false
            throw IllegalStateException("clipboard write failed")
        }
        lastText = safeText
    }

    private suspend fun setTextNowAwait(text: String): Boolean {
        val manager = clipboardManager ?: run {
            DaemonLog.warn("ClipboardSyncWatcher", "clipboard manager unavailable")
            return false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                applyPrimaryClip(manager, text)
            } catch (e: Exception) {
                DaemonLog.warn("ClipboardSyncWatcher", "native clipboard set failed", e)
                return false
            }
            return true
        }
        return suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                var success = false
                try {
                    applyPrimaryClip(manager, text)
                    success = true
                } catch (e: Exception) {
                    DaemonLog.warn("ClipboardSyncWatcher", "native clipboard set failed", e)
                } finally {
                    continuation.resume(success)
                }
            }
        }
    }

    private fun setTextNowBlocking(text: String): Boolean {
        val manager = clipboardManager ?: run {
            DaemonLog.warn("ClipboardSyncWatcher", "clipboard manager unavailable")
            return false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                applyPrimaryClip(manager, text)
            } catch (e: Exception) {
                DaemonLog.warn("ClipboardSyncWatcher", "native clipboard set failed", e)
                return false
            }
            return true
        }
        val latch = CountDownLatch(1)
        var success = false
        Handler(Looper.getMainLooper()).post {
            try {
                applyPrimaryClip(manager, text)
                success = true
            } catch (e: Exception) {
                DaemonLog.warn("ClipboardSyncWatcher", "native clipboard set failed", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
        if (latch.count > 0L) {
            DaemonLog.warn("ClipboardSyncWatcher", "clipboard set timed out on main thread")
            return false
        }
        return success
    }

    private fun applyPrimaryClip(manager: ClipboardManager, text: String) {
        if (text.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText("clipboard", text))
    }

    /**
     * Guard against protocol/system wrappers leaking into user clipboard.
     * Example dropped artifacts: "{text=}", "{\"text\":\"\"}".
     */
    private fun normalizeClipboardText(raw: String?): String {
        val envelope = ClipboardEnvelopeCodec.fromAny(raw ?: "", source = "watcher-normalize")
        return envelope.bestText()?.trim().orEmpty()
    }

    override fun close() {
        stop()
        scope.cancel()
    }
}
