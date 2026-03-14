package space.u2re.cws.runtime

import android.app.Activity
import android.app.Application
import android.content.Context
import kotlinx.coroutines.delay
import space.u2re.cws.daemon.Daemon
import space.u2re.cws.daemon.DaemonController
import space.u2re.cws.daemon.DaemonForegroundService
import space.u2re.cws.overlay.FloatingButtonService
import space.u2re.cws.settings.Settings
import space.u2re.cws.settings.SettingsStore
import space.u2re.cws.settings.resolve

object AppRuntimeCoordinator {
    fun loadSettings(context: Context): Settings = SettingsStore.load(context).resolve()

    fun startFromMainEntry(application: Application, activity: Activity? = null) {
        val settings = loadSettings(application)
        if (settings.runDaemonForeground) {
            DaemonForegroundService.start(application)
        } else {
            DaemonController.start(application, activity)
        }
        applyOverlayPolicy(application, settings)
    }

    fun startFromBoot(application: Application) {
        val settings = loadSettings(application)
        if (!settings.runDaemonOnBoot) return
        if (settings.runDaemonForeground) {
            DaemonForegroundService.start(application)
        } else {
            DaemonController.start(application)
        }
        applyOverlayPolicy(application, settings)
    }

    fun startDaemonCore(application: Application, activity: Activity? = null): Daemon {
        return DaemonController.start(application, activity)
    }

    fun stopUiOwnedRuntime(context: Context) {
        val settings = loadSettings(context)
        if (!settings.runDaemonForeground) {
            DaemonController.stop()
        }
        FloatingButtonService.stop(context)
    }

    fun stopServiceHostedRuntime(context: Context) {
        DaemonController.current()?.stop()
        FloatingButtonService.stop(context)
    }

    fun ensureShareRuntime(application: Application, activity: Activity? = null) {
        val settings = loadSettings(application)
        if (settings.runDaemonForeground) {
            DaemonForegroundService.start(application)
        } else {
            DaemonController.start(application, activity)
        }
    }

    suspend fun awaitDaemon(
        application: Application,
        activity: Activity? = null,
        maxAttempts: Int = 8,
        delayMs: Long = 80L
    ): Daemon? {
        var daemon = DaemonController.current()
        if (daemon != null) return daemon

        var attempts = 0
        while (daemon == null && attempts < maxAttempts) {
            delay(delayMs)
            daemon = DaemonController.current()
            attempts++
        }
        if (daemon != null) return daemon

        startDaemonCore(application, activity)
        attempts = 0
        while (daemon == null && attempts < 5) {
            delay(100L)
            daemon = DaemonController.current()
            attempts++
        }
        return daemon
    }

    fun applyOverlayPolicy(context: Context, settings: Settings = loadSettings(context)) {
        if (settings.showFloatingButton) {
            FloatingButtonService.start(context)
        } else {
            FloatingButtonService.stop(context)
        }
    }
}
