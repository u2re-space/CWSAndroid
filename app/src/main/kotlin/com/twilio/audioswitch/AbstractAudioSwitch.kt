package com.twilio.audioswitch

import android.content.Context
import kotlin.jvm.functions.Function2

abstract class AbstractAudioSwitch {
    private val context: Context
    private val loggingEnabled: Boolean
    private val focusChangeListener: android.media.AudioManager.OnAudioFocusChangeListener?
    private val preferredDeviceList: List<Class<out AudioDevice>>

    private var availableAudioDevices: MutableList<AudioDevice> = mutableListOf(
        AudioDevice.Earpiece(),
        AudioDevice.Speakerphone(),
    )
    private var selectedAudioDevice: AudioDevice? = availableAudioDevices.firstOrNull()
    private var manageAudioFocus = true
    private var audioMode = 0
    private var focusMode = 0
    private var audioStreamType = 0
    private var audioAttributeUsageType = 0
    private var audioAttributeContentType = 0
    private var forceHandleAudioRouting = false
    private var started = false

    protected constructor(
        context: Context,
        loggingEnabled: Boolean,
        focusChangeListener: android.media.AudioManager.OnAudioFocusChangeListener?,
        preferredDeviceList: List<Class<out AudioDevice>> = emptyList(),
    ) {
        this.context = context
        this.loggingEnabled = loggingEnabled
        this.focusChangeListener = focusChangeListener
        this.preferredDeviceList = preferredDeviceList
    }

    abstract fun getAudioDeviceChangeListener(): Function2<List<AudioDevice>, AudioDevice, kotlin.Unit>?

    abstract fun setAudioDeviceChangeListener(listener: Function2<List<AudioDevice>, AudioDevice, kotlin.Unit>?)

    open fun start(audioDeviceChangeListener: Function2<List<AudioDevice>, AudioDevice, kotlin.Unit>?) {
        started = true
    }

    open fun stop() {}

    open fun activate() {}

    open fun selectDevice(audioDevice: AudioDevice) {
        selectedAudioDevice = audioDevice
    }

    open fun getSelectedAudioDevice(): AudioDevice? = selectedAudioDevice

    open fun getAvailableAudioDevices(): List<AudioDevice> = availableAudioDevices.toList()

    open fun setManageAudioFocus(enable: Boolean) {
        manageAudioFocus = enable
    }

    open fun setAudioMode(mode: Int) {
        audioMode = mode
    }

    open fun setFocusMode(mode: Int) {
        focusMode = mode
    }

    open fun setAudioStreamType(type: Int) {
        audioStreamType = type
    }

    open fun setAudioAttributeUsageType(type: Int) {
        audioAttributeUsageType = type
    }

    open fun setAudioAttributeContentType(type: Int) {
        audioAttributeContentType = type
    }

    open fun setForceHandleAudioRouting(enabled: Boolean) {
        forceHandleAudioRouting = enabled
    }

    fun getContext(): Context = context

    fun isLoggingEnabled(): Boolean = loggingEnabled

    fun getOnAudioFocusChangeListener(): android.media.AudioManager.OnAudioFocusChangeListener? =
        focusChangeListener

    @Suppress("unused")
    protected fun isStarted(): Boolean = started

    @Suppress("unused")
    protected fun getPreferredDeviceListInternal(): List<Class<out AudioDevice>> = preferredDeviceList
}
