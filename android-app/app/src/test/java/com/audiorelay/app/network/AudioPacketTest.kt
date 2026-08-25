package com.audiorelay.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPacketTest {

    /** Builds a raw packet buffer the same way desktop-app's `AudioPacket::encode` does. */
    private fun buildPacket(
        codec: Int = 0x00,
        sequence: Long = 42L,
        timestampMs: Long = 123_456L,
        sampleRateId: Int = 1, // 48000 Hz
        channels: Int = 2,
        payload: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
    ): ByteArray {
        val buf = ByteArray(AudioPacket.HEADER_LEN + payload.size)
        buf[0] = codec.toByte()
        buf[1] = (sequence ushr 24).toByte()
        buf[2] = (sequence ushr 16).toByte()
        buf[3] = (sequence ushr 8).toByte()
        buf[4] = sequence.toByte()
        buf[5] = (timestampMs ushr 24).toByte()
        buf[6] = (timestampMs ushr 16).toByte()
        buf[7] = (timestampMs ushr 8).toByte()
        buf[8] = timestampMs.toByte()
        buf[9] = sampleRateId.toByte()
        buf[10] = channels.toByte()
        buf[11] = 0
        buf[12] = 0
        payload.copyInto(buf, AudioPacket.HEADER_LEN)
        return buf
    }

    @Test
    fun `decodes a well-formed header`() {
        val buf = buildPacket()
        val decoded = AudioPacket.decodeHeader(buf, buf.size)!!
        assertEquals(AudioPacket.CODEC_RAW_PCM, decoded.codec)
        assertEquals(42u, decoded.sequence)
        assertEquals(123_456u, decoded.timestampMs)
        assertEquals(48_000, decoded.sampleRateHz)
        assertEquals(2, decoded.channels)
        assertEquals(AudioPacket.HEADER_LEN, decoded.payloadStart)
    }

    @Test
    fun `rejects buffers shorter than the header`() {
        val buf = ByteArray(4)
        assertNull(AudioPacket.decodeHeader(buf, buf.size))
    }

    @Test
    fun `rejects an unrecognized codec`() {
        val buf = buildPacket(codec = 0x7F)
        assertNull(AudioPacket.decodeHeader(buf, buf.size))
    }

    @Test
    fun `rejects an unrecognized sample rate`() {
        val buf = buildPacket(sampleRateId = 200)
        assertNull(AudioPacket.decodeHeader(buf, buf.size))
    }

    @Test
    fun `handles a sequence number near u32 max`() {
        val buf = buildPacket(sequence = 0xFFFFFFFEL)
        val decoded = AudioPacket.decodeHeader(buf, buf.size)!!
        assertEquals(0xFFFFFFFEu, decoded.sequence)
    }

    @Test
    fun `treats a mono packet correctly`() {
        val buf = buildPacket(channels = 1)
        val decoded = AudioPacket.decodeHeader(buf, buf.size)!!
        assertEquals(1, decoded.channels)
    }

    @Test
    fun `44100 Hz sample rate id decodes correctly`() {
        val buf = buildPacket(sampleRateId = 0)
        val decoded = AudioPacket.decodeHeader(buf, buf.size)!!
        assertEquals(44_100, decoded.sampleRateHz)
    }
}
