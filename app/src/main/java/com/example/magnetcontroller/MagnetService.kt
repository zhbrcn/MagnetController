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
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler as AndroidHandler
import android.os.IBinder
import android.os.Looper as AndroidLooper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class MagnetService : Service(), SensorEventListener {
    companion object {
        const val ACTION_TRIGGER_VOICE = "com.example.magnetcontroller.TRIGGER_VOICE"
    }

    private enum class Pole { NONE, NORTH, SOUTH, UNKNOWN }
    private enum class PressState { IDLE, PRESSED }

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private lateinit var prefs: AppPreferences

    private var pressState: PressState = PressState.IDLE
    private var pressPole: Pole = Pole.NONE
    private var pressStartMs = 0L
    private var longPressTriggered = false
    private var releaseSinceMs = 0L

    private var filteredZ = 0f
    private val baseline = floatArrayOf(0f, 0f, 0f)
    private var baselineSamples = 0
    private var baselineReady = false
    private var lastRaw: FloatArray? = null

    private var candidatePole: Pole = Pole.NONE
    private var candidateSinceMs = 0L

    private var lastSensorEventMs = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var toneGenerator: ToneGenerator? = null
    private var isScreenOn = true

    private val vibrationHandler = AndroidHandler(AndroidLooper.getMainLooper())
    private var vibrationTimeout: Runnable? = null
    private val staleHandler = AndroidHandler(AndroidLooper.getMainLooper())
    private var staleRunnable: Runnable? = null

    private var pressThreshold = 180f
    private var releaseThreshold = 90f
    private var pressDebounceMs = 90L
    private var releaseDebounceMs = 110L
    private var longPressThresholdMs = 1500L
    private val baselineDriftAlpha = 0.004f
    private val saturationLimit = 900f
    private val staleCheckMs = 180L

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
        startStaleWatchdog()

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
        pressThreshold = prefs.pressThreshold
        releaseThreshold = prefs.releaseThreshold
        pressDebounceMs = prefs.pressDebounceMs
        releaseDebounceMs = prefs.releaseDebounceMs
        longPressThresholdMs = prefs.longPressDuration
        Log.d(TAG, "Settings loaded: press=$pressThreshold, release=$releaseThreshold, pressDebounce=$pressDebounceMs, releaseDebounce=$releaseDebounceMs, longPress=$longPressThresholdMs")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRIGGER_VOICE) {
            triggerVoiceAssistant()
            return START_STICKY
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
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        magnetometer?.let {
            // Prefer the fastest rate for responsiveness; fall back gracefully if permission is missing.
            try {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            } catch (e: SecurityException) {
                logToUI("⚠️ 高采样率权限缺失，已降级刷新率：" + (e.message ?: ""))
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
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
        stopStaleMonitor()
        toneGenerator?.release()
        toneGenerator = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_MAGNETIC_FIELD) return

        val now = System.currentTimeMillis()
        val rawX = event.values[0]
        val rawY = event.values[1]
        val rawZ = event.values[2]

        lastSensorEventMs = now
        accumulateBaseline(rawX, rawY, rawZ)

        val deltaZ = if (baselineReady) rawZ - baseline[2] else rawZ
        filteredZ = 0.82f * filteredZ + 0.18f * deltaZ

        val saturated = isSaturated(event.values, lastRaw)
        if (!saturated) {
            softlyRecenterBaseline(rawX, rawY, rawZ)
        }

        val pole = resolvePole(filteredZ, saturated, now)

        sendBroadcastToUI(rawX, rawY, rawZ, pole)
        processStateMachine(pole, saturated, now)

        lastRaw = floatArrayOf(rawX, rawY, rawZ)
    }

    private fun accumulateBaseline(x: Float, y: Float, z: Float) {
        if (baselineReady) return
        baseline[0] += x
        baseline[1] += y
        baseline[2] += z
        baselineSamples++
        if (baselineSamples >= 120) {
            baseline[0] /= baselineSamples
            baseline[1] /= baselineSamples
            baseline[2] /= baselineSamples
            baselineReady = true
        }
    }

    private fun softlyRecenterBaseline(x: Float, y: Float, z: Float) {
        if (!baselineReady) return
        if (pressState != PressState.IDLE) return
        val calm = abs(filteredZ) < releaseThreshold * 0.6f
        if (calm) {
            baseline[0] = baseline[0] + (x - baseline[0]) * baselineDriftAlpha
            baseline[1] = baseline[1] + (y - baseline[1]) * baselineDriftAlpha
            baseline[2] = baseline[2] + (z - baseline[2]) * baselineDriftAlpha
        }
    }

    private fun resolvePole(deltaZ: Float, saturated: Boolean, now: Long): Pole {
        val candidate = when {
            saturated -> Pole.UNKNOWN
            deltaZ > pressThreshold -> Pole.NORTH
            deltaZ < -pressThreshold -> Pole.SOUTH
            else -> Pole.NONE
        }

        if (candidate != candidatePole) {
            candidatePole = candidate
            candidateSinceMs = now
        }

        val stable = (candidate == Pole.NORTH || candidate == Pole.SOUTH) && (now - candidateSinceMs) >= pressDebounceMs
        return if (stable) candidate else if (candidate == Pole.UNKNOWN) Pole.UNKNOWN else Pole.NONE
    }

    private fun processStateMachine(pole: Pole, saturated: Boolean, now: Long) {
        when (pressState) {
            PressState.IDLE -> {
                if ((pole == Pole.NORTH || pole == Pole.SOUTH) && !saturated) {
                    startPress(pole, now)
                }
            }
            PressState.PRESSED -> {
                handlePressed(pole, saturated, now)
            }
        }
    }

    private fun startPress(pole: Pole, now: Long) {
        pressState = PressState.PRESSED
        pressPole = pole
        pressStartMs = now
        longPressTriggered = false
        releaseSinceMs = 0L
        startContinuousVibration()
        playTriggerChime()
        logToUI("按下 ${pole.name}")
    }

    private fun handlePressed(pole: Pole, saturated: Boolean, now: Long) {
        if (!longPressTriggered && now - pressStartMs >= longPressThresholdMs) {
            longPressTriggered = true
            stopVibration()
            playDoubleBeep()
            dispatchAction(true)
            return
        }

        val withinRelease = abs(filteredZ) < releaseThreshold && !saturated
        if (withinRelease) {
            if (releaseSinceMs == 0L) releaseSinceMs = now
            if (now - releaseSinceMs >= releaseDebounceMs) {
                stopVibration()
                if (!longPressTriggered) {
                    dispatchAction(false)
                }
                resetPressState()
            }
        } else {
            releaseSinceMs = 0L
        }
    }

    private fun dispatchAction(isLong: Boolean) {
        val action = when {
            pressPole == Pole.NORTH && isLong -> prefs.nLongAction
            pressPole == Pole.NORTH -> prefs.nShortAction
            pressPole == Pole.SOUTH && isLong -> prefs.sLongAction
            pressPole == Pole.SOUTH -> prefs.sShortAction
            else -> "play_pause"
        }
        performAction(action)
    }

    private fun resetPressState() {
        pressState = PressState.IDLE
        pressPole = Pole.NONE
        pressStartMs = 0L
        releaseSinceMs = 0L
        longPressTriggered = false
    }

    private fun isSaturated(values: FloatArray, last: FloatArray?): Boolean {
        val bx = abs(values[0])
        val by = abs(values[1])
        val bz = abs(values[2])
        if (max(max(bx, by), bz) > saturationLimit) return true
        if (last != null) {
            if (abs(values[2] - last[2]) > 400f) return true
        }
        return false
    }

    private fun sendBroadcastToUI(x: Float, y: Float, z: Float, pole: Pole) {
        if (!isScreenOn) return
        val mag = sqrt(x * x + y * y + z * z)
        val intent = Intent("com.example.magnetcontroller.UPDATE_UI")
        intent.putExtra("x", x)
        intent.putExtra("y", y)
        intent.putExtra("z", z)
        intent.putExtra("mag", mag)
        intent.putExtra("pole", poleToString(pole))
        intent.putExtra("status", getStatusText())
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun poleToString(pole: Pole): String {
        return when (pole) {
            Pole.NORTH -> "N"
            Pole.SOUTH -> "S"
            Pole.UNKNOWN -> "unknown"
            else -> "none"
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
        return when (pressState) {
            PressState.PRESSED -> if (longPressTriggered) "长按已触发" else "按压中..."
            PressState.IDLE -> "监听中..."
        }
    }

    private fun startStaleWatchdog() {
        staleRunnable = Runnable {
            val now = System.currentTimeMillis()
            val staleThreshold = if (pressState == PressState.PRESSED) 250L else 800L
            val sinceLast = now - lastSensorEventMs
            if (lastSensorEventMs != 0L && sinceLast > staleThreshold) {
                refreshSensorListener()
                if (pressState == PressState.PRESSED && !longPressTriggered) {
                    logToUI("⚠️ 传感器暂停，已保护性释放")
                    stopVibration()
                    dispatchAction(false)
                    resetPressState()
                }
            }
            staleHandler.postDelayed(staleRunnable!!, staleCheckMs)
        }.also { staleHandler.postDelayed(it, staleCheckMs) }
    }

    private fun stopStaleMonitor() {
        staleRunnable?.let { staleHandler.removeCallbacks(it) }
        staleRunnable = null
    }

    private fun refreshSensorListener() {
        magnetometer?.let {
            sensorManager.unregisterListener(this, it)
            try {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            } catch (e: SecurityException) {
                logToUI("⚠️ 重新注册传感器时缺少高采样权限，降级刷新率")
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    private fun playTriggerChime() {
        try {
            if (!isBluetoothAudioActive()) {
                logToUI("⚠️ 未检测到蓝牙耳机，已跳过提示音")
                return
            }
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.isSpeakerphoneOn = false
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 160)
        } catch (e: Exception) {
            logToUI("⚠️ 提示音播放失败: ${e.message}")
        }
    }

    private fun isBluetoothAudioActive(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        }
    }

    private fun startContinuousVibration() {
        val vibrator = getVibrator()
        if (!vibrator.hasVibrator()) return
        val fallbackDuration = (longPressThresholdMs + 400L).coerceIn(800L, 4000L)
        vibrationTimeout?.let { vibrationHandler.removeCallbacks(it) }
        vibrationTimeout = Runnable {
            stopVibration()
            logToUI("ℹ️ 震动已自动停止，防止误触持续反馈")
            resetPressState()
        }.also { vibrationHandler.postDelayed(it, fallbackDuration) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(fallbackDuration, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(fallbackDuration)
        }
    }

    private fun stopVibration() {
        vibrationTimeout?.let { vibrationHandler.removeCallbacks(it) }
        vibrationTimeout = null
        getVibrator().cancel()
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

    private fun acquireWakeLock(durationMs: Long) {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MagnetService:Voice").apply {
                setReferenceCounted(false)
                acquire(durationMs)
                logToUI("⚡ 已获取唤醒锁，保持 ${durationMs}ms")
            }
        } catch (e: SecurityException) {
            logToUI("⚠️ 获取唤醒锁失败：缺少 WAKE_LOCK 权限或被限制")
        } catch (e: Exception) {
            logToUI("⚠️ 获取唤醒锁失败 ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
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
