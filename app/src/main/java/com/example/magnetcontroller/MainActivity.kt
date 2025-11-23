package com.example.magnetcontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.magnetcontroller.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logBuffer = mutableListOf<String>()

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.magnetcontroller.UPDATE_UI") {
                val x = intent.getFloatExtra("x", 0f)
                val y = intent.getFloatExtra("y", 0f)
                val z = intent.getFloatExtra("z", 0f)
                val mag = intent.getFloatExtra("mag", 0f)
                val pole = intent.getStringExtra("pole") ?: "none"
                val status = intent.getStringExtra("status") ?: ""

                throttleUiUpdate(x, y, z, mag, pole, status)
            } else if (intent?.action == "com.example.magnetcontroller.UPDATE_LOG") {
                val log = intent.getStringExtra("log") ?: ""
                addLog(log)
            }
        }
    }

    private var lastUiUpdateTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startMagnetService()

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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
                "N" -> "极性: N极 (北极)"
                "S" -> "极性: S极 (南极)"
                else -> "极性: 未检测"
            }
            binding.tvPoleType.text = poleText
            binding.tvPoleType.setTextColor(
                when (pole) {
                    "N" -> android.graphics.Color.BLUE
                    "S" -> android.graphics.Color.RED
                    else -> android.graphics.Color.GRAY
                }
            )

            binding.tvStatus.text = "状态: $status"

            if (status.contains("触发")) {
                binding.tvStatus.setTextColor(android.graphics.Color.BLUE)
            } else if (status.contains("检测到")) {
                binding.tvStatus.setTextColor(android.graphics.Color.MAGENTA)
            } else {
                binding.tvStatus.setTextColor(android.graphics.Color.BLACK)
            }
            lastUiUpdateTime = now
        }
    }

    private fun addLog(message: String) {
        logBuffer.add(0, message)
        if (logBuffer.size > 5) {
            logBuffer.removeLast()
        }
        binding.tvLog.text = logBuffer.joinToString("\n")
    }
}