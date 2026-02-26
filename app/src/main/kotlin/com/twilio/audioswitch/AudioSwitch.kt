package com.twilio.audioswitch

import android.content.Context
import kotlin.jvm.functions.Function2

class AudioSwitch(
    context: Context,
    loggingEnabled: Boolean,
    onAudioFocusChangeListener: android.media.AudioManager.OnAudioFocusChangeListener?,
    preferredDeviceList: List<Class<out AudioDevice>> = emptyList(),
) : AbstractAudioSwitch(context, loggingEnabled, onAudioFocusChangeListener, preferredDeviceList) {
    private var audioDeviceChangeListener: Function2<List<AudioDevice>, AudioDevice, kotlin.Unit>? = null

    override fun getAudioDeviceChangeListener(): Function2<List<AudioDevice>, AudioDevice, kotlin.Unit>? =
        audioDeviceChangeListener

    override fun setAudioDeviceChangeListener(listener: Function2<List<AudioDevice>, AudioDevice, kotlin.Unit>?) {
        audioDeviceChangeListener = listener
    }

    override fun start(audioDeviceChangeListener: Function2<List<AudioDevice>, AudioDevice, kotlin.Unit>?) {
        super.start(audioDeviceChangeListener)
    }
}
