package com.audiorelay.app.network

/**
 * UDP audio packet decode. Mirrors `windows-app/src/protocol/packet.rs`
 * exactly — see `/protocol-spec.md` §3 for the authoritative field
 * definitions. This app is receive-only, so there's no `encode()` here.
 */
object AudioPacket {
    /** codec_id(1) + sequence(4) + timestamp_ms(4) + sample_rate_id(1) + channels(1) + reserved(2) */
    const val HEADER_LEN = 13

    const val CODEC_RAW_PCM: Int = 0x00

    data class Decoded(
        val codec: Int,
        val sequence: UInt,
        val timestampMs: UInt,
        val sampleRateHz: Int,
        val channels: Int,
        /** Offset into the original buffer where the payload starts; callers
         * slice `buffer[payloadStart until buffer.size]` rather than forcing
         * a copy here, since the payload is about to be decrypted anyway. */
        val payloadStart: Int,
    )

    private fun sampleRateHz(id: Int): Int? = when (id) {
        0 -> 44_100
        1 -> 48_000
        else -> null
    }

    /**
     * Parses the fixed header only (does not touch/copy the payload bytes).
     * Returns null for anything malformed or using a codec/sample-rate this
     * version doesn't understand — per protocol-spec.md §3, an unrecognized
     * `codec_id` must be dropped, not played.
     */
    fun decodeHeader(buffer: ByteArray, length: Int): Decoded? {
        if (length < HEADER_LEN) return null

        val codec = buffer[0].toInt() and 0xFF
        if (codec != CODEC_RAW_PCM) return null

        val sequence = readU32BE(buffer, 1)
        val timestampMs = readU32BE(buffer, 5)
        val sampleRateId = buffer[9].toInt() and 0xFF
        val sampleRateHz = sampleRateHz(sampleRateId) ?: return null
        val channels = buffer[10].toInt() and 0xFF
        // buffer[11..13) (reserved u16) intentionally ignored.

        return Decoded(
            codec = codec,
            sequence = sequence,
            timestampMs = timestampMs,
            sampleRateHz = sampleRateHz,
            channels = channels,
            payloadStart = HEADER_LEN,
        )
    }

    private fun readU32BE(buffer: ByteArray, offset: Int): UInt {
        return ((buffer[offset].toUInt() and 0xFFu) shl 24) or
            ((buffer[offset + 1].toUInt() and 0xFFu) shl 16) or
            ((buffer[offset + 2].toUInt() and 0xFFu) shl 8) or
            (buffer[offset + 3].toUInt() and 0xFFu)
    }
}
