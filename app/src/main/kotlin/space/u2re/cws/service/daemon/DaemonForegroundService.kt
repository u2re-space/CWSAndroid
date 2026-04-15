package space.u2re.cws.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import space.u2re.cws.MainActivity
import space.u2re.cws.R
import space.u2re.cws.overlay.FloatingButtonService
import space.u2re.cws.runtime.AppRuntimeCoordinator
import space.u2re.cws.settings.Settings
import space.u2re.cws.settings.SettingsStore
import space.u2re.cws.settings.resolve

/**
 * Foreground-service host for the daemon/runtime.
 *
 * WHY: Android may kill background work aggressively; this service is the
 * explicit "keep the transport/runtime alive" ownership path.
 */
class DaemonForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        DaemonLog.info(TAG, "foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = SettingsStore.load(application).resolve()
        if (!settings.runDaemonForeground) {
            DaemonLog.info(TAG, "start suppressed: foreground daemon setting is disabled")
            stopSelf()
            return START_NOT_STICKY
        }

        AppRuntimeCoordinator.startDaemonCore(application)
        AppRuntimeCoordinator.applyOverlayPolicy(application, settings)
        val notification = buildNotification(settings)

        return try {
            startForeground(NOTIFICATION_ID, notification)
            START_STICKY
        } catch (e: Exception) {
            DaemonLog.warn(TAG, "failed to enter foreground; stopping service", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        DaemonLog.info(TAG, "foreground service destroyed")
        stopForeground(STOP_FOREGROUND_REMOVE)
        AppRuntimeCoordinator.stopServiceHostedRuntime(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        DaemonLog.info(TAG, "task removed")
    }

    /** Build the persistent notification that explains why the daemon is being kept alive. */
    private fun buildNotification(settings: Settings): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.connect_icon)
            .setContentTitle("cws daemon")
            .setContentText("Clipboard sync${if (settings.clipboardSync) " and API sync active" else " is running"}")
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .build()
    }

    /** Create the notification channel once so foreground startup can succeed on Android O+. */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clipboard daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps clipboard sync and local API gateway alive."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "cws_daemon"
        private const val NOTIFICATION_ID = 8801
        private const val TAG = "DaemonForegroundService"

        /** Start the foreground-service-hosted runtime. */
        fun start(context: Context) {
            val intent = Intent(context, DaemonForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stop the foreground-service-hosted runtime. */
        fun stop(context: Context) {
            context.stopService(Intent(context, DaemonForegroundService::class.java))
        }

    }
}

