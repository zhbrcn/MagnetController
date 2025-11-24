package com.example.magnetcontroller

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.accessibilityservice.AccessibilityService

class AccessibilityVoiceService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: AccessibilityVoiceService? = null

        fun requestVoice(context: Context): Boolean {
            val svc = instance
            return if (svc != null) {
                svc.triggerVoiceAssistantInternal()
                true
            } else {
                // 引导开启无障碍
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun triggerVoiceAssistantInternal() {
        val svc = this
        val ok = try {
            val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val intent = Intent("android.intent.action.VOICE_ASSIST").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!ok) {
            // 兜底小爱
            try {
                val intent = Intent("android.intent.action.VOICE_ASSIST").apply {
                    setPackage("com.miui.voiceassist")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }
}
