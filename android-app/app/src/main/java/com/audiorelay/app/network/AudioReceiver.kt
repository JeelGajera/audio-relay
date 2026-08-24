package com.audiorelay.app.network

import com.audiorelay.app.audio.JitterBuffer
import com.audiorelay.app.audio.PlaybackTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * UDP audio receiver: binds a socket (so its port can be sent in `HELLO`
 * *before* the laptop needs it — see protocol-spec.md §4.1), decrypts each
 * packet's payload, and feeds a [JitterBuffer] that a separate playback
 * loop drains into a [PlaybackTrack].
 *
 * **Unverified end-to-end against real hardware** — see
 * `docs/roadmap.md` Phase 0.
 */
class AudioReceiver {
    private val socket = DatagramSocket() // binds an ephemeral local port immediately

    val localPort: Int get() = socket.localPort

    @Volatile private var sessionKey: ByteArray? = null

    @Volatile private var sessionId: ByteArray? = null
    private var jitterBuffer: JitterBuffer? = null
    private var playback: PlaybackTrack? = null

    /** Call once pairing + capability exchange has completed. */
    fun configureSession(sessionKey: ByteArray, sessionId: ByteArray, sampleRateHz: Int, channels: Int) {
        this.sessionKey = sessionKey
        this.sessionId = sessionId
        // ~10ms chunks, matching the sender's TARGET_CHUNK_MS (windows-app/src/capture/mod.rs).
        val bytesPerSample = 2 // 16-bit PCM
        val chunkSizeBytes = sampleRateHz / 100 * channels * bytesPerSample
        jitterBuffer = JitterBuffer(chunkSizeBytes)
        playback = PlaybackTrack(sampleRateHz, channels).also { it.play() }
    }

    /** Runs until [close] is called. Launch in its own coroutine. */
    suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        val buf = ByteArray(MAX_PACKET_SIZE)
        while (isActive) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                if (socket.isClosed) return@withContext
                continue
            }

            val header = AudioPacket.decodeHeader(buf, packet.length) ?: continue
            val key = sessionKey ?: continue // not paired yet — drop
            val id = sessionId ?: continue

            try {
                val headerBytes = buf.copyOf(AudioPacket.HEADER_LEN)
                val pcm = Crypto.decryptPayload(
                    key = key,
                    sessionId = id,
                    sequence = header.sequence,
                    headerAad = headerBytes,
                    ciphertext = buf,
                    ciphertextOffset = header.payloadStart,
                    ciphertextLength = packet.length - header.payloadStart,
                )
                jitterBuffer?.push(header.sequence, pcm)
            } catch (e: Crypto.AeadException) {
                continue // authentication failure — treat exactly like a lost packet
            }
        }
    }

    /** Runs until [close] is called. Launch in its own coroutine, alongside [receiveLoop]. */
    suspend fun playbackLoop() = withContext(Dispatchers.IO) {
        while (isActive) {
            val chunk = jitterBuffer?.pop()
            if (chunk == null) {
                delay(PREBUFFER_POLL_INTERVAL_MS)
                continue
            }
            playback?.write(chunk)
        }
    }

    fun close() {
        socket.close()
        playback?.stop()
        playback?.release()
    }

    companion object {
        // Comfortably above any realistic UDP audio frame for this project
        // (protocol-spec.md's PCM chunks are on the order of a few KB).
        private const val MAX_PACKET_SIZE = 4096
        private const val PREBUFFER_POLL_INTERVAL_MS = 5L
    }
}
