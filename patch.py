import os

PROJECT_ROOT = os.getcwd()
TARGET = os.path.join(PROJECT_ROOT, "app/src/main/java/com/example/magnetcontroller/MagnetService.kt")

print("🚀 覆盖 MagnetService.kt（v4，修复 True→true）")
print(f"📌 目标文件: {TARGET}")

SERVICE_CODE = r'''
package com.example.magnetcontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class MagnetService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null

    private var triggerStartTime = 0L
    private var isLongPressTriggered = false
    private val LONG_PRESS_DURATION = 1500L

    private var isContinuousVibrating = false

    private val THRESHOLD_TRIGGER = 500.0f
    private val THRESHOLD_RESET = 300.0f

    private val CHANNEL_ID = "MagnetServiceChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initSensor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("磁力服务运行中")
            .setContentText("后台磁力监听已启用")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Magnet Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun initSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        stopVibration()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_MAGNETIC_FIELD) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        broadcastToUI(x, y, z, magnitude)
        logicProcessing(magnitude)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 必须存在
    }

    private fun broadcastToUI(x: Float, y: Float, z: Float, mag: Float) {
        val intent = Intent("com.example.magnetcontroller.UPDATE_UI")
        intent.putExtra("x", x)
        intent.putExtra("y", y)
        intent.putExtra("z", z)
        intent.putExtra("mag", mag)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun logicProcessing(magnitude: Float) {

        val now = System.currentTimeMillis()

        if (magnitude > THRESHOLD_TRIGGER) {

            if (triggerStartTime == 0L) {
                triggerStartTime = now
                isLongPressTriggered = false
                startContinuousVibration()
            } else {
                if (!isLongPressTriggered && now - triggerStartTime > LONG_PRESS_DURATION) {
                    stopVibration()
                    playDoubleBeep()
                    triggerVoiceAssistant()
                    isLongPressTriggered = true
                }
            }

        } else if (magnitude < THRESHOLD_RESET) {

            if (triggerStartTime != 0L) {

                stopVibration()

                if (!isLongPressTriggered) {
                    triggerMediaKey()
                }

                triggerStartTime = 0L
                isLongPressTriggered = false
            }
        }
    }

    private fun triggerVoiceAssistant() {
        triggerVoiceAssistantByKeyEvent()
    }

    private fun triggerVoiceAssistantByKeyEvent() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = System.currentTimeMillis()

        val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOICE_ASSIST, 0)
        val up = KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOICE_ASSIST, 0)

        audio.dispatchMediaKeyEvent(down)
        audio.dispatchMediaKeyEvent(up)
    }

    private fun triggerMediaKey() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = System.currentTimeMillis()

        val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
        val up = KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)

        audio.dispatchMediaKeyEvent(down)
        audio.dispatchMediaKeyEvent(up)
    }

    private fun startContinuousVibration() {
        if (isContinuousVibrating) return
        val vib = vibrator()
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(5000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vib.vibrate(5000)
        }
        isContinuousVibrating = true
    }

    private fun stopVibration() {
        if (isContinuousVibrating) {
            vibrator().cancel()
            isContinuousVibrating = false
        }
    }

    private fun playDoubleBeep() {
        val vib = vibrator()
        val pattern = longArrayOf(0, 50, 100, 50)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vib.vibrate(pattern, -1)
        }
    }

    private fun vibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            mgr.defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}
'''

os.makedirs(os.path.dirname(TARGET), exist_ok=True)

with open(TARGET, "w", encoding="utf-8") as f:
    f.write(SERVICE_CODE)

print("✅ MagnetService.kt 覆盖成功（v4 修复 True→true）")
print("👉 Android Studio: Build → Clean Project → Run")
