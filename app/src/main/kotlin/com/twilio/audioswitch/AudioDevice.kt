package com.twilio.audioswitch

open class AudioDevice(
    label: String? = null,
) {
    private val name = label ?: this::class.simpleName ?: "AudioDevice"

    open fun getName(): String = name

    class Earpiece : AudioDevice("Earpiece")
    class Speakerphone : AudioDevice("Speakerphone")
    class WiredHeadset : AudioDevice("WiredHeadset")
    class BluetoothHeadset : AudioDevice("BluetoothHeadset")
}
