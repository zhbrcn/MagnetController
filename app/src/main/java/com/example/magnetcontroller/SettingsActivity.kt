package com.example.magnetcontroller

import android.content.Intent
import android.os.Bundle
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import com.example.magnetcontroller.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        binding.etThresholdTrigger.setText(prefs.thresholdTrigger.toInt().toString())
        binding.etThresholdReset.setText(prefs.thresholdReset.toInt().toString())
        binding.etResetDebounce.setText(prefs.thresholdResetDebounceMs.toString())
        binding.etLongPressMs.setText(prefs.longPressDuration.toString())
        binding.etPolarityMin.setText(prefs.polarityMin.toInt().toString())
        binding.etPolarityMax.setText(prefs.polarityMax.toInt().toString())
        binding.etPolarityDebounce.setText(prefs.polarityDebounceMs.toString())
        binding.etEnergyThreshold.setText(prefs.energySaveThreshold.toInt().toString())
        binding.etEnergyHoldMs.setText(prefs.energySaveHoldMs.toString())
        binding.etSamplingHighHz.setText(prefs.samplingHighRateHz.toString())
        binding.etSamplingLowHz.setText(prefs.samplingLowRateHz.toString())

        when (prefs.poleMode) {
            "different" -> binding.rbDifferent.isChecked = true
            else -> binding.rbBothPoles.isChecked = true
        }

        selectActionRadio(prefs.nShortAction, actionMapForNShort())
        selectActionRadio(prefs.nLongAction, actionMapForNLong())
        selectActionRadio(prefs.sShortAction, actionMapForSShort())
        selectActionRadio(prefs.sLongAction, actionMapForSLong())
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener { saveSettings() }
    }

    private fun saveSettings() {
        prefs.thresholdTrigger = binding.etThresholdTrigger.text.toString().toFloatOrNull() ?: 500f
        prefs.thresholdReset = binding.etThresholdReset.text.toString().toFloatOrNull() ?: 300f
        prefs.thresholdResetDebounceMs = binding.etResetDebounce.text.toString().toLongOrNull() ?: 80L
        prefs.longPressDuration = binding.etLongPressMs.text.toString().toLongOrNull() ?: 1500L
        prefs.polarityMin = binding.etPolarityMin.text.toString().toFloatOrNull() ?: 50f
        prefs.polarityMax = binding.etPolarityMax.text.toString().toFloatOrNull() ?: 2000f
        prefs.polarityDebounceMs = binding.etPolarityDebounce.text.toString().toLongOrNull() ?: 50L
        prefs.energySaveThreshold = binding.etEnergyThreshold.text.toString().toFloatOrNull() ?: 100f
        prefs.energySaveHoldMs = binding.etEnergyHoldMs.text.toString().toLongOrNull() ?: 2000L
        prefs.samplingHighRateHz = binding.etSamplingHighHz.text.toString().toFloatOrNull() ?: 50f
        prefs.samplingLowRateHz = binding.etSamplingLowHz.text.toString().toFloatOrNull() ?: 15f

        prefs.poleMode = if (binding.rbDifferent.isChecked) "different" else "both"

        prefs.nShortAction = readSelectedAction(actionMapForNShort())
        prefs.nLongAction = readSelectedAction(actionMapForNLong())
        prefs.sShortAction = readSelectedAction(actionMapForSShort())
        prefs.sLongAction = readSelectedAction(actionMapForSLong())

        val intent = Intent("com.example.magnetcontroller.RELOAD_SETTINGS").apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)

        finish()
    }

    private fun selectActionRadio(action: String, mapping: Map<String, RadioButton>) {
        val normalized = if (action == "media") "play_pause" else action
        val targetKey = if (mapping.containsKey(normalized)) normalized else "play_pause"
        mapping[targetKey]?.isChecked = true
    }

    private fun readSelectedAction(mapping: Map<String, RadioButton>): String {
        mapping.forEach { (key, button) ->
            if (button.isChecked) return key
        }
        return "play_pause"
    }

    private fun actionMapForNShort() = mapOf(
        "play_pause" to binding.rbNShortMedia,
        "voice" to binding.rbNShortVoice,
        "previous" to binding.rbNShortPrev,
        "next" to binding.rbNShortNext,
        "volume_down" to binding.rbNShortVolDown,
        "volume_up" to binding.rbNShortVolUp,
    )

    private fun actionMapForNLong() = mapOf(
        "play_pause" to binding.rbNLongMedia,
        "voice" to binding.rbNLongVoice,
        "previous" to binding.rbNLongPrev,
        "next" to binding.rbNLongNext,
        "volume_down" to binding.rbNLongVolDown,
        "volume_up" to binding.rbNLongVolUp,
    )

    private fun actionMapForSShort() = mapOf(
        "play_pause" to binding.rbSShortMedia,
        "voice" to binding.rbSShortVoice,
        "previous" to binding.rbSShortPrev,
        "next" to binding.rbSShortNext,
        "volume_down" to binding.rbSShortVolDown,
        "volume_up" to binding.rbSShortVolUp,
    )

    private fun actionMapForSLong() = mapOf(
        "play_pause" to binding.rbSLongMedia,
        "voice" to binding.rbSLongVoice,
        "previous" to binding.rbSLongPrev,
        "next" to binding.rbSLongNext,
        "volume_down" to binding.rbSLongVolDown,
        "volume_up" to binding.rbSLongVolUp,
    )
}
