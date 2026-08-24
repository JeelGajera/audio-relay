package com.audiorelay.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Wraps `AudioTrack` configured for low-latency media playback. See
 * `docs/architecture.md` §3.2: `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC` is what
 * lets Android's normal routing send this to whatever Bluetooth device is
 * already connected — deliberately *not*
 * `USAGE_VOICE_COMMUNICATION`/SCO, which routes mono/low-quality.
 *
 * **Unverified on real hardware** — see `docs/roadmap.md` Phase 0. This is
 * a best-effort implementation of the documented API, not a proven one.
 */
class PlaybackTrack(sampleRateHz: Int, channels: Int) {
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

    fun play() = track.play()

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
