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
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
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
    }

    private data class CompensatedField(
        val x: Float,
        val y: Float,
        val z: Float,
        val magnitude: Float
    )

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private lateinit var prefs: AppPreferences

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
    private var toneGenerator: ToneGenerator? = null
    private var lastMagSq: Float = 0f
    private var lastRawMag: Float = 0f
    private var lastUiMag = -1f
    private var lastUiPole = "none"
    private var lastUiStatus = ""
    private var isScreenOn = true
    private val vibrationHandler = Handler(Looper.getMainLooper())
    private var vibrationTimeout: Runnable? = null
    private val staleHandler = Handler(Looper.getMainLooper())
    private var staleRunnable: Runnable? = null
    private var forceHighUntil = 0L
    private var peakMagSinceTrigger = 0f
    private var releaseDropSince = 0L
    private val rapidReleaseWindowMs = 150L
    private var lastSensorEventMs = 0L
    private var biasX = 0f
    private var biasY = 0f
    private var biasZ = 0f
    private var biasInitialized = false
    private var stuckHighSince = 0L
    private var lastBiasLog = 0L

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
        startStaleMonitor()

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
            val targetDelay = if (lastMagSq >= energyThresholdSq) samplingHighDelayUs else samplingLowDelayUs
            applySamplingDelay(targetDelay)
        }
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

        if (now < forceHighUntil || magSq >= energyThresholdSq) {
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

    private fun compensateDrift(
        rawX: Float,
        rawY: Float,
        rawZ: Float,
        rawMag: Float,
        now: Long
    ): CompensatedField {
        if (!biasInitialized) {
            biasX = rawX
            biasY = rawY
            biasZ = rawZ
            biasInitialized = true
        }

        val deltaMag = abs(rawMag - lastRawMag)
        val calmAlpha = if (triggerStartTime == 0L && rawMag < prefs.thresholdReset * 0.7f) 0.03f else 0f
        if (calmAlpha > 0f) {
            biasX = lerp(biasX, rawX, calmAlpha)
            biasY = lerp(biasY, rawY, calmAlpha)
            biasZ = lerp(biasZ, rawZ, calmAlpha)
        }

        val stuckMagThreshold = prefs.thresholdTrigger * 0.95f
        val stuckDelta = 0.6f
        val allowBleedDuringTrigger = triggerStartTime == 0L || now - triggerStartTime > 260L
        if (allowBleedDuringTrigger && rawMag > stuckMagThreshold && deltaMag < stuckDelta) {
            if (stuckHighSince == 0L) stuckHighSince = now
            if (now - stuckHighSince > 420L) {
                val bleedAlpha = 0.08f
                biasX = lerp(biasX, rawX, bleedAlpha)
                biasY = lerp(biasY, rawY, bleedAlpha)
                biasZ = lerp(biasZ, rawZ, bleedAlpha)

                if (now - lastBiasLog > 1800L) {
                    logToUI("⚠️ 磁力计读数疑似拉偏，尝试自动复位")
                    lastBiasLog = now
                }
            }
        } else {
            stuckHighSince = 0L
            biasX *= 0.995f
            biasY *= 0.995f
            biasZ *= 0.995f
        }

        val compensatedX = rawX - biasX
        val compensatedY = rawY - biasY
        val compensatedZ = rawZ - biasZ
        val compensatedMag = sqrt(
            compensatedX * compensatedX + compensatedY * compensatedY + compensatedZ * compensatedZ
        )

        return CompensatedField(compensatedX, compensatedY, compensatedZ, compensatedMag)
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
        val rawMagSq = rawX * rawX + rawY * rawY + rawZ * rawZ
        val rawMag = sqrt(rawMagSq.toDouble()).toFloat()

        val (x, y, z, magnitude) = compensateDrift(rawX, rawY, rawZ, rawMag, now)

        lastMagSq = magnitude * magnitude
        lastRawMag = rawMag
        lastSensorEventMs = now

        updateSamplingRate(lastMagSq, now)

        val candidate = determinePole(x, y, z)

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

        sendBroadcastToUI(rawX, rawY, rawZ, rawMag, poleForUi, now)
        processLogic(magnitude, z, now, poleForUi)
    }

    private fun determinePole(x: Float, y: Float, z: Float): String {
        val absX = abs(x)
        val absY = abs(y)
        val absZ = abs(z)

        return when {
            absX >= absY && absX >= absZ -> if (x >= 0) "N" else "S"
            absY >= absX && absY >= absZ -> if (y >= 0) "N" else "S"
            else -> if (z >= 0) "N" else "S"
        }
    }

    private fun sendBroadcastToUI(x: Float, y: Float, z: Float, mag: Float, pole: String, now: Long) {
        val status = getStatusText()
        if (!isScreenOn) return

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
            prefs.poleMode == "different" -> "none"
            zValue >= 0 -> "N"
            else -> "S"
        }

        val hasRecognizedPole = poleForUi == "N" || poleForUi == "S" || activePole == "N" || activePole == "S"
        val shouldTrigger = when (prefs.poleMode) {
            "different" -> hasRecognizedPole
            else -> true
        }
        if (!shouldTrigger) return

        if (magnitude > prefs.thresholdTrigger) {
            resetBelowSince = 0L
            if (triggerStartTime == 0L) {
                triggerStartTime = now
                isLongPressTriggered = false
                peakMagSinceTrigger = magnitude
                releaseDropSince = 0L
                forceHighUntil = now + 2000
                startContinuousVibration()
                playTriggerChime()
                activePole = when {
                    poleForUi == "N" || poleForUi == "S" -> poleForUi
                    poleInstant == "N" || poleInstant == "S" -> poleInstant
                    activePole != "none" -> activePole
                    else -> stablePole
                }
            } else {
                if (
                    prefs.poleMode == "different" &&
                    (now - triggerStartTime) >= poleChangeAbortMs &&
                    (poleInstant == "N" || poleInstant == "S")
                ) {
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
                    if (poleForAction != "N" && poleForAction != "S") return

                    val action = if (poleForAction == "N") prefs.nLongAction else prefs.sLongAction

                    performAction(action)
                    isLongPressTriggered = true
                    lastActionTime = now
                }

                if (magnitude > peakMagSinceTrigger) {
                    peakMagSinceTrigger = magnitude
                    releaseDropSince = 0L
                } else if (peakMagSinceTrigger > 0f && magnitude <= peakMagSinceTrigger * 0.55f) {
                    if (releaseDropSince == 0L) {
                        releaseDropSince = now
                    } else if (now - releaseDropSince >= rapidReleaseWindowMs) {
                        forceHighUntil = now + 800
                        if (magnitude < prefs.thresholdTrigger * 0.7f) {
                            resetBelowSince = now - prefs.thresholdResetDebounceMs
                        }
                    }
                }
            }
        } else if (shouldRelease(magnitude, now)) {
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
                    if (poleForAction != "N" && poleForAction != "S") return

                    val action = if (poleForAction == "N") prefs.nShortAction else prefs.sShortAction

                    performAction(action)
                    lastActionTime = now
                }

                triggerStartTime = 0L
                isLongPressTriggered = false
                activePole = "none"
                peakMagSinceTrigger = 0f
                releaseDropSince = 0L
            }
        } else {
            resetBelowSince = 0L
        }
    }

    private fun shouldRelease(magnitude: Float, now: Long): Boolean {
        val hasRapidDrop = releaseDropSince != 0L && (now - releaseDropSince) >= rapidReleaseWindowMs
        if (hasRapidDrop && magnitude < prefs.thresholdTrigger * 0.7f) {
            return true
        }
        return magnitude < prefs.thresholdReset
    }

    private fun startStaleMonitor() {
        staleRunnable = Runnable {
            val now = System.currentTimeMillis()
            val staleThreshold = if (triggerStartTime != 0L) 220L else 700L
            val sinceLast = now - lastSensorEventMs

            if (lastSensorEventMs != 0L && sinceLast > staleThreshold) {
                refreshSensorListener()

                if (triggerStartTime != 0L) {
                    logToUI("⚠️ 传感器数据暂停，已保护性复位")
                    forceReleaseFallback(now)
                }
            }

            staleHandler.postDelayed(staleRunnable!!, 180L)
        }.also { staleHandler.postDelayed(it, 180L) }
    }

    private fun stopStaleMonitor() {
        staleRunnable?.let { staleHandler.removeCallbacks(it) }
        staleRunnable = null
    }

    private fun refreshSensorListener() {
        magnetometer?.let {
            sensorManager.unregisterListener(this, it)
            currentDelayUs = 0
            sensorManager.registerListener(this, it, samplingHighDelayUs, samplingHighDelayUs)
            currentDelayUs = samplingHighDelayUs
            forceHighUntil = System.currentTimeMillis() + 1200
        }
    }

    private fun forceReleaseFallback(now: Long) {
        stopVibration()

        if (triggerStartTime != 0L && !isLongPressTriggered) {
            val poleForAction = when {
                activePole == "N" || activePole == "S" -> activePole
                stablePole == "N" || stablePole == "S" -> stablePole
                else -> "none"
            }

            if (poleForAction == "N" || poleForAction == "S") {
                val action = if (poleForAction == "N") prefs.nShortAction else prefs.sShortAction
                performAction(action)
                lastActionTime = now
            }
        }

        triggerStartTime = 0L
        isLongPressTriggered = false
        activePole = "none"
        peakMagSinceTrigger = 0f
        releaseDropSince = 0L
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
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        }
    }

    private fun startContinuousVibration() {
        if (isContinuousVibrating) return
        val vibrator = getVibrator()
        if (vibrator.hasVibrator()) {
            val fallbackDuration = (longPressThresholdMs + 400L).coerceIn(800L, 4000L)
            vibrationTimeout?.let { vibrationHandler.removeCallbacks(it) }
            vibrationTimeout = Runnable {
                stopVibration()
                logToUI("ℹ️ 震动已自动停止，防止误触持续反馈")
            }.also { vibrationHandler.postDelayed(it, fallbackDuration) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(fallbackDuration, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(fallbackDuration)
            }
            isContinuousVibrating = true
        }
    }

    private fun stopVibration() {
        if (isContinuousVibrating) {
            getVibrator().cancel()
            vibrationTimeout?.let { vibrationHandler.removeCallbacks(it) }
            vibrationTimeout = null
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

    private fun lerp(current: Float, target: Float, alpha: Float): Float {
        return current + (target - current) * alpha
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
