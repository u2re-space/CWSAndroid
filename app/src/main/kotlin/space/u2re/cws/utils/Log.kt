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

    private fun format(tag: String, message: String, payload: Map<String, Any?>? = null): String {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val base = "[$timestamp] [$tag] $message"
        if (payload.isNullOrEmpty()) return base
        
        val builder = StringBuilder(base).append("\n")
        builder.append("┌").append("─".repeat(78)).append("┐\n")
        payload.forEach { (key, value) ->
            val strValue = value?.toString() ?: "null"
            val displayValue = if (strValue.length > 50) strValue.take(47) + "..." else strValue
            builder.append(String.format("│ %-20s │ %-53s │\n", key.take(20), displayValue))
        }
        builder.append("└").append("─".repeat(78)).append("┘")
        return builder.toString()
    }

    fun debug(tag: String, message: String, payload: Map<String, Any?>? = null, throwable: Throwable? = null) {
        if (!shouldLog(Level.DEBUG)) return
        val formatted = format(tag, message, payload)
        if (throwable != null) Log.d("CWS", formatted, throwable) else Log.d("CWS", formatted)
    }

    fun info(tag: String, message: String, payload: Map<String, Any?>? = null, throwable: Throwable? = null) {
        if (!shouldLog(Level.INFO)) return
        val formatted = format(tag, message, payload)
        if (throwable != null) Log.i("CWS", formatted, throwable) else Log.i("CWS", formatted)
    }

    fun warn(tag: String, message: String, payload: Map<String, Any?>? = null, throwable: Throwable? = null) {
        if (!shouldLog(Level.WARN)) return
        val formatted = format(tag, message, payload)
        if (throwable != null) Log.w("CWS", formatted, throwable) else Log.w("CWS", formatted)
    }

    fun error(tag: String, message: String, payload: Map<String, Any?>? = null, throwable: Throwable? = null) {
        if (!shouldLog(Level.ERROR)) return
        val formatted = format(tag, message, payload)
        if (throwable != null) Log.e("CWS", formatted, throwable) else Log.e("CWS", formatted)
    }

    // Compatibility methods
    fun debug(tag: String, message: String, throwable: Throwable? = null) = debug(tag, message, null, throwable)
    fun info(tag: String, message: String, throwable: Throwable? = null) = info(tag, message, null, throwable)
    fun warn(tag: String, message: String, throwable: Throwable? = null) = warn(tag, message, null, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) = error(tag, message, null, throwable)
}
