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
        val buffer = JitterBuffer(chunkSize, targetDepthChunks = 1, maxBufferedChunks = 4)
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
}
