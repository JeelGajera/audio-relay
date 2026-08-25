package com.audiorelay.app.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Loudness of one PCM chunk as a 0.0..1.0 meter reading.
 *
 * Not raw RMS: loudness is perceived logarithmically, so a linear bar sits
 * almost at the floor for ordinary listening levels. Maps -60dBFS..0dBFS onto
 * the full range, which is what a level meter is actually useful over.
 *
 * Deliberately mirrors `rms_level` in `windows-app/src/capture/mod.rs`, so
 * the meter on the laptop and the visualiser on the phone mean the same
 * thing. Pure Kotlin, so it is unit-testable off-device.
 */
object AudioLevel {
    private const val FLOOR_DBFS = -60.0f

    fun fromPcm16(pcm: ByteArray, length: Int = pcm.size): Float {
        var sumSquares = 0.0
        var samples = 0
        // A trailing odd byte is skipped rather than misread; half a sample
        // cannot be interpreted.
        var i = 0
        val end = minOf(length, pcm.size) - 1
        while (i < end) {
            val value = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
            val normalized = value / 32768.0
            sumSquares += normalized * normalized
            samples++
            i += 2
        }
        if (samples == 0) return 0f

        val rms = sqrt(sumSquares / samples).toFloat()
        if (rms <= 1e-6f) return 0f
        val dbfs = 20f * log10(rms)
        return ((dbfs - FLOOR_DBFS) / -FLOOR_DBFS).coerceIn(0f, 1f)
    }
}
