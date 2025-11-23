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
        binding.etPressThreshold.setText(prefs.pressThreshold.toInt().toString())
        binding.etReleaseThreshold.setText(prefs.releaseThreshold.toInt().toString())
        binding.etPressDebounce.setText(prefs.pressDebounceMs.toString())
        binding.etReleaseDebounce.setText(prefs.releaseDebounceMs.toString())
        binding.etLongPressMs.setText(prefs.longPressDuration.toString())

        setupActionDropdown(binding.menuNShort, prefs.nShortAction)
        setupActionDropdown(binding.menuNLong, prefs.nLongAction)
        setupActionDropdown(binding.menuSShort, prefs.sShortAction)
        setupActionDropdown(binding.menuSLong, prefs.sLongAction)
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener { saveSettings() }
    }

    private fun saveSettings() {
        prefs.pressThreshold = binding.etPressThreshold.text.toString().toFloatOrNull() ?: 180f
        prefs.releaseThreshold = binding.etReleaseThreshold.text.toString().toFloatOrNull() ?: 90f
        prefs.pressDebounceMs = binding.etPressDebounce.text.toString().toLongOrNull() ?: 90L
        prefs.releaseDebounceMs = binding.etReleaseDebounce.text.toString().toLongOrNull() ?: 110L
        prefs.longPressDuration = binding.etLongPressMs.text.toString().toLongOrNull() ?: 1500L

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
