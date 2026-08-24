package com.audiorelay.app.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Wraps `AudioTrack` configured for low-latency media playback. See
 * `docs/architecture.md` §3.2: `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC` is what
 * lets Android's normal routing send this to whatever Bluetooth device is
 * already connected — deliberately *not*
 * `USAGE_VOICE_COMMUNICATION`/SCO, which routes mono/low-quality.
 *
 * [preferredDevice] overrides that automatic routing when the user has
 * explicitly picked an output device in Settings (see
 * `audio/OutputDeviceRepository.kt`) — `null` leaves Android's normal
 * routing in charge, which is the default and what most users want.
 *
 * **Unverified on real hardware** — see `docs/roadmap.md` Phase 0. This is
 * a best-effort implementation of the documented API, not a proven one.
 */
class PlaybackTrack(sampleRateHz: Int, channels: Int, preferredDevice: AudioDeviceInfo? = null) {
    private val channelMask =
        if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

    private val minBufferSize = AudioTrack.getMinBufferSize(
        sampleRateHz,
        channelMask,
        AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(1)

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRateHz)
                .setChannelMask(channelMask)
                .build(),
        )
        .setBufferSizeInBytes(minBufferSize)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .apply { setPreferredDevice(preferredDevice) }

    fun play() = track.play()

    /**
     * Switches the output route on an already-playing track — unlike the
     * capture-device equivalent on the Windows side, this does *not* need a
     * restart, so Settings changes can apply instantly while streaming.
     * `null` reverts to Android's normal automatic routing.
     */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        // Best-effort: returns false if the device isn't a valid output
        // route for this track; not worth surfacing as an error, Android
        // just falls back to normal routing in that case.
        track.setPreferredDevice(device)
    }

    fun write(pcm: ByteArray) {
        track.write(pcm, 0, pcm.size)
    }

    fun stop() {
        track.stop()
    }

    fun release() {
        track.release()
    }
}
