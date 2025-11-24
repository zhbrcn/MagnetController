package com.example.magnetcontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MagnetService : Service(), SensorEventListener {
    companion object {
        const val ACTION_TRIGGER_VOICE = "com.example.magnetcontroller.TRIGGER_VOICE"
        const val ACTION_ZERO_SENSOR = "com.example.magnetcontroller.ZERO_SENSOR"
    }

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private lateinit var prefs: AppPreferences
    private var zeroOffsetX = 0f
    private var zeroOffsetY = 0f
    private var zeroOffsetZ = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastRawZ = 0f

    private var triggerStartTime = 0L
    private var isLongPressTriggered = false
    private var isContinuousVibrating = false
    private var stablePole: String = "none"
    private var poleCandidate: String = "none"
    private var poleCandidateSince = 0L
    private var activePole: String = "none"
    private var lockedPole: String = "none"
    private var lastActionTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastUiMag = -1f
    private var lastUiPole = "none"
    private var lastUiStatus = ""
    private var isScreenOn = true

    private val actionCooldownMs = 900L
    private var longPressThresholdMs = 1500L
    private var poleStableMs = 10L
    private var poleChangeAbortMs = 50L
    private var energyThresholdSq = 100f * 100f
    private var energyHoldMs = 2000L
    private var samplingHighDelayUs = 20_000
    private var samplingLowDelayUs = 66_000
    private var currentDelayUs = 0
    private var belowEnergySince = 0L
    private var resetBelowSince = 0L
    private val longPressPattern = longArrayOf(0, 200, 100, 200)
    private val CHANNEL_ID = "MagnetServiceChannel"
    private val TAG = "MagnetService"

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.magnetcontroller.RELOAD_SETTINGS") {
                loadSettings()
                logToUI("设置已重新加载")
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> isScreenOn = true
                Intent.ACTION_SCREEN_OFF -> isScreenOn = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        loadSettings()
        createNotificationChannel()
        initSensor()

        val filter = IntentFilter("com.example.magnetcontroller.RELOAD_SETTINGS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsReceiver, filter)
        }

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }
    }

    private fun loadSettings() {
        longPressThresholdMs = prefs.longPressDuration
        Log.d(TAG, "Settings loaded: trigger=${prefs.thresholdTrigger}, reset=${prefs.thresholdReset}, longPress=${longPressThresholdMs}ms")
        poleStableMs = prefs.polarityDebounceMs
        poleChangeAbortMs = (prefs.polarityDebounceMs * 3).coerceAtLeast(30L)
        energyThresholdSq = prefs.energySaveThreshold * prefs.energySaveThreshold
        energyHoldMs = prefs.energySaveHoldMs
        samplingHighDelayUs = hzToDelayUs(prefs.samplingHighRateHz, 20_000)
        samplingLowDelayUs = hzToDelayUs(prefs.samplingLowRateHz, 66_000)
        belowEnergySince = 0L
        resetBelowSince = 0L
        if (magnetometer != null) {
            applySamplingDelay(samplingHighDelayUs)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_VOICE -> {
                triggerVoiceAssistant()
                return START_STICKY
            }
            ACTION_ZERO_SENSOR -> {
                zeroBaseline()
                return START_STICKY
            }
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val triggerActivityIntent = Intent(this, VoiceTriggerActivity::class.java)
        val triggerPending = PendingIntent.getActivity(
            this,
            1,
            triggerActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("磁力服务运行中")
            .setContentText("正在后台监听磁场变化...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "唤醒助手", triggerPending)
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
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD).also {
                logToUI("⚠️ 当前设备未提供未校准磁力计，已回退到系统校准数据")
            }
        magnetometer?.let {
            sensorManager.registerListener(this, it, samplingHighDelayUs, samplingHighDelayUs)
            currentDelayUs = samplingHighDelayUs
        }
    }

    private fun applySamplingDelay(delayUs: Int) {
        if (magnetometer == null) return
        if (currentDelayUs == delayUs && currentDelayUs != 0) return
        sensorManager.unregisterListener(this, magnetometer)
        sensorManager.registerListener(this, magnetometer, delayUs, delayUs)
        currentDelayUs = delayUs
    }

    private fun hzToDelayUs(hz: Float, defaultDelayUs: Int): Int {
        if (hz <= 0f) return defaultDelayUs
        val delay = (1_000_000f / hz).roundToInt()
        return delay.coerceIn(5_000, 200_000)
    }

    private fun updateSamplingRate(magSq: Float, now: Long) {
        if (magnetometer == null) return
        if (magSq >= energyThresholdSq) {
            belowEnergySince = 0L
            if (currentDelayUs != samplingHighDelayUs) {
                applySamplingDelay(samplingHighDelayUs)
            }
        } else {
            if (belowEnergySince == 0L) {
                belowEnergySince = now
            } else if (now - belowEnergySince >= energyHoldMs && currentDelayUs != samplingLowDelayUs) {
                applySamplingDelay(samplingLowDelayUs)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        unregisterReceiver(settingsReceiver)
        unregisterReceiver(screenReceiver)
        stopVibration()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_MAGNETIC_FIELD && event?.sensor?.type != Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) return

        val now = System.currentTimeMillis()
        lastRawX = event.values[0]
        lastRawY = event.values[1]
        lastRawZ = event.values[2]
        val x = lastRawX - zeroOffsetX
        val y = lastRawY - zeroOffsetY
        val z = lastRawZ - zeroOffsetZ
        val magSq = x * x + y * y + z * z
        val magnitude = sqrt(magSq.toDouble()).toFloat()

        updateSamplingRate(magSq, now)

        val candidate = if (x >= z) "N" else "S"

        if (magnitude > prefs.polarityMax) {
            if (lockedPole == "none") {
                lockedPole = candidate
            }
        } else if (magnitude < prefs.polarityMin) {
            lockedPole = "none"
        } else {
            if (candidate != poleCandidate) {
                poleCandidate = candidate
                poleCandidateSince = now
            }
            if (candidate != stablePole && (now - poleCandidateSince) >= poleStableMs) {
                stablePole = candidate
            }
        }

        val poleForUi = if (lockedPole != "none") lockedPole else stablePole

        sendBroadcastToUI(x, y, z, magnitude, poleForUi)
        processLogic(magnitude, z, now, poleForUi)
    }

    private fun zeroBaseline() {
        zeroOffsetX = lastRawX
        zeroOffsetY = lastRawY
        zeroOffsetZ = lastRawZ

        stablePole = "none"
        poleCandidate = "none"
        poleCandidateSince = 0L
        activePole = "none"
        lockedPole = "none"
        triggerStartTime = 0L
        isLongPressTriggered = false
        resetBelowSince = 0L
        belowEnergySince = 0L
        stopVibration()

        lastUiMag = -1f
        logToUI("✅ 已手动归零 (X=${zeroOffsetX.roundToInt()}, Y=${zeroOffsetY.roundToInt()}, Z=${zeroOffsetZ.roundToInt()})")
    }

    private fun sendBroadcastToUI(x: Float, y: Float, z: Float, mag: Float, pole: String) {
        val status = getStatusText()
        val poleChanged = pole != lastUiPole
        val statusChanged = status != lastUiStatus
        val magChanged = abs(mag - lastUiMag) > 5f
        val isFirst = lastUiMag < 0f

        if (!isScreenOn) return

        if (isFirst || poleChanged || statusChanged || magChanged) {
            lastUiMag = mag
            lastUiPole = pole
            lastUiStatus = status

            val intent = Intent("com.example.magnetcontroller.UPDATE_UI")
            intent.putExtra("x", x)
            intent.putExtra("y", y)
            intent.putExtra("z", z)
            intent.putExtra("mag", mag)
            intent.putExtra("pole", pole)
            intent.putExtra("status", status)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
    }

    private fun logToUI(message: String) {
        val intent = Intent("com.example.magnetcontroller.UPDATE_LOG")
        intent.putExtra("log", message)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        Log.d(TAG, message)
    }

    private fun getStatusText(): String {
        return if (triggerStartTime > 0) {
            if (isLongPressTriggered) "已触发长按" else "检测到磁铁 (计时中...)"
        } else {
            "监听中..."
        }
    }

    private fun processLogic(magnitude: Float, zValue: Float, now: Long, poleForUi: String) {
        val inCooldown = now - lastActionTime < actionCooldownMs
        if (triggerStartTime == 0L && inCooldown) return

        val poleInstant = when {
            poleForUi == "N" || poleForUi == "S" -> poleForUi
            zValue >= 0 -> "N"
            else -> "S"
        }

        val shouldTrigger = when (prefs.poleMode) {
            "both" -> true
            "different" -> true
            else -> true
        }
        if (!shouldTrigger) return

        if (magnitude > prefs.thresholdTrigger) {
            resetBelowSince = 0L
            if (triggerStartTime == 0L) {
                triggerStartTime = now
                isLongPressTriggered = false
                startContinuousVibration()
                activePole = if (poleForUi == "N" || poleForUi == "S") poleForUi else poleInstant
            } else {
                if (prefs.poleMode == "different" && (now - triggerStartTime) >= poleChangeAbortMs) {
                    activePole = poleInstant
                }

                if (!isLongPressTriggered && (now - triggerStartTime >= longPressThresholdMs)) {
                    stopVibration()
                    isContinuousVibrating = false
                    playDoubleBeep()

                    val poleForAction = when {
                        poleForUi == "N" || poleForUi == "S" -> poleForUi
                        poleInstant == "N" || poleInstant == "S" -> poleInstant
                        activePole == "N" || activePole == "S" -> activePole
                        else -> stablePole
                    }
                    if (prefs.poleMode == "different" && (poleForAction != "N" && poleForAction != "S")) return

                    val action = if (prefs.poleMode == "different") {
                        if (poleForAction == "N") prefs.nLongAction else prefs.sLongAction
                    } else {
                        if (poleForAction == "N") prefs.nLongAction else prefs.sLongAction
                    }

                    performAction(action)
                    isLongPressTriggered = true
                    lastActionTime = now
                }
            }
        } else if (magnitude < prefs.thresholdReset) {
            if (resetBelowSince == 0L) {
                resetBelowSince = now
            }
            if (now - resetBelowSince < prefs.thresholdResetDebounceMs) return

            if (triggerStartTime != 0L) {
                stopVibration()

                if (!isLongPressTriggered) {
                    val poleForAction = when {
                        poleForUi == "N" || poleForUi == "S" -> poleForUi
                        poleInstant == "N" || poleInstant == "S" -> poleInstant
                        activePole == "N" || activePole == "S" -> activePole
                        else -> stablePole
                    }
                    if (prefs.poleMode == "different" && (poleForAction != "N" && poleForAction != "S")) return

                    val action = if (prefs.poleMode == "different") {
                        if (poleForAction == "N") prefs.nShortAction else prefs.sShortAction
                    } else {
                        if (poleForAction == "N") prefs.nShortAction else prefs.sShortAction
                    }

                    performAction(action)
                    lastActionTime = now
                }

                triggerStartTime = 0L
                isLongPressTriggered = false
                activePole = "none"
            }
        } else {
            resetBelowSince = 0L
        }
    }

    private fun startContinuousVibration() {
        if (isContinuousVibrating) return
        val vibrator = getVibrator()
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(5000, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(5000)
            }
            isContinuousVibrating = true
        }
    }

    private fun stopVibration() {
        if (isContinuousVibrating) {
            getVibrator().cancel()
            isContinuousVibrating = false
        }
    }

    private fun playDoubleBeep() {
        val vibrator = getVibrator()
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longPressPattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longPressPattern, -1)
            }
        }
    }

    private fun performAction(action: String) {
        when (action) {
            "voice" -> triggerVoiceAssistant()
            "next" -> triggerMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "下一曲")
            "previous" -> triggerMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "上一曲")
            "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE, "音量 +")
            "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER, "音量 -")
            "play_pause", "media" -> triggerMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "播放/暂停")
            else -> triggerMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "播放/暂停")
        }
    }

    private fun triggerVoiceAssistant() {
        if (AccessibilityVoiceService.requestVoice(this)) {
            logToUI("▶️ 正在通过无障碍服务唤醒助手...")
            return
        }

        acquireWakeLock(4000)

        logToUI("▶️ 尝试方案：VOICE_COMMAND")
        if (tryVoiceCommandGeneric()) {
            logToUI("✅ 成功：VOICE_COMMAND")
        } else {
            logToUI("⚠️ VOICE_COMMAND 失败，请检查后台弹窗/默认助手权限")
        }
        releaseWakeLock()
    }

    private fun tryPlayPauseLongPress(): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val eventTime = System.currentTimeMillis()
            val keyEventDown = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            val keyEventUp = KeyEvent(eventTime + 1100, eventTime + 1100, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            audioManager.dispatchMediaKeyEvent(keyEventDown)
            Thread.sleep(1100)
            audioManager.dispatchMediaKeyEvent(keyEventUp)
            true
        } catch (e: Exception) {
            logToUI("⚠️ 媒体键长按失败 ${e.message}")
            false
        }
    }

    private fun tryVoiceCommandGeneric(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            logToUI("⚠️ VOICE_COMMAND 失败: ${e.message}")
            false
        }
    }

    private fun tryWebSearch(packageName: String?): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (!packageName.isNullOrBlank()) setPackage(packageName)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            logToUI("⚠️ WEB_SEARCH 失败${if (!packageName.isNullOrBlank()) " ($packageName)" else ""}: ${e.message}")
            false
        }
    }

    private fun acquireWakeLock(durationMs: Long) {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MagnetService:Voice").apply {
                setReferenceCounted(false)
                acquire(durationMs)
            }
        } catch (e: SecurityException) {
            logToUI("⚠️ 获取唤醒锁失败：缺少 WAKE_LOCK 权限或被限制")
        } catch (e: Exception) {
            logToUI("⚠️ 获取唤醒锁失败 ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun tryGoogleAssistant(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                setPackage("com.google.android.googlequicksearchbox")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            logToUI("⚠️ VOICE_COMMAND Google 失败: ${e.message}")
            false
        }
    }

    private fun triggerMediaKey(keyCode: Int, label: String) {
        logToUI("▶️ $label")
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = System.currentTimeMillis()
        val keyEventDown = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val keyEventUp = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        try {
            audioManager.dispatchMediaKeyEvent(keyEventDown)
            audioManager.dispatchMediaKeyEvent(keyEventUp)
        } catch (e: Exception) {
            logToUI("⚠️ 媒体按键失败: ${e.message}")
        }
    }

    private fun adjustVolume(direction: Int, label: String) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        logToUI("🔊 $label")
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            logToUI("⚠️ 音量调整失败: ${e.message}")
        }
    }

    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
