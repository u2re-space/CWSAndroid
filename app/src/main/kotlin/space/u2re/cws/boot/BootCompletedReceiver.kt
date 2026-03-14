package space.u2re.cws.boot

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_BOOT_COMPLETED
import android.content.Intent.ACTION_MY_PACKAGE_REPLACED
import space.u2re.cws.runtime.AppRuntimeCoordinator

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_BOOT_COMPLETED,
            ACTION_MY_PACKAGE_REPLACED -> {
                val appContext = context.applicationContext as? Application ?: return
                AppRuntimeCoordinator.startFromBoot(appContext)
            }
        }
    }
}
