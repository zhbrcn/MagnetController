package com.example.magnetcontroller

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("MagnetSettings", Context.MODE_PRIVATE)
    private val allowedActions = setOf(
        "play_pause",
        "voice",
        "previous",
        "next",
        "volume_down",
        "volume_up"
    )

    var pressThreshold: Float
        get() = prefs.getFloat("press_threshold", prefs.getFloat("threshold_trigger", 180f))
        set(value) = prefs.edit().putFloat("press_threshold", value).apply()

    var releaseThreshold: Float
        get() = prefs.getFloat("release_threshold", prefs.getFloat("threshold_reset", 90f))
        set(value) = prefs.edit().putFloat("release_threshold", value).apply()

    var pressDebounceMs: Long
        get() = prefs.getLong("press_debounce_ms", 90L)
        set(value) = prefs.edit().putLong("press_debounce_ms", value).apply()

    var releaseDebounceMs: Long
        get() = prefs.getLong("release_debounce_ms", prefs.getLong("threshold_reset_debounce_ms", 110L))
        set(value) = prefs.edit().putLong("release_debounce_ms", value).apply()

    var longPressDuration: Long
        get() = prefs.getLong("long_press_duration", 1500L)
        set(value) = prefs.edit().putLong("long_press_duration", value).apply()

    var nShortAction: String
        get() = getAction("n_short_action", "play_pause")
        set(value) = setAction("n_short_action", value)

    var nLongAction: String
        get() = getAction("n_long_action", "next")
        set(value) = setAction("n_long_action", value)

    var sShortAction: String
        get() = getAction("s_short_action", "previous")
        set(value) = setAction("s_short_action", value)

    var sLongAction: String
        get() = getAction("s_long_action", "volume_down")
        set(value) = setAction("s_long_action", value)

    private fun getAction(key: String, default: String): String {
        val raw = prefs.getString(key, default) ?: default
        val normalized = when (raw) {
            "media" -> "play_pause" // backward compatibility
            else -> raw
        }
        return if (allowedActions.contains(normalized)) normalized else default
    }

    private fun setAction(key: String, value: String) {
        val sanitized = if (allowedActions.contains(value)) value else "play_pause"
        prefs.edit().putString(key, sanitized).apply()
    }
}
