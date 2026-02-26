package space.u2re.service.daemon

import android.app.Application
import android.app.Activity

object DaemonController {
    private var daemon: Daemon? = null

    fun start(application: Application, activity: Activity? = null): Daemon {
        val existing = daemon
        if (existing != null) return existing
        val next = Daemon(application) { activity }
        daemon = next
        next.start()
        return next
    }

    fun stop() {
        daemon?.stop()
        daemon = null
    }

    suspend fun restart(application: Application, activity: Activity? = null) {
        daemon?.restart()
        if (daemon == null) {
            start(application, activity)
        }
    }

    fun current(): Daemon? = daemon
}
