package com.example.magnetcontroller

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("MagnetSettings", Context.MODE_PRIVATE)
    private val allowedActions = setOf("play_pause", "voice", "previous", "next", "volume_down", "volume_up")

    var thresholdTrigger: Float
        get() = prefs.getFloat("threshold_trigger", 500f)
        set(value) = prefs.edit().putFloat("threshold_trigger", value).apply()

    var longPressDuration: Long
        get() = prefs.getLong("long_press_duration", 1500L)
        set(value) = prefs.edit().putLong("long_press_duration", value).apply()

    var poleMode: String
        get() = prefs.getString("pole_mode", "both") ?: "both"
        set(value) = prefs.edit().putString("pole_mode", value).apply()

    var polarityMin: Float
        get() = prefs.getFloat("polarity_min", 50f)
        set(value) = prefs.edit().putFloat("polarity_min", value).apply()

    var polarityMax: Float
        get() = prefs.getFloat("polarity_max", 2000f)
        set(value) = prefs.edit().putFloat("polarity_max", value).apply()

    var thresholdReset: Float
        get() = prefs.getFloat("threshold_reset", 300f)
        set(value) = prefs.edit().putFloat("threshold_reset", value).apply()

    var energySaveThreshold: Float
        get() = prefs.getFloat("energy_save_threshold", 100f)
        set(value) = prefs.edit().putFloat("energy_save_threshold", value).apply()

    var energySaveHoldMs: Long
        get() = prefs.getLong("energy_save_hold_ms", 2000L)
        set(value) = prefs.edit().putLong("energy_save_hold_ms", value).apply()

    var samplingHighRateHz: Float
        get() = prefs.getFloat("sampling_high_rate_hz", 50f)
        set(value) = prefs.edit().putFloat("sampling_high_rate_hz", value).apply()

    var samplingLowRateHz: Float
        get() = prefs.getFloat("sampling_low_rate_hz", 15f)
        set(value) = prefs.edit().putFloat("sampling_low_rate_hz", value).apply()

    var thresholdResetDebounceMs: Long
        get() = prefs.getLong("threshold_reset_debounce_ms", 80L)
        set(value) = prefs.edit().putLong("threshold_reset_debounce_ms", value).apply()

    var autoZeroThreshold: Float
        get() = prefs.getFloat("auto_zero_threshold", 80f)
        set(value) = prefs.edit().putFloat("auto_zero_threshold", value).apply()

    var autoZeroDurationMs: Long
        get() = prefs.getLong("auto_zero_duration_ms", 4000L)
        set(value) = prefs.edit().putLong("auto_zero_duration_ms", value).apply()

    var autoZeroStabilityBand: Float
        get() = prefs.getFloat("auto_zero_stability_band", 20f)
        set(value) = prefs.edit().putFloat("auto_zero_stability_band", value).apply()

    var autoZeroStabilityDurationMs: Long
        get() = prefs.getLong("auto_zero_stability_duration_ms", 4000L)
        set(value) = prefs.edit().putLong("auto_zero_stability_duration_ms", value).apply()

    var strongSuppressionThreshold: Float
        get() = prefs.getFloat("strong_suppression_threshold", 1800f)
        set(value) = prefs.edit().putFloat("strong_suppression_threshold", value).apply()

    var strongSuppressionDurationMs: Long
        get() = prefs.getLong("strong_suppression_duration_ms", 400L)
        set(value) = prefs.edit().putLong("strong_suppression_duration_ms", value).apply()

    var strongSuppressionJitter: Float
        get() = prefs.getFloat("strong_suppression_jitter", 40f)
        set(value) = prefs.edit().putFloat("strong_suppression_jitter", value).apply()

    var allShortAction: String
        get() = getAction("all_short_action", "play_pause")
        set(value) = setAction("all_short_action", value)

    var allLongAction: String
        get() = getAction("all_long_action", "voice")
        set(value) = setAction("all_long_action", value)

    var nShortAction: String
        get() = getAction("n_short_action", "play_pause")
        set(value) = setAction("n_short_action", value)

    var nLongAction: String
        get() = getAction("n_long_action", "voice")
        set(value) = setAction("n_long_action", value)

    var sShortAction: String
        get() = getAction("s_short_action", "play_pause")
        set(value) = setAction("s_short_action", value)

    var sLongAction: String
        get() = getAction("s_long_action", "voice")
        set(value) = setAction("s_long_action", value)

    var allowedBtDevices: Set<String>
        get() = prefs.getStringSet("allowed_bt_devices", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("allowed_bt_devices", value.toSet()).apply()

    var recentLogs: List<String>
        get() = prefs.getStringSet("recent_logs", emptySet())?.sortedBy { it.take(14) } ?: emptyList()
        set(value) = prefs.edit().putStringSet("recent_logs", value.toSet()).apply()

    var usePolarity: Boolean
        get() = prefs.getBoolean("use_polarity", false)
        set(value) = prefs.edit().putBoolean("use_polarity", value).apply()

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
