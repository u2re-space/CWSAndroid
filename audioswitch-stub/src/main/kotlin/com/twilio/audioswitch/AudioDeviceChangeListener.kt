package com.twilio.audioswitch

interface AudioDeviceChangeListener {
    fun onAudioDeviceChange(audioDevices: List<AudioDevice>, selectedAudioDevice: AudioDevice)
}
