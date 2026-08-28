package com.audiorelay.app.state

import android.content.Context

/**
 * User-configurable playback settings — the Android-side equivalent of
 * `desktop-app`'s capture-device selection and latency mode
 * (`desktop-app/src/state.rs`). Backed by `SharedPreferences`, same as
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
     * Jitter buffer depth in **milliseconds** — lower is less delay, higher
     * is more resistant to network jitter and loss. Clamped to a sane range
     * so a bad persisted value can't produce a silently-broken buffer.
     *
     * This was previously stored as a count of ~10ms "chunks", which was
     * wrong in a way that mattered: a packet's real duration depends on the
     * sender's latency mode and its MTU split, so the shipped default of 3
     * chunks actually meant ~18ms of buffer and the maximum reachable
     * setting was ~36ms. Wi-Fi jitter is routinely larger than that, and a
     * phone hotspot much larger, so the buffer underran more or less
     * continuously. The key is deliberately new (`..._ms`) rather than
     * reused, so an old persisted chunk count is never reinterpreted as
     * milliseconds.
     */
    var jitterTargetDepthMs: Int
        get() = prefs.getInt(KEY_JITTER_DEPTH_MS, DEFAULT_JITTER_DEPTH_MS).coerceIn(MIN_JITTER_DEPTH_MS, MAX_JITTER_DEPTH_MS)
        set(value) {
            prefs.edit().putInt(KEY_JITTER_DEPTH_MS, value.coerceIn(MIN_JITTER_DEPTH_MS, MAX_JITTER_DEPTH_MS)).apply()
        }

    /**
     * Light/dark preference. Stored by name rather than ordinal so that
     * reordering the enum can't silently repoint an existing user's setting
     * at a different value.
     */
    var themeMode: ThemeMode
        get() = ThemeMode.fromStoredName(prefs.getString(KEY_THEME_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        }

    /**
     * Whether to take colours from the wallpaper (Material You) on API 31+.
     * Defaults on: it is the platform-native behaviour, and the brand scheme
     * remains one toggle away for anyone who prefers a fixed palette.
     */
    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "audio_relay_settings"
        private const val KEY_PREFERRED_OUTPUT_DEVICE = "preferred_output_device"
        private const val KEY_JITTER_DEPTH_MS = "jitter_target_depth_ms"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"

        /**
         * Enough to ride out ordinary Wi-Fi jitter, including a phone
         * hotspot, without being so deep the delay becomes obvious. The
         * old effective default was ~18ms, which is well below what any
         * real wireless link delivers.
         */
        const val DEFAULT_JITTER_DEPTH_MS = 120

        /** For a quiet, wired-quality link where latency matters most. */
        const val MIN_JITTER_DEPTH_MS = 30

        /** For a congested or distant link, trading delay for continuity. */
        const val MAX_JITTER_DEPTH_MS = 400
    }
}

/** Mirrors `desktop-app`'s `ui::theme::Appearance`, so both apps offer the same choice. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        /** Tolerates a missing or unrecognised stored value rather than throwing. */
        fun fromStoredName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
