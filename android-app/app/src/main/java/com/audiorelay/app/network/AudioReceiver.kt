package com.audiorelay.app.network

import android.media.AudioDeviceInfo
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

    /**
     * Call once pairing + capability exchange has completed.
     *
     * @param jitterTargetDepthChunks user-configurable buffer depth (see
     *   `state/SettingsStore.kt`) — more chunks trades latency for
     *   glitch-resistance (docs/architecture.md §6).
     * @param preferredOutputDevice user-selected output route (see
     *   `audio/OutputDeviceRepository.kt`), or `null` for Android's normal
     *   automatic routing.
     */
    fun configureSession(
        sessionKey: ByteArray,
        sessionId: ByteArray,
        sampleRateHz: Int,
        channels: Int,
        jitterTargetDepthChunks: Int,
        preferredOutputDevice: AudioDeviceInfo?,
    ) {
        this.sessionKey = sessionKey
        this.sessionId = sessionId
        // ~10ms chunks, matching the sender's TARGET_CHUNK_MS (windows-app/src/capture/mod.rs).
        val bytesPerSample = 2 // 16-bit PCM
        val bytesPerFrame = channels * bytesPerSample
        val chunkSizeBytes = sampleRateHz / 100 * bytesPerFrame
        jitterBuffer = JitterBuffer(
            chunkSizeBytes,
            targetDepthChunks = jitterTargetDepthChunks,
            bytesPerFrame = bytesPerFrame,
        )
        playback = PlaybackTrack(sampleRateHz, channels, preferredOutputDevice).also { it.play() }
    }

    /** Applies a new output-device choice to the already-playing track, if a session is active. No-op otherwise. */
    fun updatePreferredOutputDevice(device: AudioDeviceInfo?) {
        playback?.setPreferredDevice(device)
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
                // The timestamp drives clock-drift correction (protocol-spec.md §3,
                // docs/roadmap.md Phase 5) — see JitterBuffer's class docs.
                jitterBuffer?.push(header.sequence, pcm, header.timestampMs)
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
