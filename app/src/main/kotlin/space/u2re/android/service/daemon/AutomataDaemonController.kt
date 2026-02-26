package io.livekit.android.example.voiceassistant.daemon

import android.app.Application
import android.app.Activity

object AutomataDaemonController {
    private var daemon: AutomataDaemon? = null

    fun start(application: Application, activity: Activity? = null): AutomataDaemon {
        val existing = daemon
        if (existing != null) return existing
        val next = AutomataDaemon(application) { activity }
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

    fun current(): AutomataDaemon? = daemon
}
