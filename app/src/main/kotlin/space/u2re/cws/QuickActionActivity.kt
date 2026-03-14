package space.u2re.cws

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.setPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import space.u2re.cws.data.ClipboardEnvelope
import space.u2re.cws.data.ClipboardEnvelopeCodec
import space.u2re.cws.daemon.DaemonController
import space.u2re.cws.daemon.DaemonForegroundService
import space.u2re.cws.daemon.DaemonLog
import space.u2re.cws.runtime.AppRuntimeCoordinator
import space.u2re.cws.settings.SettingsStore
import space.u2re.cws.settings.resolve

class QuickActionActivity : ComponentActivity() {

    companion object {
        const val MODE_FLOATING = "floating"
        const val EXTRA_MODE = "space.u2re.cws.EXTRA_MODE"
    }

    private enum class InputMode {
        FloatingTrigger,
        ShareOrProcessText
    }

    private lateinit var statusText: TextView
    private val actionResultMs = 280L
    private val floatingIdleMs = 240L
    private val pendingIntents = ArrayDeque<Intent>()
    private var processingQueue = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureWindow()
        buildUi()

        enqueueIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enqueueIntent(intent)
    }

    private fun resolveMode(rawMode: String?): InputMode = when (rawMode) {
        MODE_FLOATING -> InputMode.FloatingTrigger
        else -> InputMode.ShareOrProcessText
    }

    private fun enqueueIntent(nextIntent: Intent?) {
        if (nextIntent == null) return
        pendingIntents.addLast(Intent(nextIntent))
        if (processingQueue) return
        processingQueue = true
        lifecycleScope.launch {
            while (pendingIntents.isNotEmpty()) {
                val queuedIntent = pendingIntents.removeFirst()
                when (resolveMode(queuedIntent.getStringExtra(EXTRA_MODE))) {
                    InputMode.FloatingTrigger -> {
                        showStatus("CWS")
                        delay(floatingIdleMs)
                    }
                    InputMode.ShareOrProcessText -> handleShareTextMode(queuedIntent)
                }
            }
            processingQueue = false
            finishAndHide()
        }
    }

    private suspend fun handleShareTextMode(incomingIntent: Intent) {
        val sourceAction = incomingIntent.action ?: ""
        val settings = SettingsStore.load(application).resolve()
        val envelope = ClipboardEnvelopeCodec.fromIntent(application, incomingIntent, source = "quick-action")
        if (!envelope.hasContent()) {
            if (!settings.shareTarget) {
                showStatus("Share target disabled")
                DaemonLog.warn("QuickActionActivity", "share/process-text intent ignored")
                completeShareOrProcessText(sourceAction, envelope.bestText().orEmpty())
                delay(actionResultMs)
                return
            }
            showStatus("No content to sync")
            DaemonLog.warn("QuickActionActivity", "share/process-text intent without usable payload")
            completeShareOrProcessText(sourceAction, envelope.bestText().orEmpty())
            delay(actionResultMs)
            return
        }

        if (!settings.shareTarget) {
            showStatus("Share target disabled")
            delay(actionResultMs)
            completeShareOrProcessText(sourceAction, envelope.bestText().orEmpty())
            return
        }

        applyEnvelopeToClipboardIfPossible(envelope)
        DaemonLog.info("QuickActionActivity", "clipboard/share payload accepted from quick action")
        if (settings.quickActionCopyOnly) {
            showStatus(if (envelope.assets.isEmpty()) "Clipboard copied (no sync)" else "Content captured (no sync)")
            completeShareOrProcessText(sourceAction, envelope.bestText().orEmpty())
            delay(actionResultMs)
            return
        }

        val syncResult = runCatching {
            AppRuntimeCoordinator.ensureShareRuntime(application, this)
            AppRuntimeCoordinator.awaitDaemon(application, this)?.forceClipboardEnvelopeSyncNow(envelope)
        }

        if (syncResult.isSuccess) {
            showStatus(if (envelope.assets.isEmpty()) "Clipboard synced" else "Content synced")
            completeShareOrProcessText(sourceAction, envelope.bestText().orEmpty())
        } else {
            DaemonLog.warn("QuickActionActivity", "quick action failed", syncResult.exceptionOrNull())
            showStatus("Sync failed")
            completeShareOrProcessText(sourceAction, envelope.bestText().orEmpty())
        }
        delay(actionResultMs)
    }

    private fun buildUi() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val bg = GradientDrawable().apply {
            setColor(0xCC111111.toInt())
            cornerRadius = 26f
        }

        val indicator = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg
            setPadding(20, 14, 20, 14)
            addView(ProgressBar(this@QuickActionActivity).apply {
                isIndeterminate = true
            })
            statusText = TextView(this@QuickActionActivity).apply {
                setTextColor(Color.WHITE)
                text = "CWS"
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 0)
            }
            addView(statusText)
        }

        container.addView(indicator, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(container)
    }

    private fun showStatus(text: String) {
        runOnUiThread {
            statusText.text = text
        }
    }

    private fun finishAndHide() {
        finish()
        overridePendingTransition(0, 0)
    }

    private fun configureWindow() {
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            dimAmount = 0f
        }
    }

    private fun setClipboardText(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val data = ClipData.newPlainText("clipboard", text)
        manager.setPrimaryClip(data)
    }

    private fun applyEnvelopeToClipboardIfPossible(envelope: ClipboardEnvelope) {
        envelope.bestText()?.trim()?.takeIf { it.isNotBlank() }?.let(::setClipboardText)
    }

    private fun completeShareOrProcessText(sourceAction: String, text: String) {
        if (sourceAction == Intent.ACTION_PROCESS_TEXT) {
            val returnIntent = if (text.isBlank()) {
                intent
            } else {
                Intent().apply {
                    putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                }
            }
            setResult(RESULT_OK, returnIntent)
        }
    }
}
