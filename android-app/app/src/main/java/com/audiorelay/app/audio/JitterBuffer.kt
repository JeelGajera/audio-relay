package com.audiorelay.app.audio

import java.util.TreeMap

/**
 * Sequence-aware jitter buffer. Pure Kotlin, no Android dependencies, so it
 * can be unit-tested on the plain JVM (see `src/test/.../JitterBufferTest.kt`).
 *
 * See `docs/architecture.md` §3.2 and §6: this targets a small fixed depth
 * (a few chunks, ~20-40ms at typical 10ms chunk sizes) before it starts
 * releasing audio, and conceals a gap (loss or a chunk that never arrives
 * in time) with silence rather than repeating the last chunk — repeating
 * audibly "stutters" more than a brief dip (protocol-spec.md's sender side
 * of this tradeoff is documented in windows-app's capture module).
 *
 * Adaptive/tunable depth (docs/roadmap.md Phase 5) is not implemented yet —
 * `targetDepthChunks` is fixed for the lifetime of one instance.
 */
class JitterBuffer(
    private val chunkSizeBytes: Int,
    private val targetDepthChunks: Int = 3,
    private val maxBufferedChunks: Int = 32,
) {
    private val chunks = TreeMap<UInt, ByteArray>()
    private var nextSequence: UInt? = null
    private var started = false

    val bufferedCount: Int get() = chunks.size

    /**
     * Adds a decoded chunk. Silently drops it if it arrives after we've
     * already released that sequence number (too late to be useful) or if
     * the buffer is already at its cap (bounds memory/latency if the
     * network is delivering faster than we're draining, which shouldn't
     * normally happen but shouldn't be allowed to grow unbounded either).
     */
    fun push(sequence: UInt, pcm: ByteArray) {
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
     * to [targetDepthChunks]. Once started, always returns a chunk of
     * [chunkSizeBytes] — silence (all-zero) if the expected sequence number
     * hasn't arrived yet.
     */
    fun pop(): ByteArray? {
        if (!started) {
            if (chunks.size < targetDepthChunks) return null
            started = true
            nextSequence = chunks.firstKey()
        }
        val seq = nextSequence ?: return null
        val chunk = chunks.remove(seq)
        nextSequence = seq + 1u
        return chunk ?: ByteArray(chunkSizeBytes) // packet-loss concealment: silence, not a repeat
    }

    /** Resets to the pre-buffering state — call this after a reconnect. */
    fun reset() {
        chunks.clear()
        nextSequence = null
        started = false
    }

    /**
     * `true` if `a` is strictly before `b`, correctly handling u32
     * sequence wraparound (protocol-spec.md §3): a session running long
     * enough to wrap ~4.29 billion packets is not realistic, but the
     * comparison should still be correct rather than silently wrong near
     * the boundary.
     */
    private fun sequenceIsBefore(a: UInt, b: UInt): Boolean = (a - b).toInt() < 0
}
