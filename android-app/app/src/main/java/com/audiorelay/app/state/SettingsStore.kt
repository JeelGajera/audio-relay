package com.audiorelay.app.state

import android.content.Context

/**
 * User-configurable playback settings — the Android-side equivalent of
 * `windows-app`'s capture-device selection and latency mode
 * (`windows-app/src/state.rs`). Backed by `SharedPreferences`, same as
 * [PairedDeviceStore].
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * A stable `"type:address"` key identifying the user's preferred output
     * device (see `discovery`... actually `audio/OutputDeviceRepository.kt`'s
     * `stableKey`), or `null` for "let Android choose automatically" — the
     * default, and what most users want most of the time.
     */
    var preferredOutputDeviceKey: String?
        get() = prefs.getString(KEY_PREFERRED_OUTPUT_DEVICE, null)
        set(value) {
            prefs.edit().putString(KEY_PREFERRED_OUTPUT_DEVICE, value).apply()
        }

    /**
     * Jitter buffer depth in ~10ms chunks (docs/architecture.md §6: 20-40ms
     * is the documented sweet spot, i.e. 2-4 chunks) — lower is less delay,
     * higher is more resistant to network jitter/loss. Clamped to a sane
     * range so a bad persisted value can't produce a silently-broken buffer.
     */
    var jitterTargetDepthChunks: Int
        get() = prefs.getInt(KEY_JITTER_DEPTH, DEFAULT_JITTER_DEPTH_CHUNKS).coerceIn(MIN_JITTER_DEPTH_CHUNKS, MAX_JITTER_DEPTH_CHUNKS)
        set(value) {
            prefs.edit().putInt(KEY_JITTER_DEPTH, value.coerceIn(MIN_JITTER_DEPTH_CHUNKS, MAX_JITTER_DEPTH_CHUNKS)).apply()
        }

    companion object {
        private const val PREFS_NAME = "audio_relay_settings"
        private const val KEY_PREFERRED_OUTPUT_DEVICE = "preferred_output_device"
        private const val KEY_JITTER_DEPTH = "jitter_target_depth_chunks"

        const val DEFAULT_JITTER_DEPTH_CHUNKS = 3
        const val MIN_JITTER_DEPTH_CHUNKS = 2
        const val MAX_JITTER_DEPTH_CHUNKS = 6
    }
}
