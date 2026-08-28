package com.audiorelay.app.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Wraps `AudioTrack` configured for low-latency media playback. See
 * `docs/architecture.md` §3.2: `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC` is what
 * lets Android's normal routing send this to whatever Bluetooth device is
 * already connected — deliberately *not*
 * `USAGE_VOICE_COMMUNICATION`/SCO, which routes mono/low-quality.
 *
 * [preferredDevice] overrides that automatic routing when the user has
 * explicitly picked an output device in Settings (see
 * `audio/OutputDeviceRepository.kt`) — `null` leaves Android's normal
 * routing in charge, which is the default and what most users want.
 *
 * The track is **recreatable**: an `AudioTrack` can die under the app
 * (`ERROR_DEAD_OBJECT`) when the audio route changes out from under it —
 * Bluetooth disconnecting or reconnecting mid-stream being the common case
 * — and the only fix is to build a new one. See [write].
 */
class PlaybackTrack(
    private val sampleRateHz: Int,
    private val channels: Int,
    private var preferredDevice: AudioDeviceInfo? = null,
) {
    /** What happened to one [write]; lets the playback loop tell progress from a stalled track. */
    enum class WriteResult {
        /** The audio was handed to the track. */
        OK,

        /** The track had died and was rebuilt; this chunk was dropped, the next should land. */
        RECOVERED,

        /** The write failed and retrying immediately would just spin. */
        FAILED,
    }

    private val channelMask =
        if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

    @Volatile
    private var track: AudioTrack = buildTrack()

    /**
     * Set by [shutdown] before the track is released, and never cleared.
     *
     * Teardown races the playback loop by construction: that loop is
     * normally *blocked inside* `track.write` when the session ends, and
     * releasing the track from another thread is what unblocks it. Without
     * this flag the resulting `IllegalStateException` was indistinguishable
     * from a track that had died mid-session, so [write] rebuilt it — and
     * teardown quietly resurrected the thing it was tearing down. The loop
     * then never exited: it kept running against a fresh track, so the
     * visualiser kept animating with no audio, the session could not be
     * stopped, and every reconnect leaked another `AudioTrack` and another
     * blocked thread. That is what made playback degrade the longer the app
     * ran.
     */
    @Volatile
    private var released = false

    /** Playback-head watchdog state — see [playbackHasStalled]. */
    private var lastHeadPosition = 0
    private var stalledWrites = 0

    /**
     * Whether the mixer has ever pulled audio from this track. Until it
     * has, a motionless playback head means "still filling", not "stalled".
     */
    private var hasEverAdvanced = false

    /**
     * Builds the track, preferring the low-latency path but never letting
     * that preference cost us a session.
     *
     * Two hard-won constraints, both from real-device logs:
     *
     * - **The buffer size must be `getMinBufferSize()` exactly.** Asking for
     *   a multiple of it disqualifies the fast mixer path — AudioFlinger
     *   logs `mismatch between requested flags (00000004) and output flags
     *   (00000008)` and quietly returns a deep-buffer output, which on this
     *   device meant `frameCount = 20328` (423ms) and `mLatency = 633`.
     *   That is latency the jitter buffer cannot see, and a deep buffer is
     *   also likelier to hit the underrun eviction [playbackHasStalled]
     *   watches for.
     * - **It must be set at all.** Omitting it does not mean "choose for
     *   me": `AudioTrack.Builder` defaults to a single frame, which is too
     *   small to initialise, and `build()` throws
     *   `UnsupportedOperationException: Cannot create AudioTrack`. That
     *   killed every session immediately after pairing and left the phone
     *   reconnecting forever — pairing had actually worked fine.
     */
    private fun buildTrack(): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRateHz,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(1)

        fun build(performanceMode: Int) = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(minBufferSize)
            .setPerformanceMode(performanceMode)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { setPreferredDevice(preferredDevice) }

        // Low latency is a preference, not a requirement. If a device
        // refuses to build a track with it, fall back rather than fail the
        // session — playing with more latency beats not playing at all.
        return runCatching { build(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
            .onFailure { Log.w(TAG, "low-latency AudioTrack unavailable; using the default mode", it) }
            .getOrElse { build(AudioTrack.PERFORMANCE_MODE_NONE) }
    }

    fun play() {
        runCatching { track.play() }
            .onFailure { Log.w(TAG, "could not start playback", it) }
    }

    /**
     * Switches the output route on an already-playing track — unlike the
     * capture-device equivalent on the desktop side, this does *not* need a
     * restart, so Settings changes can apply instantly while streaming.
     * `null` reverts to Android's normal automatic routing.
     */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredDevice = device // remembered, so a rebuilt track keeps the choice
        // Best-effort: returns false if the device isn't a valid output
        // route for this track; not worth surfacing as an error, Android
        // just falls back to normal routing in that case.
        runCatching { track.setPreferredDevice(device) }
    }

    /**
     * Writes one chunk, blocking until the track accepts it — that blocking
     * is what paces the whole playback loop to real time.
     *
     * Which is exactly why the return value matters. A failed write returns
     * *immediately*, so a caller that ignores the result spins as fast as
     * the CPU allows, and since every iteration also advances the jitter
     * buffer's expected sequence number, playback races past the sender in
     * seconds and never recovers. That was the "audio stops after ~30s
     * while the desktop keeps capturing" bug.
     */
    fun write(pcm: ByteArray): WriteResult {
        if (released) return WriteResult.FAILED
        val written = try {
            track.write(pcm, 0, pcm.size)
        } catch (e: IllegalStateException) {
            // Almost certainly our own teardown releasing the track out from
            // under this call. Only treat it as a dead track if it isn't.
            if (released) return WriteResult.FAILED
            Log.w(TAG, "AudioTrack.write threw; treating as a dead track", e)
            AudioTrack.ERROR_DEAD_OBJECT
        }

        if (written > 0) {
            return if (playbackHasStalled()) WriteResult.RECOVERED else WriteResult.OK
        }

        // Zero is *not* success. A blocking write returns 0 only when it
        // accepted nothing — a track that is not playing, typically — and
        // returns it immediately. Counting that as progress turned this
        // into a busy loop that pegged a core and, because every iteration
        // also advances the jitter buffer's expected sequence, raced
        // playback past the sender until it desynced into silence.
        if (written == 0) {
            Log.w(TAG, "AudioTrack accepted no data; is the track playing?")
            return WriteResult.FAILED
        }

        return when (written) {
            AudioTrack.ERROR_DEAD_OBJECT -> {
                // Genuinely gone (the audio route changed under us). A new
                // one is the only way back.
                Log.w(TAG, "AudioTrack died; rebuilding")
                if (rebuild()) WriteResult.RECOVERED else WriteResult.FAILED
            }
            else -> {
                Log.w(TAG, "AudioTrack.write failed with $written")
                WriteResult.FAILED
            }
        }
    }

    /**
     * Detects a track that is accepting audio but no longer producing any.
     *
     * On a sustained underrun AudioFlinger drops the track from its active
     * mix list — `prepareTracks_l BUFFER TIMEOUT: remove track(...) due to
     * underrun` — and from the app side nothing looks wrong at all: writes
     * keep succeeding, so the playback loop keeps running and the level
     * visualiser keeps animating while the user hears absolute silence.
     * Nothing recovers from it on its own.
     *
     * The one observable difference is that the playback head stops
     * advancing even though writes are being accepted, which is what this
     * watches for. Re-`play()`ing puts the track back on the active list.
     */
    private fun playbackHasStalled(): Boolean {
        val head = runCatching { track.playbackHeadPosition }.getOrNull() ?: return false
        if (head != lastHeadPosition) {
            lastHeadPosition = head
            hasEverAdvanced = true
            stalledWrites = 0
            return false
        }

        // A head that has never advanced is not a stall — it is the track
        // filling for the first time, before the mixer has pulled anything.
        // Counting that was a self-inflicted wound: the watchdog fired during
        // normal startup, and because its recovery flushed the track it threw
        // away the very audio that would have started playback, so it fired
        // again immediately, over and over.
        if (!hasEverAdvanced) return false
        if (++stalledWrites < STALLED_WRITES_BEFORE_RESTART) return false

        stalledWrites = 0
        Log.w(TAG, "playback head frozen while writes succeed; re-activating the track")
        // Deliberately non-destructive: `play()` is enough to put an evicted
        // track back on AudioFlinger's active list, and unlike pause/flush it
        // cannot discard audio if the diagnosis is wrong.
        runCatching { track.play() }
            .onFailure { Log.w(TAG, "could not re-activate the track", it) }
        return true
    }

    private fun rebuild(): Boolean {
        if (released) return false // never resurrect a track we are tearing down
        return runCatching {
            runCatching { track.release() }
            track = buildTrack()
            lastHeadPosition = 0
            stalledWrites = 0
            hasEverAdvanced = false
            track.play()
        }.isSuccess
    }

    /**
     * Permanently stops and releases the track. Call this exactly once, at
     * session teardown; [write] becomes a no-op afterwards so a playback
     * loop still unwinding cannot rebuild what was just released.
     */
    fun shutdown() {
        released = true
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private companion object {
        const val TAG = "PlaybackTrack"

        /**
         * Consecutive writes with a frozen playback head before the track is
         * treated as evicted. At roughly one write per packet this is about
         * a second of genuinely silent output — deliberately far longer than
         * any scheduling hiccup, because the recovery is worth doing only
         * when playback has really stopped.
         */
        const val STALLED_WRITES_BEFORE_RESTART = 200
    }
}
