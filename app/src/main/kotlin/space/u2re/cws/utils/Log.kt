package space.u2re.cws.daemon

import android.util.Log

object DaemonLog {
    enum class Level(val priority: Int) {
        DEBUG(10),
        INFO(20),
        WARN(30),
        ERROR(40)
    }

    private var currentLevel = Level.DEBUG

    fun setLogLevel(level: String?) {
        currentLevel = when (level?.lowercase()) {
            "debug" -> Level.DEBUG
            "info" -> Level.INFO
            "warn" -> Level.WARN
            "error" -> Level.ERROR
            else -> Level.DEBUG
        }
    }

    private fun shouldLog(level: Level): Boolean = level.priority >= currentLevel.priority

    private fun format(tag: String, message: String): String = "$tag $message"

    fun debug(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(Level.DEBUG)) return
        if (throwable != null) Log.d(tag, format("[cws]", message), throwable) else Log.d(tag, format("[cws]", message))
    }

    fun info(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(Level.INFO)) return
        if (throwable != null) Log.i(tag, format("[cws]", message), throwable) else Log.i(tag, format("[cws]", message))
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(Level.WARN)) return
        if (throwable != null) Log.w(tag, format("[cws]", message), throwable) else Log.w(tag, format("[cws]", message))
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(Level.ERROR)) return
        if (throwable != null) Log.e(tag, format("[cws]", message), throwable) else Log.e(tag, format("[cws]", message))
    }
}
