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
    private enum class LogCategory { ROUTE, ACTION, STATUS, ERROR, UNKNOWN }

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
                    binding.tvStatus.contentDescription = if (enabled) {
                        getString(R.string.content_desc_accessibility_enabled)
                    } else {
                        getString(R.string.content_desc_accessibility_disabled)
                    }
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
                "N" -> getString(R.string.pole_n_label)
                "S" -> getString(R.string.pole_s_label)
                "all" -> getString(R.string.pole_all_label)
                else -> getString(R.string.pole_unknown_label)
            }
            val rateText = if (sampleHz >= 40f) getString(R.string.sampling_high) else getString(R.string.sampling_low)
            val magnitudeText = String.format(Locale.US, getString(R.string.magnitude_value), magnitude)
            val combined = String.format(Locale.US, getString(R.string.combined_value_format), magnitudeText, nsText, rateText)
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

            binding.tvXBlock.text = String.format(Locale.US, getString(R.string.axis_value), "X", x)
            binding.tvYBlock.text = String.format(Locale.US, getString(R.string.axis_value), "Y", y)
            binding.tvZBlock.text = String.format(Locale.US, getString(R.string.axis_value), "Z", z)

            val resolvedStatus = if (status.isBlank()) getString(R.string.status_listening) else status
            binding.tvStatus.text = getString(R.string.status_prefix, resolvedStatus)

            val statusColor = when (resolvedStatus) {
                getString(R.string.status_timing), getString(R.string.status_detecting) -> Color.parseColor("#FBBF24")
                getString(R.string.status_timing_long), getString(R.string.status_long_pressed) -> Color.parseColor("#34D399")
                getString(R.string.status_cooldown_only) -> Color.parseColor("#A78BFA")
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
        val (category, content) = parseLogEntry(message)
        if (category == LogCategory.ROUTE) {
            binding.tvRouteHint.text = content
            return
        }
        val display = formatLogForDisplay(category, content)
        logBuffer.add(0, display)
        if (logBuffer.size > maxLogs) {
            logBuffer.removeLast()
        }
        binding.tvLog.text = logBuffer.joinToString("\n")
    }

    private fun applyRecentLogs(logs: List<String>) {
        logBuffer.clear()
        var latestRoute: String? = null
        logs.takeLast(maxLogs).reversed().forEach { entry ->
            val (category, content) = parseLogEntry(entry)
            if (category == LogCategory.ROUTE) {
                latestRoute = content
            } else {
                val display = formatLogForDisplay(category, content)
                logBuffer.add(display)
            }
        }
        binding.tvLog.text = logBuffer.joinToString("\n")
        latestRoute?.let { binding.tvRouteHint.text = it }
    }

    private fun formatLogForDisplay(category: LogCategory, content: String): String {
        return when (category) {
            LogCategory.UNKNOWN -> content
            LogCategory.ROUTE -> content
            else -> "[${category.name}] $content"
        }
    }

    private fun parseLogEntry(message: String): Pair<LogCategory, String> {
        return when {
            message.startsWith("[ROUTE]") -> LogCategory.ROUTE to message.removePrefix("[ROUTE]").trim()
            message.startsWith("[ACTION]") -> LogCategory.ACTION to message.removePrefix("[ACTION]").trim()
            message.startsWith("[ERROR]") -> LogCategory.ERROR to message.removePrefix("[ERROR]").trim()
            message.startsWith("[STATUS]") -> LogCategory.STATUS to message.removePrefix("[STATUS]").trim()
            else -> LogCategory.UNKNOWN to message
        }
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

