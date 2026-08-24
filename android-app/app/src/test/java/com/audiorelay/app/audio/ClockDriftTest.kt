package com.audiorelay.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Clock-drift correction tests (docs/roadmap.md Phase 5).
 *
 * These drive [JitterBuffer] through a discrete-event simulation of a real
 * session: a sender emitting chunks on its own slightly-wrong clock, and a
 * receiver draining them at the rate an audio DAC actually would — one
 * chunk takes exactly as long to play as the number of frames in it. That
 * second part is what makes the test meaningful, because it's the feedback
 * path the correction relies on: shortening a chunk really does make the
 * next `pop` happen sooner.
 *
 * Receiver time is the reference clock throughout, so a positive drift
 * figure means the *sender* is running fast.
 */
class ClockDriftTest {

    private companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * 2 // 16-bit
        const val FRAMES_PER_MS = SAMPLE_RATE_HZ / 1000.0
        const val CHUNK_MS = 10
        const val CHUNK_FRAMES = (SAMPLE_RATE_HZ / 1000) * CHUNK_MS
        const val CHUNK_BYTES = CHUNK_FRAMES * BYTES_PER_FRAME
        const val TARGET_DEPTH = 3

        /** Ten simulated minutes — long enough for realistic ppm drift to matter. */
        const val DEFAULT_DURATION_MS = 600_000.0

        /** Poll interval the real playback loop uses while pre-buffering. */
        const val PREBUFFER_POLL_MS = 5.0
    }

    private class Result(
        val framesDropped: Long,
        val framesDuplicated: Long,
        val observedDriftPpm: Double,
        /** Mean buffer depth over the final quarter of the run, i.e. steady state. */
        val steadyStateDepth: Double,
        val minDepthAfterWarmup: Int,
        val maxDepthAfterWarmup: Int,
    )

    private class SendEvent(val arrivalMs: Double, val sequence: UInt, val timestampMs: UInt)

    /**
     * @param driftPpm sender clock error relative to the receiver; positive = sender fast.
     * @param jitterMs uniform +/- network jitter applied to arrival times, zero-mean.
     */
    private fun simulate(
        driftPpm: Double,
        correctionEnabled: Boolean = true,
        durationMs: Double = DEFAULT_DURATION_MS,
        jitterMs: Double = 0.0,
        seed: Long = 42L,
    ): Result {
        var now = 0.0
        val buffer = JitterBuffer(
            chunkSizeBytes = CHUNK_BYTES,
            targetDepthChunks = TARGET_DEPTH,
            bytesPerFrame = BYTES_PER_FRAME,
            driftCorrectionEnabled = correctionEnabled,
            nowMs = { now.toLong() },
        )

        // A sender whose clock runs fast emits chunks closer together in real
        // time, while still stamping each one CHUNK_MS after the last.
        val sendIntervalMs = CHUNK_MS * 1_000_000.0 / (1_000_000.0 + driftPpm)
        val rng = Random(seed)

        val sends = ArrayList<SendEvent>()
        var scheduled = 0.0
        var sequence = 0u
        var senderTimestamp = 0u
        while (scheduled < durationMs) {
            val jitter = if (jitterMs > 0.0) (rng.nextDouble() * 2.0 - 1.0) * jitterMs else 0.0
            sends.add(SendEvent((scheduled + jitter).coerceAtLeast(0.0), sequence, senderTimestamp))
            scheduled += sendIntervalMs
            sequence++
            senderTimestamp += CHUNK_MS.toUInt()
        }
        sends.sortBy { it.arrivalMs }

        val depthSamples = ArrayList<Int>()
        var nextPopAt = 0.0
        var sendIndex = 0

        while (sendIndex < sends.size || nextPopAt < durationMs) {
            val nextSendAt = if (sendIndex < sends.size) sends[sendIndex].arrivalMs else Double.MAX_VALUE
            if (nextSendAt <= nextPopAt) {
                val event = sends[sendIndex++]
                now = maxOf(now, event.arrivalMs)
                buffer.push(event.sequence, ByteArray(CHUNK_BYTES), event.timestampMs)
            } else {
                if (nextPopAt >= durationMs) break
                now = maxOf(now, nextPopAt)
                // Sampled before the pop, so this matches the depth the
                // control loop itself sees — measuring after removal would
                // read one chunk low and misrepresent where it settles.
                val depth = buffer.bufferedCount
                val chunk = buffer.pop()
                if (chunk == null) {
                    nextPopAt += PREBUFFER_POLL_MS
                } else {
                    depthSamples.add(depth)
                    nextPopAt += (chunk.size / BYTES_PER_FRAME) / FRAMES_PER_MS
                }
            }
        }

        // Ignore the first 10% as start-up transient.
        val warmed = depthSamples.drop(depthSamples.size / 10)
        val steadyStateWindow = warmed.takeLast(warmed.size / 4).ifEmpty { warmed }
        return Result(
            framesDropped = buffer.framesDropped,
            framesDuplicated = buffer.framesDuplicated,
            observedDriftPpm = buffer.observedDriftPpm,
            steadyStateDepth = steadyStateWindow.average(),
            minDepthAfterWarmup = warmed.minOrNull() ?: 0,
            maxDepthAfterWarmup = warmed.maxOrNull() ?: 0,
        )
    }

    @Test
    fun `a fast sender is corrected by dropping frames, not by overflowing`() {
        val corrected = simulate(driftPpm = 100.0)

        assertTrue(
            "expected frames to be dropped to drain the backlog, got ${corrected.framesDropped}",
            corrected.framesDropped > 0,
        )
        assertEquals("a fast sender should never need frames duplicated", 0L, corrected.framesDuplicated)
        assertTrue(
            "steady-state depth ${corrected.steadyStateDepth} should stay near the target $TARGET_DEPTH",
            abs(corrected.steadyStateDepth - TARGET_DEPTH) < 1.0,
        )
    }

    @Test
    fun `a slow sender is corrected by duplicating frames, not by starving`() {
        val corrected = simulate(driftPpm = -100.0)

        assertTrue(
            "expected frames to be duplicated to refill the buffer, got ${corrected.framesDuplicated}",
            corrected.framesDuplicated > 0,
        )
        assertEquals("a slow sender should never need frames dropped", 0L, corrected.framesDropped)
        assertTrue(
            "steady-state depth ${corrected.steadyStateDepth} should stay near the target $TARGET_DEPTH",
            abs(corrected.steadyStateDepth - TARGET_DEPTH) < 1.0,
        )
    }

    /**
     * The point of the whole exercise: with correction off, depth walks away
     * from the target and never comes back. This is the regression that would
     * otherwise only show up as growing latency an hour into a session.
     */
    @Test
    fun `correction is what keeps depth bounded — without it the buffer diverges`() {
        val uncorrected = simulate(driftPpm = 100.0, correctionEnabled = false)
        val corrected = simulate(driftPpm = 100.0, correctionEnabled = true)

        val uncorrectedError = abs(uncorrected.steadyStateDepth - TARGET_DEPTH)
        val correctedError = abs(corrected.steadyStateDepth - TARGET_DEPTH)

        assertTrue(
            "uncorrected depth ${uncorrected.steadyStateDepth} should have drifted well past the target",
            uncorrectedError > 2.0,
        )
        assertTrue(
            "corrected depth ${corrected.steadyStateDepth} should be far closer to target than " +
                "uncorrected ${uncorrected.steadyStateDepth}",
            correctedError < uncorrectedError / 2.0,
        )
    }

    /**
     * Network jitter is not drift, and correction must not chase it.
     *
     * Note this asserts "negligible", not "zero". A deadband controller under
     * zero-mean noise will occasionally tick, and there's a real effect
     * underneath: a late packet is concealed as silence, so heavy jitter
     * genuinely does bleed a little buffer depth, and answering that is
     * correct behaviour rather than a bug. What matters is that a jittery
     * but driftless link provokes an order of magnitude less correction than
     * a genuinely drifting one — the deadband is doing its job.
     *
     * +/-10ms of jitter on 10ms chunks is a deliberately harsh link; typical
     * LAN jitter is a fraction of that.
     */
    @Test
    fun `zero-mean jitter without drift provokes far less correction than real drift`() {
        val duration = 120_000.0
        val jittery = simulate(driftPpm = 0.0, jitterMs = 10.0, durationMs = duration)
        val drifting = simulate(driftPpm = 100.0, durationMs = duration)

        val jitteryCorrections = jittery.framesDropped + jittery.framesDuplicated
        val driftingCorrections = drifting.framesDropped + drifting.framesDuplicated

        assertTrue("precondition: real drift should provoke correction", driftingCorrections > 0)
        assertTrue(
            "jitter provoked $jitteryCorrections corrections vs $driftingCorrections for real drift — " +
                "the deadband is not filtering noise",
            jitteryCorrections < driftingCorrections / 5,
        )
    }

    @Test
    fun `drift is measured from packet timestamps`() {
        val fast = simulate(driftPpm = 100.0, durationMs = 60_000.0)
        val slow = simulate(driftPpm = -100.0, durationMs = 60_000.0)

        assertTrue(
            "expected roughly +100ppm, measured ${fast.observedDriftPpm}",
            abs(fast.observedDriftPpm - 100.0) < 20.0,
        )
        assertTrue(
            "expected roughly -100ppm, measured ${slow.observedDriftPpm}",
            abs(slow.observedDriftPpm + 100.0) < 20.0,
        )
    }

    @Test
    fun `no correction happens before enough session time has been observed`() {
        // Three seconds is below the drift window, so however wrong the clock
        // is, the buffer must not have touched a single frame yet.
        val result = simulate(driftPpm = 500.0, durationMs = 3_000.0)

        assertEquals(0L, result.framesDropped)
        assertEquals(0L, result.framesDuplicated)
        assertEquals("drift is not reportable inside the window either", 0.0, result.observedDriftPpm, 0.0)
    }

    @Test
    fun `correction never empties or overflows a chunk`() {
        val result = simulate(driftPpm = 150.0)

        assertTrue("buffer should never starve to empty", result.minDepthAfterWarmup > 0)
        assertTrue(
            "buffer should stay well under the 32-chunk cap, peaked at ${result.maxDepthAfterWarmup}",
            result.maxDepthAfterWarmup < 32,
        )
    }

    @Test
    fun `reset clears accumulated drift state`() {
        var now = 0L
        val buffer = JitterBuffer(
            chunkSizeBytes = CHUNK_BYTES,
            targetDepthChunks = 1,
            bytesPerFrame = BYTES_PER_FRAME,
            nowMs = { now },
        )

        // Sender stamps 11ms per chunk while only 10ms of receiver time
        // passes — a deliberately gross drift so the window is unmistakably open.
        var timestamp = 0u
        repeat(2000) {
            buffer.push(it.toUInt(), ByteArray(CHUNK_BYTES), timestamp)
            timestamp += 11u
            now += CHUNK_MS.toLong()
            buffer.pop()
        }
        assertTrue("precondition: a drift window should be open", buffer.observedDriftPpm != 0.0)

        buffer.reset()
        assertEquals("drift window must not survive a reconnect", 0.0, buffer.observedDriftPpm, 0.0)
    }
}
