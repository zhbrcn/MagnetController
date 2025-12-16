package com.example.magnetcontroller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.magnetcontroller.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logBuffer = mutableListOf<String>()

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.magnetcontroller.UPDATE_UI" -> {
                    val x = intent.getFloatExtra("x", 0f)
                    val y = intent.getFloatExtra("y", 0f)
                    val z = intent.getFloatExtra("z", 0f)
                    val mag = intent.getFloatExtra("mag", 0f)
                    val pole = intent.getStringExtra("pole") ?: "none"
                    val status = intent.getStringExtra("status") ?: ""
                    throttleUiUpdate(x, y, z, mag, pole, status)
                }
                "com.example.magnetcontroller.UPDATE_LOG" -> {
                    val log = intent.getStringExtra("log") ?: ""
                    addLog(log)
                }
            }
        }
    }

    private var lastUiUpdateTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        startMagnetService()

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnManualZero.setOnClickListener {
            requestManualZero()
        }
    }

    private fun startMagnetService() {
        val serviceIntent = Intent(this, MagnetService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("com.example.magnetcontroller.UPDATE_UI")
            addAction("com.example.magnetcontroller.UPDATE_LOG")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(updateReceiver)
    }

    private fun throttleUiUpdate(x: Float, y: Float, z: Float, magnitude: Float, pole: String, status: String) {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdateTime > 100) {
            binding.tvX.text = String.format(Locale.US, "X: %.1f", x)
            binding.tvY.text = String.format(Locale.US, "Y: %.1f", y)
            binding.tvZ.text = String.format(Locale.US, "Z: %.1f", z)
            binding.tvMagnitude.text = String.format(Locale.US, "%.0f μT", magnitude)

            val poleText = when (pole) {
                "N" -> getString(R.string.pole_n)
                "S" -> getString(R.string.pole_s)
                "all" -> getString(R.string.pole_all)
                else -> getString(R.string.pole_unknown)
            }
            binding.tvPoleType.text = poleText
            binding.tvPoleType.setTextColor(
                when (pole) {
                    "N" -> android.graphics.Color.parseColor("#60A5FA")
                    "S" -> android.graphics.Color.parseColor("#FCA5A5")
                    "all" -> android.graphics.Color.parseColor("#6B7280")
                    else -> android.graphics.Color.parseColor("#9FB0D3")
                }
            )

            val statusText = if (status.isBlank()) getString(R.string.status_listening) else status
            binding.tvStatus.text = getString(R.string.status_prefix, statusText)

            val statusColor = when {
                status.contains("触发") -> android.graphics.Color.parseColor("#34D399")
                status.contains("检测到") -> android.graphics.Color.parseColor("#FBBF24")
                else -> android.graphics.Color.parseColor("#E5E7EB")
            }
            binding.tvStatus.setTextColor(statusColor)
            lastUiUpdateTime = now
        }
    }

    private fun requestManualZero() {
        val intent = Intent(this, MagnetService::class.java).apply {
            action = MagnetService.ACTION_ZERO_SENSOR
        }
        ContextCompat.startForegroundService(this, intent)
        addLog(getString(R.string.manual_zero_requested))
    }

    private fun addLog(message: String) {
        if (message.isBlank()) return
        if (message.startsWith("⚠️") || message.startsWith("✅")) {
            binding.tvRouteHint.text = message
            return
        }
        logBuffer.add(0, message)
        if (logBuffer.size > 10) {
            logBuffer.removeLast()
        }
        binding.tvLog.text = logBuffer.joinToString("\n")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }
}
