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

/**
 * Small coordinator for deciding who owns the daemon runtime.
 *
 * NOTE: this file is the policy boundary between app-entry, boot, foreground
 * service hosting, and UI-owned daemon startup/shutdown.
 */
object AppRuntimeCoordinator {
    /** Load the current settings snapshot with resolver indirections already applied. */
    fun loadSettings(context: Context): Settings = SettingsStore.load(context).resolve()

    /** Start the runtime from the main app entry, choosing foreground-service or in-process ownership. */
    fun startFromMainEntry(application: Application, activity: Activity? = null) {
        val settings = loadSettings(application)
        if (settings.runDaemonForeground) {
            DaemonForegroundService.start(application)
        } else {
            DaemonController.start(application, activity)
        }
        applyOverlayPolicy(application, settings)
    }

    /** Start the runtime from boot when the user's boot setting allows it. */
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

    /** Start only the core daemon without deciding boot/UI policy again. */
    fun startDaemonCore(application: Application, activity: Activity? = null): Daemon {
        return DaemonController.start(application, activity)
    }

    /**
     * Stop only the UI-owned runtime.
     *
     * WHY: when the daemon is hosted by a foreground service, the UI should not
     * accidentally stop the service-owned transport stack.
     */
    fun stopUiOwnedRuntime(context: Context) {
        val settings = loadSettings(context)
        if (!settings.runDaemonForeground) {
            DaemonController.stop()
        }
        FloatingButtonService.stop(context)
    }

    /** Stop the service-hosted runtime and shared overlay state. */
    fun stopServiceHostedRuntime(context: Context) {
        DaemonController.current()?.stop()
        FloatingButtonService.stop(context)
    }

    /** Ensure the daemon exists for share-entry flows without requiring the full main UI path. */
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

    /** Apply the floating-overlay policy derived from the current settings snapshot. */
    fun applyOverlayPolicy(context: Context, settings: Settings = loadSettings(context)) {
        if (settings.showFloatingButton) {
            FloatingButtonService.start(context)
        } else {
            FloatingButtonService.stop(context)
        }
    }
}
