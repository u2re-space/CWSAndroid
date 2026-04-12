package space.u2re.cws.notifications

import java.util.ArrayDeque
import java.util.UUID

data class NotificationEvent(
    val id: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val timestamp: Long
)

object NotificationEventStore {
    private const val MAX_EVENTS = 200
    private val entries = ArrayDeque<NotificationEvent>()

    fun record(event: NotificationEvent) {
        synchronized(entries) {
            entries.addLast(event)
            while (entries.size > MAX_EVENTS) {
                entries.removeFirst()
            }
        }
    }

    fun snapshot(limit: Int): List<NotificationEvent> {
        val safeLimit = limit.coerceIn(1, MAX_EVENTS)
        synchronized(entries) {
            return entries.toList().asReversed().take(safeLimit)
        }
    }

    fun nextId(packageName: String): String = "${packageName}-${UUID.randomUUID()}"
}
