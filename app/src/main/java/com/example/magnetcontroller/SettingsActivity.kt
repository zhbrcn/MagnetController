package com.example.magnetcontroller

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.example.magnetcontroller.databinding.ActivitySettingsBinding
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class SettingsActivity : AppCompatActivity() {

    private data class ActionOption(val key: String, val label: String)

    private val actionOptions = listOf(
        ActionOption("play_pause", "播放/暂停"),
        ActionOption("voice", "语音助手"),
        ActionOption("previous", "上一曲"),
        ActionOption("next", "下一曲"),
        ActionOption("volume_down", "音量 -"),
        ActionOption("volume_up", "音量 +"),
    )

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

        setupActionDropdown(binding.menuNShort, prefs.nShortAction)
        setupActionDropdown(binding.menuNLong, prefs.nLongAction)
        setupActionDropdown(binding.menuSShort, prefs.sShortAction)
        setupActionDropdown(binding.menuSLong, prefs.sLongAction)
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

        prefs.nShortAction = readSelectedAction(binding.menuNShort)
        prefs.nLongAction = readSelectedAction(binding.menuNLong)
        prefs.sShortAction = readSelectedAction(binding.menuSShort)
        prefs.sLongAction = readSelectedAction(binding.menuSLong)

        val intent = Intent("com.example.magnetcontroller.RELOAD_SETTINGS").apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)

        finish()
    }

    private fun setupActionDropdown(view: MaterialAutoCompleteTextView, actionKey: String) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, actionOptions.map { it.label })
        view.setAdapter(adapter)

        val normalized = normalizeActionKey(actionKey)
        val selected = actionOptions.find { it.key == normalized } ?: actionOptions.first()
        view.setText(selected.label, false)
    }

    private fun readSelectedAction(view: MaterialAutoCompleteTextView): String {
        val label = view.text?.toString()?.trim() ?: ""
        val selected = actionOptions.find { it.label == label } ?: actionOptions.first()
        return selected.key
    }

    private fun normalizeActionKey(action: String) = if (action == "media") "play_pause" else action
}
