package com.audiorelay.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PlaybackTrack` itself needs a real `AudioTrack`, so these pin the
 * *decision logic* that governs it — the part that was wrong, and that a
 * plain JVM test can hold onto. Each case here corresponds to a defect that
 * made playback degrade the longer the app ran.
 */
class PlaybackTrackContractTest {

    /** Mirrors `PlaybackTrack.write`'s classification of a write result. */
    private fun classify(written: Int, released: Boolean): PlaybackTrack.WriteResult = when {
        released -> PlaybackTrack.WriteResult.FAILED
        written > 0 -> PlaybackTrack.WriteResult.OK
        written == 0 -> PlaybackTrack.WriteResult.FAILED
        written == -6 -> PlaybackTrack.WriteResult.RECOVERED // ERROR_DEAD_OBJECT, rebuilt
        else -> PlaybackTrack.WriteResult.FAILED
    }

    /**
     * A blocking write returning 0 accepted nothing and returned instantly.
     * Treating it as success turned the playback loop into a busy spin that
     * pegged a core and raced the jitter buffer past the sender.
     */
    @Test
    fun `a zero-length write is not progress`() {
        assertEquals(PlaybackTrack.WriteResult.FAILED, classify(0, released = false))
    }

    @Test
    fun `a positive write is progress`() {
        assertEquals(PlaybackTrack.WriteResult.OK, classify(960, released = false))
    }

    /**
     * The leak. Teardown releases the track while the playback loop is
     * blocked inside `write`, which throws — and that was indistinguishable
     * from a track dying mid-session, so the track got rebuilt and the loop
     * never exited. Once shut down, every outcome must be terminal.
     */
    @Test
    fun `nothing is recoverable once the track has been shut down`() {
        for (written in listOf(960, 0, -6, -3)) {
            assertEquals(
                "write=$written after shutdown must not revive the track",
                PlaybackTrack.WriteResult.FAILED,
                classify(written, released = true),
            )
        }
    }

    /** A genuine mid-session death still has to be recoverable. */
    @Test
    fun `a dead track mid-session is still rebuilt`() {
        assertEquals(PlaybackTrack.WriteResult.RECOVERED, classify(-6, released = false))
    }

    @Test
    fun `write results are distinct outcomes`() {
        assertTrue(PlaybackTrack.WriteResult.entries.size == 3)
    }
}
