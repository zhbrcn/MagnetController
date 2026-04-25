package com.example.magnetcontroller

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

class MagnetAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        broadcastState(true)
        try {
            ContextCompat.startForegroundService(this, Intent(this, MagnetService::class.java))
        } catch (e: Exception) {
            Log.w("MagnetAccessibility", "Unable to start MagnetService: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The service is kept intentionally passive. Existing action routing stays in MagnetService.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        broadcastState(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isConnected = false
        broadcastState(false)
        super.onDestroy()
    }

    private fun broadcastState(enabled: Boolean) {
        val intent = Intent("com.example.magnetcontroller.UPDATE_ACCESSIBILITY").apply {
            putExtra("enabled", enabled)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
