package com.audiorelay.app.state

import android.content.Context
import java.util.UUID

/**
 * Persisted app state: this phone's own device ID, and the set of
 * previously paired laptops (keyed by laptop `device_id`) so daily use is
 * "open both apps, they reconnect" per protocol-spec.md §5 — mirrors
 * `windows-app/src/config.rs` on the other side of the pairing.
 *
 * Backed by `SharedPreferences` rather than a database — this is a
 * handful of small values, not a dataset.
 */
class PairedDeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    data class SavedLaptop(val name: String, val sessionKeyHex: String)

    fun getSavedLaptop(laptopDeviceId: String): SavedLaptop? {
        val name = prefs.getString(nameKey(laptopDeviceId), null) ?: return null
        val key = prefs.getString(keyKey(laptopDeviceId), null) ?: return null
        return SavedLaptop(name, key)
    }

    fun saveLaptop(laptopDeviceId: String, name: String, sessionKeyHex: String) {
        prefs.edit()
            .putString(nameKey(laptopDeviceId), name)
            .putString(keyKey(laptopDeviceId), sessionKeyHex)
            .putString(KEY_LAST_LAPTOP_ID, laptopDeviceId)
            .apply()
    }

    /** The most recently paired laptop's device_id, for auto-reconnect on launch. */
    val lastLaptopDeviceId: String?
        get() = prefs.getString(KEY_LAST_LAPTOP_ID, null)

    fun forgetLaptop(laptopDeviceId: String) {
        prefs.edit()
            .remove(nameKey(laptopDeviceId))
            .remove(keyKey(laptopDeviceId))
            .apply()
    }

    private fun nameKey(laptopDeviceId: String) = "laptop_name_$laptopDeviceId"
    private fun keyKey(laptopDeviceId: String) = "laptop_key_$laptopDeviceId"

    companion object {
        private const val PREFS_NAME = "audio_relay_state"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_LAPTOP_ID = "last_laptop_id"
    }
}
