package com.audiorelay.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * These mirror the Rust `rms_level` tests in
 * `desktop-app/src/capture/mod.rs`. Both meters must mean the same thing, or
 * the same audio reads differently on the laptop and the phone.
 */
class AudioLevelTest {

    private fun pcm(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun fullScale(count: Int) = ShortArray(count) {
        if (it % 2 == 0) Short.MAX_VALUE else (Short.MIN_VALUE + 1).toShort()
    }

    @Test
    fun `silence reads zero`() {
        assertEquals(0f, AudioLevel.fromPcm16(pcm(ShortArray(64))), 0f)
    }

    @Test
    fun `empty input is not a crash`() {
        assertEquals(0f, AudioLevel.fromPcm16(ByteArray(0)), 0f)
        assertEquals(0f, AudioLevel.fromPcm16(byteArrayOf(0)), 0f) // half a sample
    }

    @Test
    fun `a trailing odd byte is ignored rather than misread`() {
        val buffer = pcm(fullScale(8)) + byteArrayOf(0x7F)
        assertTrue(abs(AudioLevel.fromPcm16(buffer) - 1f) < 0.01f)
    }

    @Test
    fun `full scale reads full`() {
        assertTrue(abs(AudioLevel.fromPcm16(pcm(fullScale(64))) - 1f) < 0.01f)
    }

    /** Halving amplitude is -6dB, a tenth of a 60dB scale. */
    @Test
    fun `halving amplitude drops about six decibels`() {
        val loud = AudioLevel.fromPcm16(pcm(fullScale(64)))
        val quiet = AudioLevel.fromPcm16(pcm(ShortArray(64) { (fullScale(64)[it] / 2).toShort() }))
        assertTrue("expected ~0.1 of the bar, got ${loud - quiet}", abs((loud - quiet) - 0.1f) < 0.01f)
    }

    @Test
    fun `output always stays within the meter range`() {
        for (amplitude in shortArrayOf(0, 1, 100, 5_000, Short.MAX_VALUE)) {
            val level = AudioLevel.fromPcm16(pcm(ShortArray(32) { amplitude }))
            assertTrue("$amplitude produced $level", level in 0f..1f)
        }
    }

    @Test
    fun `very quiet signals pin to the floor`() {
        assertEquals(0f, AudioLevel.fromPcm16(pcm(ShortArray(128) { 1 })), 0f)
    }

    @Test
    fun `honours an explicit length shorter than the array`() {
        val buffer = pcm(fullScale(64))
        // Only the first sample counted; still full scale, so still 1.0.
        assertTrue(abs(AudioLevel.fromPcm16(buffer, length = 2) - 1f) < 0.01f)
        assertEquals(0f, AudioLevel.fromPcm16(buffer, length = 0), 0f)
    }
}
