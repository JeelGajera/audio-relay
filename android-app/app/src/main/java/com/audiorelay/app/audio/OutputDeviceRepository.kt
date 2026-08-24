package com.audiorelay.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

data class OutputDevice(
    /** Stable across reconnects (unlike [AudioDeviceInfo.getId], which is only stable within one connection). */
    val key: String,
    val label: String,
)

/**
 * Lists available audio output routes (Bluetooth, wired, USB, the phone's
 * own speaker) so the user can explicitly pick one instead of relying
 * purely on Android's automatic routing — see `docs/architecture.md` §3.2
 * and the Settings screen (`ui/SettingsScreen.kt`).
 *
 * **Unverified on real hardware** — see `docs/roadmap.md` Phase 0.
 */
class OutputDeviceRepository(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun listOutputDevices(): List<OutputDevice> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in RELEVANT_TYPES }
            .map { OutputDevice(stableKey(it), describe(it)) }

    /** Resolves a persisted [OutputDevice.key] back to a live [AudioDeviceInfo], if that device is currently connected. */
    fun findByKey(key: String): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { stableKey(it) == key }

    /** Notified whenever the set of connected audio devices changes (plugged/unplugged, BT connect/disconnect). */
    fun registerChangeCallback(onChanged: () -> Unit): AudioDeviceCallback {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onChanged()
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        return callback
    }

    fun unregisterChangeCallback(callback: AudioDeviceCallback) {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun stableKey(info: AudioDeviceInfo): String = "${info.type}:${info.address}"

    private fun describe(info: AudioDeviceInfo): String {
        val typeLabel = when (info.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call audio)"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            else -> "Audio device"
        }
        val name = info.productName?.toString()?.takeIf { it.isNotBlank() && it != "?" }
        return if (name != null) "$typeLabel — $name" else typeLabel
    }

    companion object {
        private val RELEVANT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
        )
    }
}
