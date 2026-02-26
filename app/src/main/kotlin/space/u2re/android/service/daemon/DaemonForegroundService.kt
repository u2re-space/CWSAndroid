package space.u2re.service.daemon

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
import space.u2re.service.MainActivity
import space.u2re.service.R
import space.u2re.service.overlay.FloatingButtonService

class DaemonForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        DaemonLog.info(TAG, "foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = SettingsStore.load(application)
        if (!settings.runDaemonForeground) {
            DaemonLog.info(TAG, "start suppressed: foreground daemon setting is disabled")
            stopSelf()
            return START_NOT_STICKY
        }

        DaemonController.start(application)
        if (settings.showFloatingButton) {
            FloatingButtonService.start(application)
        }
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
        DaemonController.current()?.stop()
        FloatingButtonService.stop(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        DaemonLog.info(TAG, "task removed")
    }

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

        fun start(context: Context) {
            val intent = Intent(context, DaemonForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DaemonForegroundService::class.java))
        }

    }
}

