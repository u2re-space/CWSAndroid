package space.u2re.cws.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import space.u2re.cws.history.HistoryOrigin
import space.u2re.cws.history.HistoryRepository

class NotificationRelayService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val notificationText = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (notificationText.isNullOrBlank() && title.isNullOrBlank()) return

        val event = NotificationEvent(
            id = NotificationEventStore.nextId(sbn.packageName),
            packageName = sbn.packageName,
            title = title,
            text = notificationText,
            timestamp = sbn.postTime
        )
        NotificationEventStore.record(event)
        HistoryRepository.recordNotificationEvent(event, origin = HistoryOrigin.LOCAL)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // no-op for now
    }
}
