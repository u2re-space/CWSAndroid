package space.u2re.cws.boot

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_BOOT_COMPLETED
import android.content.Intent.ACTION_MY_PACKAGE_REPLACED
import space.u2re.cws.daemon.DaemonForegroundService
import space.u2re.cws.daemon.DaemonController
import space.u2re.cws.daemon.SettingsStore
import space.u2re.cws.daemon.resolve
import space.u2re.cws.overlay.FloatingButtonService

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_BOOT_COMPLETED,
            ACTION_MY_PACKAGE_REPLACED -> {
                val settings = SettingsStore.load(context).resolve()
                if (!settings.runDaemonOnBoot) return
                val appContext = context.applicationContext as? Application ?: return
                if (settings.runDaemonForeground) {
                    DaemonForegroundService.start(appContext)
                } else {
                    DaemonController.start(appContext)
                }
                if (settings.showFloatingButton) {
                    FloatingButtonService.start(appContext)
                }
            }
        }
    }
}

