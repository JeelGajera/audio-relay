package com.audiorelay.app.audio

import java.util.TreeMap

/**
 * Sequence-aware jitter buffer with clock-drift correction. Pure Kotlin, no
 * Android dependencies, so it can be unit-tested on the plain JVM (see
 * `src/test/.../JitterBufferTest.kt`).
 *
 * See `docs/architecture.md` §3.2 and §6: this targets a small fixed depth
 * (a few chunks, ~20-40ms at typical 10ms chunk sizes) before it starts
 * releasing audio, and conceals a gap (loss or a chunk that never arrives
 * in time) with silence rather than repeating the last chunk — repeating
 * audibly "stutters" more than a brief dip (protocol-spec.md's sender side
 * of this tradeoff is documented in windows-app's capture module).
 *
 * ## Clock-drift correction (docs/roadmap.md Phase 5)
 *
 * The laptop's capture clock and this phone's playback clock are never
 * exactly equal — crystal oscillators differ by tens of ppm. Left alone
 * that is a slow one-way leak: if the sender runs fast the buffer fills
 * until it hits [maxBufferedChunks] and drops a whole chunk (an audible
 * click, plus latency that grew the whole time); if it runs slow the buffer
 * drains until every `pop` is concealment silence.
 *
 * The correction is a slow control loop over buffer depth:
 *
 * - **Control signal** — an EWMA of buffer depth sampled at each [pop].
 *   Depth is the integral of the rate mismatch, so regulating it back to
 *   [targetDepthChunks] cancels the drift regardless of its size or sign.
 * - **Actuator** — one PCM *frame* dropped from, or duplicated into, an
 *   outgoing chunk. One frame at 48kHz is ~21µs; a whole chunk is ~10ms.
 *   Correcting a frame at a time is inaudible where correcting a chunk at a
 *   time is exactly the click we're trying to avoid.
 * - **Readiness gate** — packet timestamps (`protocol-spec.md` §3, the
 *   `timestamp_ms` header field). Correction stays off until we have
 *   observed a real [MIN_DRIFT_WINDOW_MS] of session time, so startup
 *   transients never trigger frame surgery.
 * - **Hysteresis and rate limiting** — a [DEPTH_HYSTERESIS_CHUNKS]
 *   deadband, and at most one frame per [MIN_POPS_BETWEEN_CORRECTIONS]
 *   pops. That caps correction authority at roughly ±200ppm with 10ms
 *   chunks, comfortably above real crystal drift while keeping ordinary
 *   network jitter from provoking any correction at all.
 *
 * [observedDriftPpm] reports the drift measured from those timestamps. Note
 * it compares the *sender's* clock against this device's wall clock, while
 * what actually moves buffer depth is the sender's clock against the audio
 * DAC's clock — related but not identical oscillators. It is therefore
 * reported as a diagnostic, and deliberately not used as the control
 * signal; buffer depth is measured directly and is the honest feedback.
 */
class JitterBuffer(
    private val chunkSizeBytes: Int,
    private val targetDepthChunks: Int = 3,
    private val maxBufferedChunks: Int = 32,
    /** Bytes per PCM frame — `channels * 2` for 16-bit audio. The correction granularity. */
    private val bytesPerFrame: Int = DEFAULT_BYTES_PER_FRAME,
    private val driftCorrectionEnabled: Boolean = true,
    /** Injectable clock so drift behaviour is deterministically testable. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val chunks = TreeMap<UInt, ByteArray>()
    private var nextSequence: UInt? = null
    private var started = false

    // Drift measurement, from packet timestamps.
    private var latestSenderTimestampMs: UInt? = null
    private var senderElapsedMs = 0L
    private var windowStartLocalMs = 0L
    private var windowLatestLocalMs = 0L

    // Depth control.
    private var smoothedDepth = Double.NaN
    private var popsSinceCorrection = 0

    val bufferedCount: Int get() = chunks.size

    /** Frames removed from outgoing chunks to drain a persistently over-full buffer. */
    var framesDropped: Long = 0L
        private set

    /** Frames repeated into outgoing chunks to refill a persistently starved buffer. */
    var framesDuplicated: Long = 0L
        private set

    /**
     * Measured sender-vs-receiver clock drift in parts per million, positive
     * when the sender's clock is running fast. Zero until [MIN_DRIFT_WINDOW_MS]
     * of session time has been observed. Diagnostic only — see the class
     * docs for why this is not the control signal.
     */
    val observedDriftPpm: Double
        get() {
            val localElapsed = windowLatestLocalMs - windowStartLocalMs
            if (localElapsed < MIN_DRIFT_WINDOW_MS) return 0.0
            return (senderElapsedMs - localElapsed) * 1_000_000.0 / localElapsed
        }

    /**
     * Adds a decoded chunk. Silently drops it if it arrives after we've
     * already released that sequence number (too late to be useful) or if
     * the buffer is already at its cap (bounds memory/latency if the
     * network is delivering faster than we're draining, which shouldn't
     * normally happen but shouldn't be allowed to grow unbounded either).
     *
     * [timestampMs] is the packet's `timestamp_ms` header field. Passing it
     * enables drift correction; omitting it leaves the buffer in its plain
     * fixed-depth behaviour.
     */
    fun push(sequence: UInt, pcm: ByteArray, timestampMs: UInt? = null) {
        if (timestampMs != null) recordSenderTimestamp(timestampMs)

        val next = nextSequence
        if (next != null && sequenceIsBefore(sequence, next)) {
            return // too-late or duplicate packet
        }
        if (chunks.size >= maxBufferedChunks && !chunks.containsKey(sequence)) {
            chunks.remove(chunks.firstKey())
        }
        chunks[sequence] = pcm
    }

    /**
     * Returns the next chunk to play, or null while still pre-buffering up
     * to [targetDepthChunks]. Once started, always returns a chunk — silence
     * (all-zero) if the expected sequence number hasn't arrived yet.
     *
     * The returned array is normally [chunkSizeBytes] long, but may differ
     * by one frame when drift correction fires. Callers must write
     * `array.size` bytes rather than assuming a fixed length.
     */
    fun pop(): ByteArray? {
        if (!started) {
            if (chunks.size < targetDepthChunks) return null
            started = true
            nextSequence = chunks.firstKey()
        }
        val seq = nextSequence ?: return null

        sampleDepth()

        val chunk = chunks.remove(seq) ?: ByteArray(chunkSizeBytes) // loss concealment: silence, not a repeat
        nextSequence = seq + 1u
        return correctForDrift(chunk)
    }

    /** Resets to the pre-buffering state — call this after a reconnect. */
    fun reset() {
        chunks.clear()
        nextSequence = null
        started = false
        latestSenderTimestampMs = null
        senderElapsedMs = 0L
        windowStartLocalMs = 0L
        windowLatestLocalMs = 0L
        smoothedDepth = Double.NaN
        popsSinceCorrection = 0
    }

    /**
     * Accumulates forward progress of the sender's clock. Only advances on a
     * new maximum timestamp, which makes it immune to reordered packets —
     * accumulating every delta would double-count when a late packet lands
     * between two newer ones.
     */
    private fun recordSenderTimestamp(timestampMs: UInt) {
        val local = nowMs()
        val previous = latestSenderTimestampMs

        if (previous == null) {
            windowStartLocalMs = local
            windowLatestLocalMs = local
            latestSenderTimestampMs = timestampMs
            return
        }

        val delta = (timestampMs - previous).toInt() // wraparound-correct, negative for reordered packets
        if (delta <= 0) return // not new progress
        if (delta > MAX_PLAUSIBLE_GAP_MS) {
            // A stall this long means the estimate can't be trusted; start a
            // fresh window rather than fold the gap into the drift figure.
            senderElapsedMs = 0L
            windowStartLocalMs = local
            windowLatestLocalMs = local
            latestSenderTimestampMs = timestampMs
            return
        }

        senderElapsedMs += delta
        windowLatestLocalMs = local
        latestSenderTimestampMs = timestampMs
    }

    private fun sampleDepth() {
        val depth = chunks.size.toDouble()
        smoothedDepth = if (smoothedDepth.isNaN()) depth else smoothedDepth + DEPTH_SMOOTHING * (depth - smoothedDepth)
    }

    private fun correctForDrift(chunk: ByteArray): ByteArray {
        popsSinceCorrection++
        if (!correctionIsAvailable()) return chunk

        val error = smoothedDepth - targetDepthChunks
        return when {
            // Persistently too deep: the sender is outrunning us. Shorten a
            // chunk so playback consumes the backlog slightly faster.
            error > DEPTH_HYSTERESIS_CHUNKS -> {
                popsSinceCorrection = 0
                framesDropped++
                chunk.copyOf(chunk.size - bytesPerFrame)
            }
            // Persistently too shallow: we're outrunning the sender. Stretch
            // a chunk so playback gives the buffer time to refill.
            error < -DEPTH_HYSTERESIS_CHUNKS -> {
                popsSinceCorrection = 0
                framesDuplicated++
                val stretched = ByteArray(chunk.size + bytesPerFrame)
                chunk.copyInto(stretched, 0)
                chunk.copyInto(stretched, chunk.size, chunk.size - bytesPerFrame, chunk.size)
                stretched
            }
            else -> chunk
        }
    }

    private fun correctionIsAvailable(): Boolean {
        if (!driftCorrectionEnabled) return false
        if (popsSinceCorrection < MIN_POPS_BETWEEN_CORRECTIONS) return false
        // A chunk has to be at least two frames long for removing one to
        // leave anything behind.
        if (bytesPerFrame <= 0 || chunkSizeBytes < bytesPerFrame * 2) return false
        if (chunkSizeBytes % bytesPerFrame != 0) return false
        return windowLatestLocalMs - windowStartLocalMs >= MIN_DRIFT_WINDOW_MS
    }

    /**
     * `true` if `a` is strictly before `b`, correctly handling u32
     * sequence wraparound (protocol-spec.md §3): a session running long
     * enough to wrap ~4.29 billion packets is not realistic, but the
     * comparison should still be correct rather than silently wrong near
     * the boundary.
     */
    private fun sequenceIsBefore(a: UInt, b: UInt): Boolean = (a - b).toInt() < 0

    companion object {
        /** 16-bit stereo. */
        const val DEFAULT_BYTES_PER_FRAME = 4

        /**
         * EWMA weight for the depth estimate. At ~100 pops/sec (10ms chunks)
         * this is roughly a two-second time constant — slow enough that
         * burst jitter averages out, fast enough to track real drift, which
         * moves over minutes.
         */
        private const val DEPTH_SMOOTHING = 0.005

        /**
         * Deadband around the target, in chunks. Sized so ordinary network
         * jitter lives inside it: correction should be answering sustained
         * clock error, not chasing a noisy signal.
         */
        private const val DEPTH_HYSTERESIS_CHUNKS = 0.75

        /**
         * Correction authority: one frame per this many pops. At 10ms chunks
         * that is ~±200ppm, well above real crystal drift (tens of ppm).
         */
        private const val MIN_POPS_BETWEEN_CORRECTIONS = 10

        /** Session time that must be observed before correction may engage. */
        private const val MIN_DRIFT_WINDOW_MS = 5_000L

        /** A sender gap longer than this invalidates the drift window. */
        private const val MAX_PLAUSIBLE_GAP_MS = 5_000
    }
}
