package com.example.magnetcontroller

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.example.magnetcontroller.databinding.ActivitySettingsBinding
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences
    private val actionOptions = listOf(
        "play_pause" to "播放 / 暂停",
        "next" to "下一曲",
        "previous" to "上一曲",
        "voice" to "语音助手",
        "volume_up" to "音量 +",
        "volume_down" to "音量 -"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        setupActionSpinners()
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
        binding.etEnergyThreshold.setText(prefs.energySaveThreshold.toInt().toString())
        binding.etEnergyHoldMs.setText(prefs.energySaveHoldMs.toString())
        binding.etSamplingHighHz.setText(prefs.samplingHighRateHz.toString())
        binding.etSamplingLowHz.setText(prefs.samplingLowRateHz.toString())
        binding.etAutoZeroThreshold.setText(prefs.autoZeroThreshold.toString())
        binding.etAutoZeroSeconds.setText(String.format(Locale.US, "%.1f", prefs.autoZeroDurationMs / 1000f))

        when (prefs.poleMode) {
            "different" -> binding.rbDifferent.isChecked = true
            else -> binding.rbBothPoles.isChecked = true
        }

        setSpinnerSelection(binding.spNShort, prefs.nShortAction)
        setSpinnerSelection(binding.spNLong, prefs.nLongAction)
        setSpinnerSelection(binding.spSShort, prefs.sShortAction)
        setSpinnerSelection(binding.spSLong, prefs.sLongAction)
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
        prefs.energySaveThreshold = binding.etEnergyThreshold.text.toString().toFloatOrNull() ?: 100f
        prefs.energySaveHoldMs = binding.etEnergyHoldMs.text.toString().toLongOrNull() ?: 2000L
        prefs.samplingHighRateHz = binding.etSamplingHighHz.text.toString().toFloatOrNull() ?: 50f
        prefs.samplingLowRateHz = binding.etSamplingLowHz.text.toString().toFloatOrNull() ?: 15f
        prefs.autoZeroThreshold = binding.etAutoZeroThreshold.text.toString().toFloatOrNull() ?: 80f
        prefs.autoZeroDurationMs = ((binding.etAutoZeroSeconds.text.toString().toFloatOrNull() ?: 4f) * 1000).toLong()

        prefs.poleMode = if (binding.rbDifferent.isChecked) "different" else "both"

        prefs.nShortAction = readActionFromSpinner(binding.spNShort)
        prefs.nLongAction = readActionFromSpinner(binding.spNLong)
        prefs.sShortAction = readActionFromSpinner(binding.spSShort)
        prefs.sLongAction = readActionFromSpinner(binding.spSLong)

        val intent = Intent("com.example.magnetcontroller.RELOAD_SETTINGS").apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)

        finish()
    }

    private fun setupActionSpinners() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            actionOptions.map { it.second }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        listOf(binding.spNShort, binding.spNLong, binding.spSShort, binding.spSLong).forEach {
            it.adapter = adapter
        }
    }

    private fun setSpinnerSelection(spinner: android.widget.Spinner, action: String) {
        val normalized = if (action == "media") "play_pause" else action
        val index = actionOptions.indexOfFirst { it.first == normalized }.takeIf { it >= 0 } ?: 0
        spinner.setSelection(index)
    }

    private fun readActionFromSpinner(spinner: android.widget.Spinner): String {
        val position = spinner.selectedItemPosition
        return actionOptions.getOrNull(position)?.first ?: "play_pause"
    }
}
