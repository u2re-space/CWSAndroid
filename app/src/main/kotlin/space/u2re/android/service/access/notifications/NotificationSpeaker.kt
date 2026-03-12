package space.u2re.cws.notifications

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech

object NotificationSpeaker {
    fun speak(context: Context, text: String) {
        val safeText = text.trim()
        if (safeText.isBlank()) return

        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                engine.shutdown()
                return@TextToSpeech
            }
            val utterance = "notification-${System.currentTimeMillis()}"
            engine.speak(safeText, TextToSpeech.QUEUE_FLUSH, null, utterance)
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                engine.shutdown()
            }, 3000L)
        }
    }
}
