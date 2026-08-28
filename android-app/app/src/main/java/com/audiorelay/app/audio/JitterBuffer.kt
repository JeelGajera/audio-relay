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
 * of this tradeoff is documented in desktop-app's capture module).
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
    chunkSizeBytes: Int,
    /**
     * Fallback depth, in chunks, used only when [targetDepthMs] is null.
     * Buffer depth is really a duration, not a packet count — see
     * [targetDepthMs] — but the buffer's own bookkeeping is per-chunk, and
     * expressing it directly is convenient in unit tests.
     */
    private val targetDepthChunks: Int = 3,
    /**
     * Baseline eviction cap. The effective cap is this or three times the
     * configured depth, whichever is larger — see [maxBufferedChunks].
     */
    private val baseMaxBufferedChunks: Int = 32,
    /** Bytes per PCM frame — `channels * 2` for 16-bit audio. The correction granularity. */
    private val bytesPerFrame: Int = DEFAULT_BYTES_PER_FRAME,
    private val driftCorrectionEnabled: Boolean = true,
    /** Injectable clock so drift behaviour is deterministically testable. */
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * How much audio to hold before playing, in milliseconds — the honest
     * unit for this setting, and what callers should use.
     *
     * Counting *packets* instead was a real bug: a packet's duration
     * depends on the sender's latency mode and on its MTU split, so the
     * shipped default of 3 chunks silently meant only ~18ms of buffer,
     * with even the maximum setting reaching just ~36ms. Ordinary Wi-Fi
     * jitter — let alone a phone hotspot — routinely exceeds that, so the
     * buffer underran constantly and played concealment silence instead.
     * Given in milliseconds the depth means the same thing regardless of
     * how the sender happens to be packetising.
     */
    private val targetDepthMs: Int? = null,
    /** Bytes of PCM per millisecond, i.e. `sampleRateHz * bytesPerFrame / 1000`. */
    private val bytesPerMs: Int = 0,
) {
    private val chunks = TreeMap<UInt, ByteArray>()
    private var nextSequence: UInt? = null
    private var started = false

    /**
     * How long a concealment chunk should be. Seeded from the constructor's
     * best guess, then corrected to whatever the sender is *actually*
     * sending as soon as the first packet lands.
     *
     * It cannot be a fixed constructor value: the sender's packet size
     * depends on its latency mode (~5ms vs ~10ms chunks) and on the MTU
     * split in `desktop-app`'s `AudioSender::send_frame`, none of which the
     * receiver is told up front. Concealing a 6ms gap with 10ms of silence
     * inserts 4ms of audio out of nothing on every single lost packet,
     * which is a drift the correction loop then has to fight.
     */
    private var chunkSizeBytes: Int = chunkSizeBytes

    /** Candidate size seen once; adopted only if it repeats. See [observeChunkSize]. */
    private var pendingChunkSizeBytes: Int = 0

    /**
     * Consecutive concealments, i.e. how many expected chunks in a row were
     * missing. Drives the starvation resync in [pop] — see [resync].
     */
    private var consecutiveConcealments = 0

    /** How many times [resync] has fired. Diagnostic; also asserted in tests. */
    var resyncCount: Long = 0L
        private set

    /** How many times the buffer paused to rebuild depth after starving. */
    var refillCount: Long = 0L
        private set

    /** How many times [trimToTarget] has fired. Diagnostic; also asserted in tests. */
    var latencyTrimCount: Long = 0L
        private set

    /** Chunks played as concealment silence because they never arrived in time. */
    var concealedCount: Long = 0L
        private set

    /** Chunks played from real received audio. */
    var playedCount: Long = 0L
        private set

    /** Packets discarded on arrival as too late to be useful. */
    var lateCount: Long = 0L
        private set

    /** A one-line health summary for logs — see `AudioReceiver.playbackLoop`. */
    fun healthSummary(): String {
        val total = playedCount + concealedCount
        val lossPct = if (total == 0L) 0.0 else concealedCount * 100.0 / total
        return "depth=%d/%d (+%dms adaptive) concealed=%.1f%% (%d/%d) late=%d resyncs=%d trims=%d refills=%d drift=%.0fppm"
            .format(
                chunks.size, effectiveTargetChunks, adaptiveExtraMs, lossPct,
                concealedCount, total, lateCount, resyncCount, latencyTrimCount,
                refillCount, observedDriftPpm,
            )
    }

    /**
     * [targetDepthMs] rendered in whole chunks of whatever the sender is
     * currently sending. Recomputed rather than cached because
     * [chunkSizeBytes] is learned at runtime and can change if the sender
     * switches latency mode mid-stream.
     */
    private val effectiveTargetChunks: Int
        get() {
            val ms = targetDepthMs
            if (ms == null || bytesPerMs <= 0 || chunkSizeBytes <= 0) {
                return targetDepthChunks + adaptiveExtraChunks
            }
            // Round up: better a hair more latency than a buffer that is
            // one packet short of the depth actually asked for.
            val chunks = (ms * bytesPerMs + chunkSizeBytes - 1) / chunkSizeBytes
            return (chunks + adaptiveExtraChunks).coerceAtLeast(1)
        }

    /**
     * Extra depth the buffer has taken on itself because the configured
     * amount was not covering this link's delay variance.
     *
     * A fixed target only works if you know the network in advance, and the
     * failure is quietly awful when it is too small: packets miss their
     * playout deadline, get concealed, and then arrive — counting as "late"
     * and concealed at once. Measured on a real hotspot at a 120ms setting,
     * that was 58% concealment with the buffer sitting *at* its target the
     * whole time, because the packets held were future ones while the
     * needed packet was still in flight. No amount of depth *management*
     * fixes that; only more depth does.
     *
     * So lateness drives this upward quickly, and a sustained clean stretch
     * lets it drift back down, keeping latency no higher than the link
     * actually requires.
     */
    private var adaptiveExtraChunks = 0

    /** How much [adaptiveExtraChunks] has added, in ms — diagnostic. */
    val adaptiveExtraMs: Int
        get() = if (bytesPerMs <= 0) 0 else adaptiveExtraChunks * chunkSizeBytes / bytesPerMs

    // Rolling window driving the adaptation.
    private var windowPops = 0
    private var windowLate = 0
    private var windowConcealed = 0

    /**
     * Re-evaluates [adaptiveExtraChunks] once per window.
     *
     * Grows on *lateness* specifically, not on concealment generally: a
     * concealed chunk that never arrives at all is real packet loss, and no
     * amount of buffering recovers it, so growing for that would trade
     * latency for nothing.
     */
    private fun adaptDepth() {
        windowPops++
        if (windowPops < ADAPT_WINDOW_POPS) return

        val target = effectiveTargetChunks
        val ceiling = if (bytesPerMs > 0 && chunkSizeBytes > 0) {
            MAX_ADAPTIVE_EXTRA_MS * bytesPerMs / chunkSizeBytes
        } else {
            MAX_ADAPTIVE_EXTRA_CHUNKS
        }

        if (windowLate > windowPops * LATE_FRACTION_TO_GROW) {
            // Packets are missing their deadline. Add roughly a quarter of
            // the current depth — enough to actually change the outcome,
            // since creeping up one packet at a time would spend minutes
            // concealing on the way.
            adaptiveExtraChunks = (adaptiveExtraChunks + (target / 4).coerceAtLeast(1))
                .coerceAtMost(ceiling)
        } else if (windowConcealed == 0 && adaptiveExtraChunks > 0) {
            // A completely clean window: give a little latency back. Slower
            // than it grows, so a link that is merely quiet for a moment
            // does not cost the user a fresh round of dropouts.
            adaptiveExtraChunks--
        }

        windowPops = 0
        windowLate = 0
        windowConcealed = 0
    }

    /**
     * Hard cap on buffered chunks, scaled to the configured depth. A fixed
     * cap would silently truncate a large buffer setting — at ~6ms per
     * packet the old constant 32 could not even hold 200ms — so it tracks
     * the target with enough headroom to absorb a burst without evicting
     * audio we are about to play.
     */
    private val maxBufferedChunks: Int
        get() = maxOf(baseMaxBufferedChunks, effectiveTargetChunks * 3)

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
        if (pcm.isNotEmpty()) observeChunkSize(pcm.size)

        val next = nextSequence
        if (next != null) {
            // Signed distance, wraparound-correct: negative means this packet
            // is behind where we're playing, positive means ahead.
            val distance = (sequence - next).toInt()
            if (distance < -RESYNC_DISTANCE_CHUNKS || distance > RESYNC_DISTANCE_CHUNKS) {
                // The stream is nowhere near our play position. Rejecting
                // these as "late" is what used to wedge the buffer
                // permanently: once `nextSequence` ran past the sender (a
                // stalled playback loop spinning through sequence numbers,
                // or a long stall leaving us far behind), *every* subsequent
                // packet looked too-late, so the buffer stayed empty and
                // playback stayed silent forever while the sender happily
                // kept transmitting. Treat it as a new stream position and
                // rebuild from here instead.
                resync()
            } else if (distance < 0) {
                lateCount++
                windowLate++
                return // ordinary late or duplicate packet
            }
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
            if (chunks.size < effectiveTargetChunks) return null
            started = true
            nextSequence = chunks.firstKey()
            consecutiveConcealments = 0
        }
        // Standing latency, not jitter: if the buffer is sitting far deeper
        // than asked for, every one of those extra chunks is delay the
        // listener keeps paying on every packet from here on.
        if (chunks.size > latencyTrimThreshold) {
            trimToTarget()
        }

        val seq = nextSequence ?: return null

        sampleDepth()
        adaptDepth()

        if (chunks.isEmpty()) {
            // Nothing buffered at all. Concealing here and advancing anyway
            // is actively harmful: it burns through sequence numbers, so the
            // packets still in flight arrive "late" and get discarded, which
            // starves the buffer further — a feedback loop that measured as
            // 96% of all concealments being packets that had actually
            // arrived. It is also what made raising the target useless,
            // since nothing ever rebuilt depth once playback had started.
            //
            // Pausing instead lets the buffer refill to its target before
            // resuming. That costs one audible gap and then plays cleanly,
            // rather than concealing indefinitely.
            started = false
            refillCount++
            return null
        }

        val buffered = chunks.remove(seq)
        if (buffered == null) {
            consecutiveConcealments++
            concealedCount++
            windowConcealed++
            // Sustained starvation the sequence-distance check in `push`
            // can't see: if we fall behind by more than the buffer can hold
            // but less than RESYNC_DISTANCE_CHUNKS, every arriving packet is
            // evicted as "too far ahead" before we reach it, so we conceal
            // forever while packets keep flowing. Half a second of unbroken
            // silence means the stream is not going to recover on its own.
            if (consecutiveConcealments >= MAX_CONSECUTIVE_CONCEALMENTS) {
                resync()
                return ByteArray(chunkSizeBytes)
            }
            nextSequence = seq + 1u
            return correctForDrift(ByteArray(chunkSizeBytes)) // concealment: silence, not a repeat
        }

        consecutiveConcealments = 0
        playedCount++
        nextSequence = seq + 1u
        return correctForDrift(buffered)
    }

    /**
     * Depth at which standing latency is corrected by skipping rather than
     * waited out. Generous enough that ordinary jitter never reaches it,
     * since every trim costs a small audible skip.
     */
    private val latencyTrimThreshold: Int
        get() {
            val target = effectiveTargetChunks
            // Double the target, not a fraction over it. A trim discards
            // audio, so it must answer only genuine standing backlog —
            // "we are running at twice the delay we asked for" — never
            // ordinary jitter riding above the target for a moment.
            return maxOf(target * 2, target + LATENCY_TRIM_MIN_MARGIN_CHUNKS)
        }

    /**
     * Adopts a new observed packet size, but only once it repeats.
     *
     * Every derived quantity — target depth, trim threshold, concealment
     * length — is computed from this, so letting a single differently-sized
     * packet change it makes all of them oscillate. That is exactly what an
     * uneven MTU split used to do (alternating 1168- and 752-byte packets
     * swung the target between 20 and 31 chunks), which tripped the latency
     * trim on healthy audio every few seconds. The sender now splits evenly,
     * and this debounce means no future packetisation can reintroduce the
     * problem — while a real, sustained change (the sender switching latency
     * mode) is still picked up on the second packet.
     */
    private fun observeChunkSize(size: Int) {
        if (size == chunkSizeBytes) {
            pendingChunkSizeBytes = 0
            return
        }
        if (size == pendingChunkSizeBytes) {
            chunkSizeBytes = size
            pendingChunkSizeBytes = 0
        } else {
            pendingChunkSizeBytes = size
        }
    }

    /**
     * Discards the oldest buffered audio so playback resumes
     * [effectiveTargetChunks] behind the newest packet — i.e. back at the
     * depth actually configured.
     *
     * This exists because the drift correction cannot do it. That loop
     * moves one PCM *frame* per ten pops, which is right for cancelling a
     * few tens of ppm of crystal drift and hopelessly wrong for shedding
     * accumulated backlog: draining 200ms of excess that way takes about
     * ten minutes, so in practice a single transient stall — a descheduled
     * receive loop, a Wi-Fi retransmit burst — became latency the listener
     * paid for the rest of the session. Skipping ahead costs one brief
     * discontinuity and then the delay is correct again, which is
     * overwhelmingly the better trade for live audio.
     */
    private fun trimToTarget() {
        val target = effectiveTargetChunks
        while (chunks.size > target) {
            chunks.pollFirstEntry()
        }
        // Resume from the oldest chunk we kept.
        nextSequence = chunks.firstKey()
        latencyTrimCount++
        // The depth average described the pre-trim regime; keeping it would
        // have the drift loop immediately fighting a level that no longer
        // exists.
        smoothedDepth = Double.NaN
        popsSinceCorrection = 0
        consecutiveConcealments = 0
    }

    /**
     * Drops back to the pre-buffering state mid-stream, after deciding the
     * buffer's idea of "where we are" no longer matches the sender's.
     *
     * Costs one brief gap while [targetDepthChunks] refills — versus the
     * alternative it replaces, which was silence until the user restarted
     * the app. Deliberately *keeps* the drift-measurement window: the two
     * clocks did not change just because a packet run was lost, and
     * restarting a five-second measurement window on every hiccup would
     * mean drift correction never engages at all on a lossy link.
     */
    private fun resync() {
        resyncCount++
        chunks.clear()
        nextSequence = null
        started = false
        consecutiveConcealments = 0
        smoothedDepth = Double.NaN
        popsSinceCorrection = 0
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
        consecutiveConcealments = 0
        resyncCount = 0L
        latencyTrimCount = 0L
        pendingChunkSizeBytes = 0
        concealedCount = 0L
        playedCount = 0L
        lateCount = 0L
        adaptiveExtraChunks = 0
        refillCount = 0L
        windowPops = 0
        windowLate = 0
        windowConcealed = 0
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
        // Guard on the chunk actually in hand, not just the expected size:
        // the sender can change latency mode mid-stream, so the next packet
        // may be shorter than the one that set `chunkSizeBytes`. Trimming a
        // frame off something smaller than two frames would produce an
        // empty or negative-length copy.
        if (chunk.size < bytesPerFrame * 2 || chunk.size % bytesPerFrame != 0) return chunk

        val error = smoothedDepth - effectiveTargetChunks
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

        /**
         * How far a packet's sequence number may sit from the one we expect
         * before we stop calling it "jitter" and call it a desync. Ordinary
         * reordering is a handful of chunks; [maxBufferedChunks] is 32. At
         * roughly 6ms per chunk this is over a second of divergence, which
         * no healthy link produces.
         */
        private const val RESYNC_DISTANCE_CHUNKS = 200

        /**
         * Unbroken concealed chunks before we give up on the current play
         * position. ~500ms at 10ms chunks — long enough that ordinary bursts
         * of loss ride through on concealment alone, short enough that a
         * genuinely wedged stream recovers before a listener reaches for
         * the app.
         */
        private const val MAX_CONSECUTIVE_CONCEALMENTS = 50

        /**
         * Smallest margin over the target before a latency trim fires, for
         * shallow targets where half the target would be a chunk or two and
         * would trim on ordinary jitter.
         */
        private const val LATENCY_TRIM_MIN_MARGIN_CHUNKS = 8

        /** Pops per adaptation decision — a second or two of audio. */
        private const val ADAPT_WINDOW_POPS = 400

        /** Late-packet fraction within a window that justifies more depth. */
        private const val LATE_FRACTION_TO_GROW = 0.02

        /**
         * Ceiling on self-added depth. Past this the link is too poor for
         * live audio and piling on latency only makes it worse to use.
         */
        private const val MAX_ADAPTIVE_EXTRA_MS = 400
        private const val MAX_ADAPTIVE_EXTRA_CHUNKS = 64
    }
}
