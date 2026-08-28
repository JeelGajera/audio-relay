package com.audiorelay.app.network

import android.media.AudioDeviceInfo
import com.audiorelay.app.audio.AudioLevel
import com.audiorelay.app.audio.JitterBuffer
import com.audiorelay.app.audio.PlaybackTrack
import android.util.Log
import com.audiorelay.app.state.RelayState
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
    private val socket = DatagramSocket().apply {
        // The OS default receive buffer is small enough that a short burst
        // — a Wi-Fi retransmit flurry, or the scheduler holding this thread
        // off for a few milliseconds — silently overflows it, and audio
        // dropped in the kernel is invisible to the jitter buffer's loss
        // handling. Best-effort: the OS may clamp this, which is fine.
        runCatching { receiveBufferSize = RECEIVE_BUFFER_BYTES }
    }

    val localPort: Int get() = socket.localPort

    @Volatile private var sessionKey: ByteArray? = null

    @Volatile private var sessionId: ByteArray? = null
    private var jitterBuffer: JitterBuffer? = null
    private var playback: PlaybackTrack? = null

    /** Rate-limits the level visualiser — see [playbackLoop]. */
    private var lastLevelUpdateMs = 0L

    /** Set by [close]; both loops check it so teardown is unambiguous. */
    @Volatile private var closed = false

    /** Last time playback health was logged — see [playbackLoop]. */
    private var lastHealthLogMs = 0L

    /**
     * Call once pairing + capability exchange has completed.
     *
     * @param jitterTargetDepthMs user-configurable buffer depth in milliseconds (see
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
        jitterTargetDepthMs: Int,
        preferredOutputDevice: AudioDeviceInfo?,
    ) {
        this.sessionKey = sessionKey
        this.sessionId = sessionId
        val bytesPerSample = 2 // 16-bit PCM
        val bytesPerFrame = channels * bytesPerSample
        // Only a starting guess (~10ms). The real packet size depends on the
        // sender's latency mode *and* on its MTU split
        // (desktop-app/src/network/audio_sender.rs), neither of which is
        // negotiated, so JitterBuffer re-learns this from the first packet
        // that arrives — see its `chunkSizeBytes`.
        val chunkSizeBytes = sampleRateHz / 100 * bytesPerFrame
        jitterBuffer = JitterBuffer(
            chunkSizeBytes,
            bytesPerFrame = bytesPerFrame,
            // Depth in milliseconds, converted to packets internally against
            // whatever size the sender turns out to be using.
            targetDepthMs = jitterTargetDepthMs,
            bytesPerMs = sampleRateHz * bytesPerFrame / 1000,
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
        while (isActive && !closed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                if (closed || socket.isClosed) return@withContext
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
        while (isActive && !closed) {
            val chunk = jitterBuffer?.pop()
            if (chunk == null) {
                delay(PREBUFFER_POLL_INTERVAL_MS)
                continue
            }
            // Measured here rather than on receive, so the visualiser reflects
            // what actually reaches the speaker — including concealment
            // silence during packet loss, which is exactly when a user is
            // looking at it.
            //
            // Throttled, because this loop runs once per packet — ~165 times
            // a second at a ~6ms packet size. Computing RMS over every chunk
            // and pushing every result into a StateFlow drives that many
            // Compose recompositions per second on a path that must not miss
            // its deadline, and no display needs more than ~25fps anyway.
            val now = System.currentTimeMillis()
            // Periodic health line. Choppiness has several possible causes
            // that sound identical — real packet loss, a starved buffer, a
            // latency trim, a resync — and this is the only way to tell
            // which is actually happening on a given network:
            //   adb logcat -s AudioRelay
            if (now - lastHealthLogMs >= HEALTH_LOG_INTERVAL_MS) {
                lastHealthLogMs = now
                jitterBuffer?.let { Log.i(HEALTH_TAG, it.healthSummary()) }
            }
            if (now - lastLevelUpdateMs >= LEVEL_UPDATE_INTERVAL_MS) {
                lastLevelUpdateMs = now
                RelayState.setPlaybackLevel(AudioLevel.fromPcm16(chunk))
            }
            // A blocking write is what paces this loop to real time. When it
            // *fails* it returns immediately, so without backing off here the
            // loop would spin at CPU speed — and because every iteration also
            // advances the jitter buffer's expected sequence, playback would
            // race past the sender and never recover. See PlaybackTrack.write.
            if (playback?.write(chunk) == PlaybackTrack.WriteResult.FAILED) {
                if (closed) return@withContext
                delay(WRITE_FAILURE_BACKOFF_MS)
            }
        }
    }

    fun close() {
        // Flag first, then unblock. Both loops are normally parked inside a
        // blocking call — `socket.receive` and `AudioTrack.write` — which is
        // not a cancellable suspension point, so closing the socket and
        // releasing the track is what actually wakes them. Setting this
        // before that happens is what lets each one tell "we are shutting
        // down" from "something failed", instead of trying to recover from
        // its own teardown.
        closed = true
        runCatching { socket.close() }
        playback?.shutdown()
    }

    companion object {
        // Comfortably above any realistic UDP audio frame for this project
        // (protocol-spec.md's PCM chunks are on the order of a few KB).
        private const val MAX_PACKET_SIZE = 4096

        /**
         * Enough to absorb a burst without the kernel dropping packets,
         * deliberately not more: audio queued here is invisible to the
         * jitter buffer, so an oversized socket buffer just hoards latency
         * the receiver cannot see. ~340ms at this protocol's bitrate; the
         * jitter buffer trims anything that does pile up.
         */
        private const val RECEIVE_BUFFER_BYTES = 64 * 1024
        private const val PREBUFFER_POLL_INTERVAL_MS = 5L

        /**
         * Backoff after a failed `AudioTrack` write. Long enough that a
         * persistently broken track can't burn a core, short enough that
         * recovery is inaudible once the route settles.
         */
        private const val WRITE_FAILURE_BACKOFF_MS = 20L

        /** ~25 visualiser updates a second; the audio path runs ~165. */
        private const val LEVEL_UPDATE_INTERVAL_MS = 40L

        private const val HEALTH_TAG = "AudioRelay"
        private const val HEALTH_LOG_INTERVAL_MS = 10_000L
    }
}
