package space.u2re.cws.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import io.livekit.android.LiveKit
import io.livekit.android.token.TokenSource
import io.livekit.android.token.cached
import space.u2re.cws.ui.screen.VoiceAssistantRoute

class VoiceAssistantViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    val room = LiveKit.create(application)

    val tokenSource: TokenSource

    init {
        val (sandboxId, url, token, _apiEndpoint, _apiKey, _aiAllowInsecureTls) =
            savedStateHandle.toRoute<VoiceAssistantRoute>()

        tokenSource = if (sandboxId.isNotEmpty()) {
            TokenSource.fromSandboxTokenServer(sandboxId = sandboxId).cached()
        } else {
            TokenSource.fromLiteral(url, token).cached()
        }
    }

    override fun onCleared() {
        super.onCleared()
        room.disconnect()
        room.release()
    }
}
