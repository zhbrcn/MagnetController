package com.example.magnetcontroller

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.magnetcontroller.databinding.ActivitySettingsBinding
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences
    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val btPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            showBluetoothPicker()
        } else {
            Toast.makeText(this, getString(R.string.bt_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }
    private val actionOptions = listOf(
        ActionOption("play_pause", "播放 / 暂停"),
        ActionOption("next", "下一曲"),
        ActionOption("previous", "上一曲"),
        ActionOption("voice", "语音助手"),
        ActionOption("volume_up", "音量 +"),
        ActionOption("volume_down", "音量 -")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        setupActionSpinners()
        loadSettings()
        setupListeners()
        refreshBluetoothSummary()
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
        binding.etAutoZeroStableSeconds.setText(String.format(Locale.US, "%.1f", prefs.autoZeroStabilityDurationMs / 1000f))
        binding.etAutoZeroStableBand.setText(prefs.autoZeroStabilityBand.toString())
        binding.etStrongSuppressionThreshold.setText(prefs.strongSuppressionThreshold.toString())
        binding.etStrongSuppressionDuration.setText(prefs.strongSuppressionDurationMs.toString())
        binding.etStrongSuppressionJitter.setText(prefs.strongSuppressionJitter.toString())

        when (prefs.poleMode) {
            "different" -> binding.rbDifferent.isChecked = true
            else -> binding.rbBothPoles.isChecked = true
        }

        setSpinnerSelection(binding.spAllShort, prefs.allShortAction)
        setSpinnerSelection(binding.spAllLong, prefs.allLongAction)
        setSpinnerSelection(binding.spNShort, prefs.nShortAction)
        setSpinnerSelection(binding.spNLong, prefs.nLongAction)
        setSpinnerSelection(binding.spSShort, prefs.sShortAction)
        setSpinnerSelection(binding.spSLong, prefs.sLongAction)
        binding.cbUsePolarity.isChecked = prefs.usePolarity

        updateActionVisibility()
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener { saveSettings() }

        binding.rgPoleMode.setOnCheckedChangeListener { _, _ ->
            updateActionVisibility()
        }

        binding.btnSelectBt.setOnClickListener {
            ensureBluetoothPermissionAndPick()
        }
    }

    private fun saveSettings() {
        var trigger = binding.etThresholdTrigger.text.toString().toFloatOrNull() ?: 500f
        var reset = binding.etThresholdReset.text.toString().toFloatOrNull() ?: 300f
        if (reset >= trigger) {
            reset = (trigger * 0.6f).coerceAtLeast(50f)
            trigger = reset + 10f
        }
        prefs.thresholdTrigger = trigger
        prefs.thresholdReset = reset

        prefs.thresholdResetDebounceMs = binding.etResetDebounce.text.toString().toLongOrNull() ?: 80L
        prefs.longPressDuration = binding.etLongPressMs.text.toString().toLongOrNull() ?: 1500L

        var polarityMin = binding.etPolarityMin.text.toString().toFloatOrNull() ?: 50f
        var polarityMax = binding.etPolarityMax.text.toString().toFloatOrNull() ?: 2000f
        if (polarityMin > polarityMax) {
            polarityMax = polarityMin + 10f
        }
        prefs.polarityMin = polarityMin
        prefs.polarityMax = polarityMax

        prefs.energySaveThreshold = binding.etEnergyThreshold.text.toString().toFloatOrNull() ?: 100f
        prefs.energySaveHoldMs = binding.etEnergyHoldMs.text.toString().toLongOrNull() ?: 2000L

        var samplingHigh = binding.etSamplingHighHz.text.toString().toFloatOrNull() ?: 50f
        var samplingLow = binding.etSamplingLowHz.text.toString().toFloatOrNull() ?: 15f
        if (samplingLow >= samplingHigh) {
            samplingLow = (samplingHigh * 0.6f).coerceAtLeast(5f)
        }
        prefs.samplingHighRateHz = samplingHigh
        prefs.samplingLowRateHz = samplingLow

        prefs.autoZeroThreshold = binding.etAutoZeroThreshold.text.toString().toFloatOrNull() ?: 80f
        prefs.autoZeroDurationMs = ((binding.etAutoZeroSeconds.text.toString().toFloatOrNull() ?: 4f) * 1000).toLong()
        prefs.autoZeroStabilityDurationMs = ((binding.etAutoZeroStableSeconds.text.toString().toFloatOrNull() ?: 4f) * 1000).toLong()
        prefs.autoZeroStabilityBand = binding.etAutoZeroStableBand.text.toString().toFloatOrNull() ?: 20f
        prefs.strongSuppressionThreshold = binding.etStrongSuppressionThreshold.text.toString().toFloatOrNull() ?: 1800f
        prefs.strongSuppressionDurationMs = binding.etStrongSuppressionDuration.text.toString().toLongOrNull() ?: 400L
        prefs.strongSuppressionJitter = binding.etStrongSuppressionJitter.text.toString().toFloatOrNull() ?: 40f
        prefs.usePolarity = binding.cbUsePolarity.isChecked

        prefs.poleMode = if (binding.rbDifferent.isChecked) "different" else "both"

        prefs.allShortAction = readActionFromSpinner(binding.spAllShort)
        prefs.allLongAction = readActionFromSpinner(binding.spAllLong)
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
            actionOptions.map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        listOf(
            binding.spAllShort,
            binding.spAllLong,
            binding.spNShort,
            binding.spNLong,
            binding.spSShort,
            binding.spSLong
        ).forEach {
            it.adapter = adapter
            it.prompt = "选择动作"
        }
    }

    private fun setSpinnerSelection(spinner: android.widget.Spinner, action: String) {
        val normalized = if (action == "media") "play_pause" else action
        val index = actionOptions.indexOfFirst { it.key == normalized }.takeIf { it >= 0 } ?: 0
        spinner.setSelection(index)
    }

    private fun readActionFromSpinner(spinner: android.widget.Spinner): String {
        val position = spinner.selectedItemPosition
        return actionOptions.getOrNull(position)?.key ?: "play_pause"
    }

    private fun updateActionVisibility() {
        val splitPoles = binding.rbDifferent.isChecked
        binding.groupAllPoleActions.visibility = if (splitPoles) android.view.View.GONE else android.view.View.VISIBLE
        binding.groupSplitPoleActions.visibility = if (splitPoles) android.view.View.VISIBLE else android.view.View.GONE
        binding.etPolarityMin.isEnabled = splitPoles
        binding.etPolarityMax.isEnabled = splitPoles
    }

    private fun refreshBluetoothSummary() {
        val allowed = prefs.allowedBtDevices
        val bonded = getBondedDevices()
        val map = bonded.associateBy { it.address.uppercase(Locale.US) }
        val displayNames = allowed.mapNotNull { entry ->
            when {
                entry.startsWith("name::") -> entry.removePrefix("name::")
                map.containsKey(entry.uppercase(Locale.US)) -> map[entry.uppercase(Locale.US)]?.name
                else -> entry
            }
        }.filter { it.isNotBlank() }.distinct()
        val text = when {
            allowed.isEmpty() -> getString(R.string.bt_selected_none)
            displayNames.isEmpty() -> getString(R.string.bt_selected_missing)
            else -> getString(R.string.bt_selected_prefix) + displayNames.joinToString("、")
        }
        binding.tvBtSelected.text = text
        binding.btnSelectBt.isEnabled = bluetoothAdapter != null
        if (bluetoothAdapter == null) {
            binding.tvBtSelected.text = getString(R.string.bt_not_supported)
        }
    }

    private fun ensureBluetoothPermissionAndPick() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, getString(R.string.bt_not_supported), Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        showBluetoothPicker()
    }

    private fun showBluetoothPicker() {
        val devices = getBondedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, getString(R.string.bt_no_bonded), Toast.LENGTH_SHORT).show()
            return
        }
        val allowed = prefs.allowedBtDevices.toMutableSet()
        val names = devices.map { "${it.name ?: "未命名设备"} (${it.address})" }.toTypedArray()
        val addresses = devices.map { it.address.uppercase(Locale.US) }.toTypedArray()
        val checked = addresses.map { addr -> allowed.any { it.equals(addr, ignoreCase = true) } }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bt_title))
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val addr = addresses[which]
                val nameKey = "name::${devices[which].name.orEmpty()}"
                if (isChecked) {
                    allowed.add(addr)
                    allowed.add(nameKey)
                } else {
                    allowed.remove(addr)
                    allowed.remove(nameKey)
                }
            }
            .setPositiveButton("确定") { _, _ ->
                // 补全名称兜底，防止部分机型地址空白
                devices.forEachIndexed { index, device ->
                    val addr = addresses[index]
                    val nameKey = "name::${device.name.orEmpty()}"
                    if (allowed.contains(addr)) {
                        allowed.add(nameKey)
                    }
                }
                prefs.allowedBtDevices = allowed
                refreshBluetoothSummary()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getBondedDevices(): List<BluetoothDevice> {
        if (bluetoothAdapter == null) return emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!granted) return emptyList()
        }
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    private data class ActionOption(val key: String, val label: String)
}
