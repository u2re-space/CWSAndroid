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
import space.u2re.cws.daemon.DaemonController
import space.u2re.cws.daemon.DaemonForegroundService
import space.u2re.cws.daemon.DaemonLog
import space.u2re.cws.daemon.SettingsStore
import space.u2re.cws.daemon.resolve

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureWindow()
        buildUi()

        lifecycleScope.launch {
            when (resolveMode(intent.getStringExtra(EXTRA_MODE))) {
                InputMode.FloatingTrigger -> {
                    showStatus("CWS")
                    delay(floatingIdleMs)
                    finishAndHide()
                }

                InputMode.ShareOrProcessText -> {
                    handleShareTextMode()
                }
            }
        }
    }

    private fun resolveMode(rawMode: String?): InputMode = when (rawMode) {
        MODE_FLOATING -> InputMode.FloatingTrigger
        else -> InputMode.ShareOrProcessText
    }

    private suspend fun handleShareTextMode() {
        val sourceAction = intent.action ?: ""
        val settings = SettingsStore.load(application).resolve()
        val text = extractTextFromIntent()
        if (text.isBlank()) {
            if (!settings.shareTarget) {
                showStatus("Share target disabled")
                DaemonLog.warn("QuickActionActivity", "share/process-text intent ignored")
                completeShareOrProcessText(sourceAction, text)
                delay(actionResultMs)
                finishAndHide()
                return
            }

            if (hasImagePayload()) {
                if (settings.quickActionHandleImage) {
                    showStatus("Image payload received (future OCR)")
                } else {
                    showStatus("Image payload is disabled")
                }
                if (sourceAction == Intent.ACTION_PROCESS_TEXT) {
                    setResult(RESULT_OK, intent)
                }
                delay(actionResultMs)
                finishAndHide()
                return
            }

            if (hasUnsupportedSharePayload()) {
                showStatus("Content sync in next version")
                DaemonLog.info("QuickActionActivity", "received non-text share payload")
            } else {
                showStatus("No text to sync")
                DaemonLog.warn("QuickActionActivity", "share/process-text intent without text")
            }
            completeShareOrProcessText(sourceAction, text)
            delay(actionResultMs)
            finishAndHide()
            return
        }

        if (!settings.shareTarget) {
            showStatus("Share target disabled")
            delay(actionResultMs)
            completeShareOrProcessText(sourceAction, text)
            finishAndHide()
            return
        }

        setClipboardText(text)
        DaemonLog.info("QuickActionActivity", "clipboard set from shared/process text")
        if (settings.quickActionCopyOnly) {
            showStatus("Clipboard copied (no sync)")
            completeShareOrProcessText(sourceAction, text)
            delay(actionResultMs)
            finishAndHide()
            return
        }

        val syncResult = runCatching {
            ensureDaemonStarted()
            waitForDaemonOrStartFallback()
            DaemonController.current()?.forceClipboardSyncNow(text)
        }

        if (syncResult.isSuccess) {
            showStatus("Clipboard synced")
            completeShareOrProcessText(sourceAction, text)
        } else {
            DaemonLog.warn("QuickActionActivity", "quick action failed", syncResult.exceptionOrNull())
            showStatus("Sync failed")
            completeShareOrProcessText(sourceAction, text)
        }
        delay(actionResultMs)
        finishAndHide()
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

    private suspend fun waitForDaemonOrStartFallback() {
        var attempts = 0
        while (DaemonController.current() == null && attempts < 8) {
            delay(80L)
            attempts++
        }

        if (DaemonController.current() == null) {
            DaemonController.start(application, this)
            var retryAttempts = 0
            while (DaemonController.current() == null && retryAttempts < 5) {
                delay(100L)
                retryAttempts++
            }
        }
    }

    private fun setClipboardText(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val data = ClipData.newPlainText("clipboard", text)
        manager.setPrimaryClip(data)
    }

    private fun extractTextFromIntent(): String {
        if (hasImagePayload()) return ""
        return try {
            intent?.getStringExtra(Intent.EXTRA_TEXT)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: intent?.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: run {
                    val data = intent?.clipData
                    if (data != null && data.itemCount > 0) {
                        data.getItemAt(0).coerceToText(this).toString().trim()
                    } else {
                        ""
                    }
                }
        } catch (_: Exception) {
            ""
        }
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

    private fun hasImagePayload(): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val type = intent?.type ?: return false
        return type.startsWith("image/")
    }

    private fun hasUnsupportedSharePayload(): Boolean {
        if (intent == null) return false
        return intent!!.action == Intent.ACTION_SEND && (
            intent!!.clipData?.itemCount?.let { it > 0 } == true ||
                intent!!.hasExtra(Intent.EXTRA_STREAM)
            )
    }

    private fun ensureDaemonStarted() {
        val settings = SettingsStore.load(application).resolve()
        if (settings.runDaemonForeground) {
            DaemonForegroundService.start(application)
        } else {
            DaemonController.start(application, this)
        }
    }
}
