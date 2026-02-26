package space.u2re.service.daemon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import java.io.Closeable

interface ClipboardWatcher : Closeable {
    fun start()
    fun stop()
    suspend fun setTextSilently(text: String)
    fun setTextSilentlySync(text: String)
    fun readCurrentText(): String
    fun lastSeenText(): String
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
        if (pollJob != null) return

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

        pollJob = scope.launch {
            pollOnce()
            while (this.isActive) {
                delay(900)
                pollOnce()
            }
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
        return item?.coerceToText(context)?.toString() ?: ""
    }

    override fun readCurrentText(): String = try {
        readClipboardText()
    } catch (_: Exception) {
        ""
    }

    override fun lastSeenText(): String = lastText

    private suspend fun handleText(raw: String) {
        val text = raw.ifBlank { return }
        if (suppressNext) {
            suppressNext = false
            lastText = text
            return
        }
        if (text != lastText) {
            lastText = text
            onChange(text)
        }
    }

    override suspend fun setTextSilently(text: String) {
        suppressNext = true
        val safeText = text.ifBlank { "" }
        if (safeText.isNotEmpty()) {
            setTextNow(safeText)
            lastText = safeText
        }
    }

    override fun setTextSilentlySync(text: String) {
        suppressNext = true
        val safeText = text.ifBlank { "" }
        if (safeText.isNotEmpty()) {
            setTextNow(safeText)
            lastText = safeText
        }
    }

    private fun setTextNow(text: String) {
        try {
            val manager = clipboardManager ?: return
            val clip = ClipData.newPlainText("clipboard", text)
            // Use handler to avoid touching platform APIs from non-main thread in some cases.
            Handler(Looper.getMainLooper()).post {
                manager.setPrimaryClip(clip)
            }
        } catch (e: Exception) {
            DaemonLog.warn("ClipboardSyncWatcher", "native clipboard set failed", e)
            lastText = text
        }
    }

    override fun close() {
        stop()
        scope.cancel()
    }
}
