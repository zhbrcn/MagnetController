package com.example.magnetcontroller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
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
    private var lastUiUpdateTime = 0L
    private val maxLogs = 100

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
                    val sampleHz = intent.getFloatExtra("sample_hz", 0f)
                    throttleUiUpdate(x, y, z, mag, pole, status, sampleHz)
                }
                "com.example.magnetcontroller.UPDATE_LOG" -> {
                    val log = intent.getStringExtra("log") ?: ""
                    addLog(log)
                }
                "com.example.magnetcontroller.UPDATE_ACCESSIBILITY" -> {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    binding.tvStatus.contentDescription = if (enabled) "无障碍已开启" else "无障碍未开启"
                }
                "com.example.magnetcontroller.UPDATE_RECENT_LOGS" -> {
                    val logs = intent.getStringArrayListExtra("logs") ?: arrayListOf()
                    applyRecentLogs(logs)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTitleSpan()
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
            addAction("com.example.magnetcontroller.UPDATE_ACCESSIBILITY")
            addAction("com.example.magnetcontroller.UPDATE_RECENT_LOGS")
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

    private fun throttleUiUpdate(x: Float, y: Float, z: Float, magnitude: Float, pole: String, status: String, sampleHz: Float) {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdateTime > 100) {
            val nsText = when (pole) {
                "N" -> "N极"
                "S" -> "S极"
                "all" -> "全部"
                else -> "--"
            }
            val rateText = if (sampleHz >= 40f) "高采样" else "低采样"
            val combined = String.format(Locale.US, "%-10s %-4s %s", String.format(Locale.US, "%.1f μT", magnitude), nsText, rateText)
            val span = android.text.SpannableString(combined)
            val nsStart = combined.indexOf(nsText)
            if (nsStart >= 0) {
                val color = when (pole) {
                    "N" -> Color.parseColor("#2563EB")
                    "S" -> Color.parseColor("#DC2626")
                    else -> Color.parseColor("#4B5563")
                }
                span.setSpan(android.text.style.ForegroundColorSpan(color), nsStart, nsStart + nsText.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            binding.tvCombined.text = span

            binding.tvXBlock.text = String.format(Locale.US, "X %.1f μT", x)
            binding.tvYBlock.text = String.format(Locale.US, "Y %.1f μT", y)
            binding.tvZBlock.text = String.format(Locale.US, "Z %.1f μT", z)

            val statusText = if (status.isBlank()) getString(R.string.status_listening) else status
            binding.tvStatus.text = getString(R.string.status_prefix, statusText)

            val statusColor = when {
                statusText.contains("触发") -> Color.parseColor("#34D399")
                statusText.contains("计时") -> Color.parseColor("#FBBF24")
                statusText.contains("冷却") -> Color.parseColor("#A78BFA")
                else -> Color.parseColor("#0F172A")
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
        if (message.startsWith("⚠️") || message.startsWith("✅") || message.startsWith("ℹ️")) {
            binding.tvRouteHint.text = message
            return
        }
        logBuffer.add(0, message)
        if (logBuffer.size > maxLogs) {
            logBuffer.removeLast()
        }
        binding.tvLog.text = logBuffer.joinToString("\n")
    }

    private fun applyRecentLogs(logs: List<String>) {
        logBuffer.clear()
        logBuffer.addAll(logs.takeLast(maxLogs).reversed())
        binding.tvLog.text = logBuffer.joinToString("\n")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun applyTitleSpan() {
        val text = "MagnetController"
        val split = "Magnet".length
        val span = android.text.SpannableString(text)
        span.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#2563EB")), 0, split, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#DC2626")), split, text.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.tvTitle.text = span
    }
}

