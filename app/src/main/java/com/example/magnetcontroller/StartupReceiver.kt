package com.example.magnetcontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = AppPreferences(context)
        val shouldStart = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> prefs.autoStartOnBoot || prefs.skiModeEnabled
            "android.bluetooth.device.action.ACL_CONNECTED",
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" -> prefs.autoStartOnBluetooth || prefs.skiModeEnabled
            else -> false
        }
        if (shouldStart) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MagnetService::class.java))
            } catch (e: Exception) {
                Log.w("StartupReceiver", "Unable to start MagnetService from $action: ${e.message}")
            }
        }
    }
}
