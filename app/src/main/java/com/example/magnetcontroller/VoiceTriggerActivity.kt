package com.example.magnetcontroller

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class VoiceTriggerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 在前台上下文直接执行唤醒逻辑
        val serviceIntent = Intent(this, MagnetService::class.java)
        startService(serviceIntent)

        val cmdIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(cmdIntent)
            finish()
            overridePendingTransition(0, 0)
            return
        } catch (_: Exception) {
        }

        val xiaoaiIntent = Intent("android.intent.action.VOICE_ASSIST").apply {
            setPackage("com.miui.voiceassist")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(xiaoaiIntent)
        } catch (_: Exception) {
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
