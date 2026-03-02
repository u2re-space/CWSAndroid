package space.u2re.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import space.u2re.service.daemon.DaemonController
import space.u2re.service.daemon.SettingsStore
import space.u2re.service.daemon.DaemonLog

class ClipboardAccessibilityService : AccessibilityService() {
    private var appSettingEnabled: Boolean = false
    private var lastEventText: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        appSettingEnabled = SettingsStore.load(applicationContext).useAccessibilityService
        if (!appSettingEnabled) {
            DaemonLog.warn(TAG, "service connected while app setting is disabled; stopping accessibility service")
            disableSelf()
        } else {
            DaemonLog.info(TAG, "clipboard accessibility service connected")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        appSettingEnabled = SettingsStore.load(applicationContext).useAccessibilityService
        if (!appSettingEnabled) return
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            return
        }

        val currentText = event.text.joinToString(" ").trim()
        if (currentText.isBlank()) return
        if (currentText == lastEventText) return

        lastEventText = currentText
        DaemonLog.debug(TAG, "accessibility event triggered clipboard sync")
        val daemon = DaemonController.current()
        if (daemon == null) {
            DaemonController.start(application)
            DaemonController.current()?.forceClipboardSyncNow(currentText)
        } else {
            daemon.forceClipboardSyncNow(currentText)
        }
    }

    override fun onInterrupt() {
        DaemonLog.warn(TAG, "clipboard accessibility service interrupted")
    }

    companion object {
        private const val TAG = "ClipboardAccessibilitySvc"
    }
}

