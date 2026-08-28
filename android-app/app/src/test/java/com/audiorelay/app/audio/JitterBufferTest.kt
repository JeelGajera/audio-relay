package com.audiorelay.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JitterBufferTest {
    private val chunkSize = 4
    private fun chunk(fill: Byte) = ByteArray(chunkSize) { fill }

    @Test
    fun `withholds chunks until target depth is reached`() {
        val buffer = JitterBuffer(chunkSize, targetDepthChunks = 3)
        buffer.push(0u, chunk(1))
        assertNull(buffer.pop())
        buffer.push(1u, chunk(2))
        assertNull(buffer.pop())
        buffer.push(2u, chunk(3))
        assertArrayEquals(chunk(1), buffer.pop())
    }

    @Test
    fun `pops chunks in sequence order`() {
        val buffer = JitterBuffer(chunkSize, targetDepthChunks = 2)
        buffer.push(0u, chunk(1))
        buffer.push(1u, chunk(2))
        buffer.push(2u, chunk(3))
        assertArrayEquals(chunk(1), buffer.pop())
        assertArrayEquals(chunk(2), buffer.pop())
        assertArrayEquals(chunk(3), buffer.pop())
    }

    @Test
    fun `conceals a missing chunk with silence rather than blocking`() {
        val buffer = JitterBuffer(chunkSize, targetDepthChunks = 2)
        buffer.push(0u, chunk(1))
        // sequence 1 never arrives
        buffer.push(2u, chunk(3))
        assertArrayEquals(chunk(1), buffer.pop())
        assertArrayEquals(ByteArray(chunkSize), buffer.pop()) // silence for the gap
        assertArrayEquals(chunk(3), buffer.pop())
    }

    @Test
    fun `drops packets that arrive after their sequence was already released`() {
        val buffer = JitterBuffer(chunkSize, targetDepthChunks = 1)
        buffer.push(0u, chunk(1))
        assertArrayEquals(chunk(1), buffer.pop()) // starts, releases seq 0, now expects seq 1

        buffer.push(0u, chunk(99)) // late duplicate — must be dropped
        buffer.push(1u, chunk(2))
        assertArrayEquals(chunk(2), buffer.pop())
    }

    @Test
    fun `bounds memory when packets arrive faster than they drain`() {
        val buffer = JitterBuffer(chunkSize, targetDepthChunks = 1, baseMaxBufferedChunks = 4)
        for (seq in 0u until 20u) {
            buffer.push(seq, chunk(seq.toByte()))
        }
        assert(buffer.bufferedCount <= 4)
    }

    @Test
    fun `reset returns to the pre-buffering state`() {
        val buffer = JitterBuffer(chunkSize, targetDepthChunks = 1)
        buffer.push(0u, chunk(1))
        assertArrayEquals(chunk(1), buffer.pop())

        buffer.reset()
        assertNull(buffer.pop()) // pre-buffering again, even though nextSequence was 1 before reset

        buffer.push(5u, chunk(9)) // a fresh session can start at any sequence
        assertArrayEquals(chunk(9), buffer.pop())
    }

    @Test
    fun `handles sequence wraparound correctly`() {
        val buffer = JitterBuffer(chunkSize, targetDepthChunks = 1)
        val nearMax = UInt.MAX_VALUE - 1u
        buffer.push(nearMax, chunk(1))
        assertArrayEquals(chunk(1), buffer.pop()) // starts at nearMax, next expected = MAX_VALUE

        buffer.push(UInt.MAX_VALUE, chunk(2))
        buffer.push(0u, chunk(3)) // wrapped around
        assertArrayEquals(chunk(2), buffer.pop())
        assertArrayEquals(chunk(3), buffer.pop())
    }

    // ---------------------------------------------------------------------
    // Desync recovery. These cover the failure the relay actually hit in
    // the field: audio played for ~30s, then went permanently silent while
    // the sender kept transmitting normally.
    // ---------------------------------------------------------------------

    /**
     * The exact lockup. Something advances the play position far past the
     * sender (before the AudioTrack fix, a failing write spun the playback
     * loop through sequence numbers at CPU speed). Every subsequent packet
     * then looked "too late" and was dropped, so the buffer stayed empty
     * and playback stayed silent forever.
     */
    @Test
    fun `recovers when the play position has run far past the sender`() {
        val buffer = JitterBuffer(chunkSize, targetDepthChunks = 2)
        buffer.push(0u, chunk(1))
        buffer.push(1u, chunk(2))
        assertArrayEquals(chunk(1), buffer.pop())
        assertArrayEquals(chunk(2), buffer.pop())

        // Drain hard while nothing is arriving. The buffer must not let the
        // play position run away here — advancing through starvation is what
        // used to make every in-flight packet arrive "late" and be dropped.
        repeat(5_000) { buffer.pop() }

        // The sender is still going, from where it always was.
        buffer.push(2u, chunk(7))
        buffer.push(3u, chunk(8))

        // Whether it got here by pausing to refill or by resyncing, the
        // requirement is the same: real audio plays again rather than
        // silence forever.
        assertArrayEquals(chunk(7), buffer.pop())
        assertArrayEquals(chunk(8), buffer.pop())
        assertEquals(
            "the buffer must not run its play position away from the sender",
            true,
            buffer.refillCount > 0 || buffer.resyncCount > 0,
        )
    }

    /**
     * The other direction, and the one a plain sequence-distance check
     * cannot see: we fall behind by more than the buffer holds but less
     * than the resync distance, so arriving packets are evicted before the
     * play position ever reaches them. Sustained concealment is the only
     * signal that this is happening.
     */
    @Test
    fun `recovers from sustained starvation while packets keep arriving`() {
        val buffer = JitterBuffer(chunkSize, targetDepthChunks = 2, baseMaxBufferedChunks = 8)
        buffer.push(0u, chunk(1))
        buffer.push(1u, chunk(2))
        assertArrayEquals(chunk(1), buffer.pop())

        // A burst arrives far enough ahead to evict everything we still
        // expect, but not far enough to trip the distance check.
        for (seq in 60u until 68u) buffer.push(seq, chunk(9))

        // Drain: every pop conceals, because the expected sequences are gone.
        var recovered: ByteArray? = null
        repeat(120) {
            val out = buffer.pop()
            if (out != null && out.any { b -> b == 9.toByte() }) {
                recovered = out
                return@repeat
            }
        }

        assertEquals(
            "buffer never resynced; it would conceal forever",
            true,
            buffer.resyncCount > 0,
        )
        // And after resyncing it plays the audio that was actually available.
        buffer.push(200u, chunk(5))
        buffer.push(201u, chunk(6))
        val next = generateSequence { buffer.pop() }.take(20).firstOrNull { c -> c.any { b -> b == 5.toByte() } }
        assertEquals(true, next != null || recovered != null)
    }

    /** A resync must not be triggered by ordinary reordering or a short loss burst. */
    @Test
    fun `ordinary jitter and short loss bursts never trigger a resync`() {
        val buffer = JitterBuffer(chunkSize, targetDepthChunks = 2)
        buffer.push(0u, chunk(1))
        buffer.push(1u, chunk(2))
        buffer.pop()

        // Reordered arrivals, a few dropped chunks, duplicates — all normal.
        buffer.push(3u, chunk(4))
        buffer.push(2u, chunk(3))
        buffer.push(3u, chunk(4)) // duplicate
        repeat(10) { buffer.pop() }
        buffer.push(14u, chunk(6))
        repeat(5) { buffer.pop() }

        assertEquals(0L, buffer.resyncCount)
    }

    /**
     * Concealment silence has to match the sender's real packet size. The
     * sender's chunk length depends on its latency mode and its MTU split,
     * neither of which is negotiated, so assuming 10ms would insert audio
     * out of nothing on every lost packet.
     */
    @Test
    fun `conceals using the observed packet size, not the constructor guess`() {
        // Constructed expecting 10ms-ish chunks...
        val buffer = JitterBuffer(chunkSizeBytes = 64, targetDepthChunks = 2)
        // ...but the sender actually sends 8-byte chunks.
        buffer.push(0u, ByteArray(8) { 1 })
        buffer.push(1u, ByteArray(8) { 2 })
        buffer.pop()
        buffer.pop()
        // Sequence 2 is lost; the concealment must be 8 bytes, not 64.
        buffer.push(3u, ByteArray(8) { 3 })
        val concealed = buffer.pop()
        assertEquals(8, concealed!!.size)
    }

    // ---------------------------------------------------------------------
    // Depth expressed in milliseconds. Counting packets instead silently
    // gave ~18ms of buffer on a link that needs an order of magnitude more.
    // ---------------------------------------------------------------------

    /** 48kHz stereo 16-bit = 192 bytes per millisecond. */
    private val bytesPerMs = 192

    @Test
    fun `a millisecond depth buffers that much audio regardless of packet size`() {
        // Two senders, same requested depth, very different packet sizes.
        for (packetBytes in listOf(192, 1168)) {
            val buffer = JitterBuffer(
                chunkSizeBytes = packetBytes,
                bytesPerFrame = 4,
                targetDepthMs = 120,
                bytesPerMs = bytesPerMs,
            )
            var seq = 0u
            var popped: ByteArray? = null
            var buffered = 0
            while (popped == null && buffered < 500) {
                buffer.push(seq, ByteArray(packetBytes) { 1 })
                seq++
                buffered++
                popped = buffer.pop()
            }
            val bufferedMs = buffered * packetBytes / bytesPerMs
            assertEquals(
                "packet size $packetBytes should still buffer ~120ms, got ${bufferedMs}ms",
                true,
                bufferedMs in 110..135,
            )
        }
    }

    /**
     * The old fixed cap of 32 chunks could not physically hold a large
     * depth setting: 400ms of ~6ms packets is ~66 of them.
     */
    @Test
    fun `a deep buffer is not truncated by the eviction cap`() {
        val packetBytes = 1168 // ~6ms
        val buffer = JitterBuffer(
            chunkSizeBytes = packetBytes,
            bytesPerFrame = 4,
            targetDepthMs = 400,
            bytesPerMs = bytesPerMs,
        )
        var seq = 0u
        repeat(80) { buffer.push(seq++, ByteArray(packetBytes) { 1 }) }
        // All 80 must still be held; with a cap of 32 the earliest were evicted
        // and the buffer could never reach its own target.
        assertEquals(80, buffer.bufferedCount)
        // ...and it is now deep enough to start playing.
        assertEquals(true, buffer.pop() != null)
    }

    // ---------------------------------------------------------------------
    // Standing latency. Jitter is transient; backlog is forever unless
    // something sheds it, and the drift loop is far too slow to.
    // ---------------------------------------------------------------------

    /**
     * A transient stall — a descheduled receive loop, a Wi-Fi retransmit
     * burst — delivers a pile of packets at once. Without a fast trim that
     * backlog becomes permanent added delay: the drift correction moves one
     * ~21us frame per ten pops, so shedding 200ms would take ~10 minutes.
     */
    @Test
    fun `a burst backlog is shed instead of becoming permanent latency`() {
        val packetBytes = 1168 // ~6ms
        val buffer = JitterBuffer(
            chunkSizeBytes = packetBytes,
            bytesPerFrame = 4,
            targetDepthMs = 120,
            bytesPerMs = bytesPerMs,
        )
        var seq = 0u
        // A stall's worth of audio lands in one go — far past the target.
        repeat(120) { buffer.push(seq++, ByteArray(packetBytes) { 1 }) }
        buffer.pop()

        val target = 120 * bytesPerMs / packetBytes
        assertEquals(
            "buffer should have shed the backlog back toward its target",
            true,
            buffer.bufferedCount <= target + 2,
        )
        assertEquals(true, buffer.latencyTrimCount > 0)
    }

    /** Steady-state playback at the configured depth must never trim. */
    @Test
    fun `normal streaming at the target depth never trims`() {
        val packetBytes = 1168
        val buffer = JitterBuffer(
            chunkSizeBytes = packetBytes,
            bytesPerFrame = 4,
            targetDepthMs = 120,
            bytesPerMs = bytesPerMs,
        )
        var seq = 0u
        repeat(25) { buffer.push(seq++, ByteArray(packetBytes) { 1 }) }
        // One in, one out — the steady state, with a little ordinary jitter.
        repeat(400) {
            buffer.pop()
            buffer.push(seq++, ByteArray(packetBytes) { 1 })
        }
        assertEquals(0L, buffer.latencyTrimCount)
        assertEquals(0L, buffer.resyncCount)
    }

    /** After trimming, playback continues in order from where it resumed. */
    @Test
    fun `playback stays sequential after a trim`() {
        val buffer = JitterBuffer(
            chunkSizeBytes = 4,
            targetDepthChunks = 3,
            bytesPerFrame = 4,
        )
        var seq = 0u
        repeat(60) { buffer.push(seq++, chunk(1)) }
        buffer.pop()
        assertEquals(true, buffer.latencyTrimCount > 0)
        // Whatever it resumed from, the following pops must be contiguous
        // real audio, not concealment silence.
        repeat(2) { assertArrayEquals(chunk(1), buffer.pop()) }
    }

    /**
     * The chopping regression: the sender briefly emitted alternating
     * packet sizes, which swung the inferred target depth and tripped the
     * latency trim on perfectly good audio every few seconds. The sender
     * splits evenly now; the buffer must not be destabilised by it either
     * way.
     */
    @Test
    fun `alternating packet sizes do not provoke spurious trims`() {
        val buffer = JitterBuffer(
            chunkSizeBytes = 1168,
            bytesPerFrame = 4,
            targetDepthMs = 120,
            bytesPerMs = bytesPerMs,
        )
        var seq = 0u
        // Prime, alternating exactly as the uneven split did.
        repeat(40) { buffer.push(seq++, ByteArray(if (it % 2 == 0) 1168 else 752) { 1 }) }
        repeat(600) {
            buffer.pop()
            buffer.push(seq++, ByteArray(if (it % 2 == 0) 1168 else 752) { 1 })
        }
        assertEquals(
            "an alternating packet size must not look like standing backlog",
            0L,
            buffer.latencyTrimCount,
        )
    }

    // ---------------------------------------------------------------------
    // Adaptive depth. A fixed target only works if the network is known in
    // advance; measured on a real hotspot, a 120ms setting concealed 58% of
    // chunks while sitting *at* target, because the needed packet was still
    // in flight.
    // ---------------------------------------------------------------------

    /** Packets that consistently miss their deadline must buy more depth. */
    @Test
    fun `sustained lateness grows the buffer`() {
        val packetBytes = 960
        val buffer = JitterBuffer(
            chunkSizeBytes = packetBytes,
            bytesPerFrame = 4,
            targetDepthMs = 120,
            bytesPerMs = bytesPerMs,
        )
        var seq = 0u
        repeat(30) { buffer.push(seq++, ByteArray(packetBytes) { 1 }) }
        repeat(3000) {
            buffer.pop()
            // Every few packets arrives after we have already played past
            // it. The buffer holds ~30, so the play position sits about 30
            // behind `seq`; 50 back is genuinely late, and still well inside
            // the distance that would instead be treated as a desync.
            if (it % 20 == 0) buffer.push(seq - 50u, ByteArray(packetBytes) { 2 })
            buffer.push(seq++, ByteArray(packetBytes) { 1 })
        }
        assertEquals(
            "buffer should have taken on extra depth to cover the lateness",
            true,
            buffer.adaptiveExtraMs > 0,
        )
    }

    /**
     * Plain packet loss must NOT grow the buffer: a chunk that never arrives
     * is not recovered by waiting longer, so growing would spend latency for
     * nothing.
     */
    @Test
    fun `pure packet loss does not grow the buffer`() {
        val packetBytes = 960
        val buffer = JitterBuffer(
            chunkSizeBytes = packetBytes,
            bytesPerFrame = 4,
            targetDepthMs = 120,
            bytesPerMs = bytesPerMs,
        )
        var seq = 0u
        repeat(30) { buffer.push(seq++, ByteArray(packetBytes) { 1 }) }
        repeat(3000) {
            buffer.pop()
            // 5% of packets simply never show up — none of them arrive late.
            if (it % 20 == 0) seq++
            buffer.push(seq++, ByteArray(packetBytes) { 1 })
        }
        assertEquals(0, buffer.adaptiveExtraMs)
    }

    /** A clean link must not accumulate latency it does not need. */
    @Test
    fun `a clean stream never grows the buffer`() {
        val packetBytes = 960
        val buffer = JitterBuffer(
            chunkSizeBytes = packetBytes,
            bytesPerFrame = 4,
            targetDepthMs = 120,
            bytesPerMs = bytesPerMs,
        )
        var seq = 0u
        repeat(30) { buffer.push(seq++, ByteArray(packetBytes) { 1 }) }
        repeat(3000) {
            buffer.pop()
            buffer.push(seq++, ByteArray(packetBytes) { 1 })
        }
        assertEquals(0, buffer.adaptiveExtraMs)
    }
}
