
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
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MagnetService : Service(), SensorEventListener {
    companion object {
        const val ACTION_TRIGGER_VOICE = "com.example.magnetcontroller.TRIGGER_VOICE"
        const val ACTION_ZERO_SENSOR = "com.example.magnetcontroller.ZERO_SENSOR"
    }

    private enum class State { IDLE, TIMING, COOLDOWN }

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private lateinit var prefs: AppPreferences

    private var zeroOffsetX = 0f
    private var zeroOffsetY = 0f
    private var zeroOffsetZ = 0f

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastRawZ = 0f

    private var state: State = State.IDLE
    private var triggerStartTime = 0L
    private var isLongPressTriggered = false
    private var activePole: String = "none"
    private var lastActionTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var isScreenOn = true

    private val actionCooldownMs = 900L
    private var longPressThresholdMs = 1500L
    private var strongSuppressionThreshold = 1800f
    private var strongSuppressionDurationMs = 400L
    private var strongSuppressionJitter = 40f
    private var strongSuppressionStart = 0L
    private var strongSuppressionMin = 0f
    private var strongSuppressionMax = 0f
    private var strongSuppressionLatched = false

    private var thresholdTrigger = 500f
    private var thresholdReset = 300f

    private var usePolarity = false
    private var polarityWindowMs = 80
    private var strongLockHoldMs = 600L
    private var strongLockUntil = 0L

    private var energyThresholdSq = 100f * 100f
    private var energyHoldMs = 2000L
    private var samplingHighDelayUs = 20_000
    private var samplingLowDelayUs = 66_000
    private var currentDelayUs = 0
    private var belowEnergySince = 0L
    private var pendingInitialZero = true

    private var autoZeroThreshold = 80f
    private var autoZeroDurationMs = 4000L
    private var autoZeroStabilityBand = 20f
    private var autoZeroStabilityDurationMs = 4000L
    private var autoZeroSince = 0L
    private var autoZeroStableStart = 0L
    private var autoZeroStableMin = 0f
    private var autoZeroStableMax = 0f
    private var autoZeroLatched = false

    private var allowedBtDevices: Set<String> = emptySet()
    private var lastRouteLog: String? = null
    private var lastAudioRouteAllowed = false
    private var enableSoundFeedback = true
    private var enableVoiceFeedback = false
    private var enableVibrationFeedback = true

    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingTtsText: String? = null

    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<String, Int>()
    private val soundReleaseHandler = Handler(Looper.getMainLooper())
    private val soundReleaseDelayMs = 60_000L
    private val soundReleaseRunnable = Runnable { releaseSoundEffects() }
    private val longPressPattern = longArrayOf(0, 200, 100, 200)
    private val CHANNEL_ID = "MagnetServiceChannel"
    private val TAG = "MagnetService"

    private val magWindow = ArrayDeque<Float>()
    private var noiseMean = 0f
    private var noiseM2 = 0f
    private var noiseSamples = 0
    private val filterWindowSize = 5
    private val noiseMultiplier = 3.0f

    private val recentLogs = ArrayDeque<String>()
    private val maxRecentLogs = 100

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
        syncRecentLogsToUi()

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
        thresholdTrigger = prefs.thresholdTrigger
        thresholdReset = prefs.thresholdReset
        usePolarity = prefs.usePolarity && prefs.poleMode == "different"
        energyThresholdSq = prefs.energySaveThreshold * prefs.energySaveThreshold
        energyHoldMs = prefs.energySaveHoldMs
        samplingHighDelayUs = hzToDelayUs(prefs.samplingHighRateHz, 20_000)
        samplingLowDelayUs = hzToDelayUs(prefs.samplingLowRateHz, 66_000)
        autoZeroThreshold = prefs.autoZeroThreshold
        autoZeroDurationMs = prefs.autoZeroDurationMs
        autoZeroStabilityBand = prefs.autoZeroStabilityBand
        autoZeroStabilityDurationMs = prefs.autoZeroStabilityDurationMs
        strongSuppressionThreshold = prefs.strongSuppressionThreshold
        strongSuppressionDurationMs = prefs.strongSuppressionDurationMs
        strongSuppressionJitter = prefs.strongSuppressionJitter
        allowedBtDevices = prefs.allowedBtDevices
        enableSoundFeedback = prefs.enableFeedbackSound
        enableVoiceFeedback = prefs.enableFeedbackVoice
        enableVibrationFeedback = prefs.enableFeedbackVibration
        if (!enableVoiceFeedback) {
            shutdownTts()
            pendingTtsText = null
        }
        if (!enableVibrationFeedback) {
            stopVibration()
        }

        belowEnergySince = 0L
        autoZeroSince = 0L
        autoZeroStableStart = 0L
        autoZeroStableMin = 0f
        autoZeroStableMax = 0f
        autoZeroLatched = false
        strongSuppressionStart = 0L
        strongSuppressionLatched = false
        strongSuppressionMin = 0f
        strongSuppressionMax = 0f
        strongLockUntil = 0L
        magWindow.clear()
        noiseMean = 0f
        noiseM2 = 0f
        noiseSamples = 0
        state = State.IDLE
        lastRouteLog = null
        lastAudioRouteAllowed = false
        if (magnetometer != null) {
            applySamplingDelay(samplingHighDelayUs)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_VOICE -> {
                val routeOk = isAudioRouteAllowed(System.currentTimeMillis())
                triggerVoiceAssistant(
                    alreadyPlayedSound = false,
                    feedbackAllowed = routeOk && enableVoiceFeedback
                )
                return START_STICKY
            }
            ACTION_ZERO_SENSOR -> {
                resetBaseline()
                return START_STICKY
            }
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("磁控助手后台运行")
            .setContentText("保持后台以便磁控响应")
            .setSmallIcon(R.drawable.ic_magnet)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "磁控助手前台",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun initSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD).also {
                logToUI("未找到未校准磁力计，退回已校准传感器")
            }
        magnetometer?.let {
            sensorManager.registerListener(this, it, samplingHighDelayUs, samplingHighDelayUs)
            currentDelayUs = samplingHighDelayUs
        } ?: run {
            logToUI("无法注册磁力计监听")
            stopSelf()
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
        releaseSoundEffects()
        shutdownTts()
        syncRecentLogsToUi()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_MAGNETIC_FIELD && event?.sensor?.type != Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) return

        val now = System.currentTimeMillis()
        lastRawX = event.values[0]
        lastRawY = event.values[1]
        lastRawZ = event.values[2]

        if (pendingInitialZero) {
            pendingInitialZero = false
            resetBaseline("启动自动归零")
        }

        val x = lastRawX - zeroOffsetX
        val y = lastRawY - zeroOffsetY
        val z = lastRawZ - zeroOffsetZ
        val magSq = x * x + y * y + z * z
        val magnitude = sqrt(magSq.toDouble()).toFloat()

        updateSamplingRate(magSq, now)
        handleAutoZero(magnitude, now)

        val poleForUi = if (usePolarity) classifyPole(x, z) else "all"
        sendBroadcastToUI(x, y, z, magnitude, poleForUi)

        processLogic(magnitude, now)
    }

    private fun resetBaseline(logMessage: String = "已重置零点") {
        zeroOffsetX = lastRawX
        zeroOffsetY = lastRawY
        zeroOffsetZ = lastRawZ
        state = State.IDLE
        triggerStartTime = 0L
        isLongPressTriggered = false
        activePole = "none"
        lastActionTime = 0L
        belowEnergySince = 0L
        autoZeroSince = 0L
        autoZeroStableStart = 0L
        autoZeroStableMin = 0f
        autoZeroStableMax = 0f
        autoZeroLatched = false
        strongSuppressionStart = 0L
        strongSuppressionLatched = false
        strongSuppressionMin = 0f
        strongSuppressionMax = 0f
        strongLockUntil = 0L
        stopVibration()

        logToUI("$logMessage (X=${zeroOffsetX.roundToInt()}, Y=${zeroOffsetY.roundToInt()}, Z=${zeroOffsetZ.roundToInt()})")
    }

    private fun handleAutoZero(magnitude: Float, now: Long) {
        updateNoiseStats(magnitude)
        if (autoZeroDurationMs <= 0L && autoZeroStabilityDurationMs <= 0L) return

        val rearmCeiling = autoZeroThreshold + autoZeroStabilityBand
        if (autoZeroLatched) {
            if (magnitude > rearmCeiling) {
                autoZeroLatched = false
            } else {
                return
            }
        }

        var zeroReason: String? = null

        if (autoZeroDurationMs > 0L) {
            if (magnitude < autoZeroThreshold) {
                if (autoZeroSince == 0L) autoZeroSince = now
                if (now - autoZeroSince >= autoZeroDurationMs) {
                    zeroReason = "场强低于 ${autoZeroThreshold.roundToInt()} μT 持续 ${"%.1f".format(autoZeroDurationMs / 1000f)} 秒"
                }
            } else {
                autoZeroSince = 0L
            }
        }

        if (autoZeroStabilityDurationMs > 0L) {
            if (autoZeroStableStart == 0L) {
                autoZeroStableStart = now
                autoZeroStableMin = magnitude
                autoZeroStableMax = magnitude
            } else {
                autoZeroStableMin = minOf(autoZeroStableMin, magnitude)
                autoZeroStableMax = maxOf(autoZeroStableMax, magnitude)
            }

            val withinBand = autoZeroStableMax - autoZeroStableMin <= autoZeroStabilityBand
            if (withinBand) {
                if (zeroReason == null && now - autoZeroStableStart >= autoZeroStabilityDurationMs) {
                    zeroReason = "场强波动 ≤ ${autoZeroStabilityBand.roundToInt()} μT 持续 ${"%.1f".format(autoZeroStabilityDurationMs / 1000f)} 秒"
                }
            } else {
                autoZeroStableStart = now
                autoZeroStableMin = magnitude
                autoZeroStableMax = magnitude
            }
        } else {
            autoZeroStableStart = 0L
            autoZeroStableMin = magnitude
            autoZeroStableMax = magnitude
        }

        if (zeroReason != null) {
            resetBaseline("因 $zeroReason，自动归零")
            autoZeroLatched = true
            autoZeroSince = 0L
            autoZeroStableStart = 0L
        }
    }

    private fun handleStrongSuppression(magnitude: Float, now: Long, soundAllowed: Boolean): Boolean {
        if (strongSuppressionThreshold <= 0f || strongSuppressionDurationMs <= 0L) return false

        if (magnitude > strongSuppressionThreshold) {
            if (strongSuppressionStart == 0L) {
                strongSuppressionStart = now
                strongSuppressionMin = magnitude
                strongSuppressionMax = magnitude
            } else {
                strongSuppressionMin = minOf(strongSuppressionMin, magnitude)
                strongSuppressionMax = maxOf(strongSuppressionMax, magnitude)
            }

            val jitter = strongSuppressionMax - strongSuppressionMin
            if (jitter <= strongSuppressionJitter && now - strongSuppressionStart >= strongSuppressionDurationMs) {
                if (!strongSuppressionLatched) {
                    strongSuppressionLatched = true
                    cancelActiveTrigger()
                    lastActionTime = now
                    logToUI("磁场过强且稳定，已忽略本次触发")
                    if (soundAllowed) {
                        playPressFailureChime()
                    }
                }
                return true
            }
        } else {
            if (magnitude < strongSuppressionThreshold - strongSuppressionJitter) {
                strongSuppressionLatched = false
            }
            strongSuppressionStart = 0L
            strongSuppressionMin = magnitude
            strongSuppressionMax = magnitude
        }

        return false
    }

    private fun processLogic(magnitude: Float, now: Long) {
        val routeAllowed = isAudioRouteAllowed(now)
        val promptSoundAllowed = routeAllowed && enableSoundFeedback
        val actionSoundAllowed = routeAllowed && enableVoiceFeedback
        val vibrationAllowed = routeAllowed && enableVibrationFeedback
        val ttsAllowed = routeAllowed && enableVoiceFeedback
        val filteredMag = filterMagnitude(magnitude)

        if (!routeAllowed || !vibrationAllowed) {
            stopVibration()
        }

        if (handleStrongSuppression(filteredMag, now, promptSoundAllowed)) return

        val adaptiveTrigger = max(thresholdTrigger, noiseMean + noiseMultiplier * getNoiseStd())
        val adaptiveReset = max(thresholdReset, adaptiveTrigger * 0.6f)

        when (state) {
            State.IDLE -> {
                if (filteredMag > adaptiveTrigger && now - lastActionTime >= actionCooldownMs) {
                    state = State.TIMING
                    triggerStartTime = now
                    isLongPressTriggered = false
                    if (vibrationAllowed) {
                        startContinuousVibration()
                    }
                    if (promptSoundAllowed) {
                        playPressSound()
                    }
                    activePole = if (usePolarity) samplePoleDuringWindow(now) else "all"
                }
            }
            State.TIMING -> {
                if (filteredMag < adaptiveReset) {
                    stopVibration()
                    if (!isLongPressTriggered) {
                        val poleForAction = if (usePolarity) activePole else "all"
                        if (!usePolarity || poleForAction == "N" || poleForAction == "S" || poleForAction == "all") {
                            val action = selectActionForPole(poleForAction, false)
                            performAction(action, routeAllowed, ttsAllowed, actionSoundAllowed)
                        }
                    }
                    state = State.COOLDOWN
                    lastActionTime = now
                } else if (!isLongPressTriggered && now - triggerStartTime >= longPressThresholdMs) {
                    stopVibration()
                    if (vibrationAllowed) {
                        playDoubleBeep()
                    }
                    val poleForAction = if (usePolarity) activePole else "all"
                    if (!usePolarity || poleForAction == "N" || poleForAction == "S" || poleForAction == "all") {
                        val action = selectActionForPole(poleForAction, true)
                        performAction(action, routeAllowed, ttsAllowed, actionSoundAllowed)
                        isLongPressTriggered = true
                        state = State.COOLDOWN
                        lastActionTime = now
                    }
                }
            }
            State.COOLDOWN -> {
                if (now - lastActionTime >= actionCooldownMs) {
                    state = State.IDLE
                }
            }
        }
    }

    private fun samplePoleDuringWindow(now: Long): String {
        val sampleEnd = now + polarityWindowMs
        val samples = mutableListOf<Pair<Float, Float>>()
        samples.add(lastRawX - zeroOffsetX to lastRawZ - zeroOffsetZ)
        while (System.currentTimeMillis() < sampleEnd) {
            samples.add(lastRawX - zeroOffsetX to lastRawZ - zeroOffsetZ)
            Thread.sleep(5)
        }
        val avgX = samples.map { it.first }.average().toFloat()
        val avgZ = samples.map { it.second }.average().toFloat()
        return classifyPole(avgX, avgZ)
    }

    private fun classifyPole(x: Float, z: Float): String {
        return if (x >= z) "N" else "S"
    }

    private fun filterMagnitude(rawMag: Float): Float {
        magWindow.addLast(rawMag)
        if (magWindow.size > filterWindowSize) {
            magWindow.removeFirst()
        }
        return magWindow.average().toFloat()
    }

    private fun updateNoiseStats(mag: Float) {
        if (state != State.IDLE) return
        if (mag > thresholdReset) return
        noiseSamples += 1
        val delta = mag - noiseMean
        noiseMean += delta / noiseSamples
        noiseM2 += delta * (mag - noiseMean)
        if (noiseSamples > 20000) {
            noiseSamples /= 2
            noiseM2 /= 2
        }
    }

    private fun getNoiseStd(): Float {
        return if (noiseSamples > 1) sqrt(noiseM2 / (noiseSamples - 1)) else 0f
    }

    private fun cancelActiveTrigger() {
        state = State.IDLE
        triggerStartTime = 0L
        isLongPressTriggered = false
        activePole = "none"
        stopVibration()
    }

    private fun performAction(action: String, allowed: Boolean, ttsAllowed: Boolean = enableVoiceFeedback, soundAllowed: Boolean = enableVoiceFeedback) {
        val label = when (action) {
            "voice" -> "语音助手"
            "next" -> "下一曲"
            "previous" -> "上一曲"
            "volume_up" -> "音量+"
            "volume_down" -> "音量-"
            "play_pause", "media" -> "播放/暂停"
            else -> "播放/暂停"
        }

        if (!allowed) {
            logAction(label, false)
            return
        }

        logAction(label, true)
        if (ttsAllowed) {
            speakActionLabel(label)
        }
        if (soundAllowed) {
            playActionSound(action)
        }
        when (action) {
            "voice" -> triggerVoiceAssistant(alreadyPlayedSound = soundAllowed, feedbackAllowed = allowed && soundAllowed)
            "next" -> triggerMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> triggerMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
            "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
            "play_pause", "media" -> triggerMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            else -> triggerMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    private fun logAction(label: String, executed: Boolean) {
        val suffix = if (executed) "[已执行]" else "[未执行]"
        logToUI("动作 $label $suffix")
    }

    private fun selectActionForPole(pole: String, longPress: Boolean): String {
        val normalized = if (usePolarity && (pole == "N" || pole == "S")) pole else "all"
        return if (longPress) {
            when (normalized) {
                "N" -> prefs.nLongAction
                "S" -> prefs.sLongAction
                else -> prefs.allLongAction
            }
        } else {
            when (normalized) {
                "N" -> prefs.nShortAction
                "S" -> prefs.sShortAction
                else -> prefs.allShortAction
            }
        }
    }

    private fun startContinuousVibration() {
        if (!enableVibrationFeedback || !lastAudioRouteAllowed) return
        val vibrator = getVibrator()
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(5000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(5000)
        }
    }

    private fun stopVibration() {
        getVibrator().cancel()
    }

    private fun playDoubleBeep() {
        if (!enableVibrationFeedback || !lastAudioRouteAllowed) return
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

    private fun ensureSoundEffects(): SoundPool? {
        soundPool?.let { return it }
        val pool = SoundPool.Builder().setMaxStreams(1).build()
        soundPool = pool
        soundIds.clear()
        loadSound(pool, "toggle")
        loadSound(pool, "prev")
        loadSound(pool, "next")
        loadSound(pool, "volup")
        loadSound(pool, "voldown")
        loadSound(pool, "assistant")
        loadSound(pool, "press")
        return pool
    }

    private fun loadSound(pool: SoundPool, name: String) {
        try {
            assets.openFd("sound/$name.wav").use { afd ->
                val id = pool.load(afd, 1)
                soundIds[name] = id
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载音效失败 $name: ${e.message}")
        }
    }

    private fun playActionSound(action: String) {
        if (!enableVoiceFeedback) return
        val key = when (action) {
            "play_pause", "media" -> "toggle"
            "previous" -> "prev"
            "next" -> "next"
            "volume_up" -> "volup"
            "volume_down" -> "voldown"
            "voice" -> "assistant"
            else -> null
        }
        playSound(key)
    }

    private fun playPressSound() {
        if (!enableSoundFeedback) return
        playSound("press")
    }

    private fun playPressFailureChime() {
        if (!enableSoundFeedback) return
        val intervals = listOf(0L, 150L, 300L)
        intervals.forEach { delay ->
            soundReleaseHandler.postDelayed({ playPressSound() }, delay)
        }
    }

    private fun playSound(key: String?) {
        if (!lastAudioRouteAllowed) return
        val pool = ensureSoundEffects() ?: return
        val soundId = key?.let { soundIds[it] } ?: return
        pool.play(soundId, 1f, 1f, 1, 0, 1f)
        scheduleSoundRelease()
    }

    private fun releaseSoundEffects() {
        soundReleaseHandler.removeCallbacks(soundReleaseRunnable)
        soundPool?.release()
        soundPool = null
        soundIds.clear()
    }

    private fun scheduleSoundRelease() {
        soundReleaseHandler.removeCallbacks(soundReleaseRunnable)
        soundReleaseHandler.postDelayed(soundReleaseRunnable, soundReleaseDelayMs)
    }

    private fun speakActionLabel(label: String) {
        val tts = ensureTts() ?: run {
            pendingTtsText = label
            return
        }
        if (!isTtsReady) {
            pendingTtsText = label
            return
        }
        pendingTtsText = null
        tts.speak(label, TextToSpeech.QUEUE_FLUSH, null, "action_$label")
    }

    private fun ensureTts(): TextToSpeech? {
        if (textToSpeech != null && isTtsReady) return textToSpeech
        if (textToSpeech == null) {
            textToSpeech = TextToSpeech(this) { status ->
                isTtsReady = status == TextToSpeech.SUCCESS
                if (isTtsReady) {
                    textToSpeech?.language = Locale.getDefault()
                    pendingTtsText?.let {
                        speakActionLabel(it)
                    }
                    pendingTtsText = null
                } else {
                    pendingTtsText = null
                }
            }
        }
        return textToSpeech
    }

    private fun shutdownTts() {
        isTtsReady = false
        pendingTtsText = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    private fun triggerVoiceAssistant(alreadyPlayedSound: Boolean = false, feedbackAllowed: Boolean = true) {
        if (!alreadyPlayedSound && feedbackAllowed) {
            playSound("assistant")
        }
        if (AccessibilityVoiceService.requestVoice(this)) {
            logToUI("尝试通过无障碍触发语音助手...")
            return
        }
        acquireWakeLock(4000)
        try {
            logToUI("直接请求无障碍触发失败，申请唤醒锁后重试")
            AccessibilityVoiceService.requestVoice(this)
        } finally {
            releaseWakeLock()
        }
    }

    private fun triggerMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = System.currentTimeMillis()
        val keyEventDown = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val keyEventUp = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        try {
            audioManager.dispatchMediaKeyEvent(keyEventDown)
            audioManager.dispatchMediaKeyEvent(keyEventUp)
        } catch (e: Exception) {
            logToUI("发送媒体键失败: ${e.message}")
        }
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            logToUI("调整音量失败: ${e.message}")
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
            logToUI("申请 WAKE_LOCK 权限失败")
        } catch (e: Exception) {
            logToUI("申请唤醒锁失败: ${e.message}")
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

    private fun isAudioRouteAllowed(now: Long): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val btTypes = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST
        )

        val candidates = mutableListOf<Pair<String, String>>()
        outputs.filter { btTypes.contains(it.type) }.forEach { dev ->
            val addr = try {
                dev.address.orEmpty()
            } catch (_: SecurityException) {
                ""
            }
            val name = dev.productName?.toString().orEmpty()
            candidates.add(addr to name)
        }

        val allowedAddrs = allowedBtDevices.filter { it.contains(":") }.map { it.uppercase() }.toSet()
        val allowedNames = allowedBtDevices.filter { it.startsWith("name::") }.map { it.removePrefix("name::").lowercase() }.toSet()

        var reason: String? = null
        var routeMessage: String? = null
        if (candidates.isEmpty()) {
            reason = "当前未连接蓝牙音频设备/通道"
            routeMessage = "⚠️ 当前未连接蓝牙音频设备，动作不执行"
        } else if (allowedBtDevices.isNotEmpty()) {
            val matched = candidates.any { (addrRaw, nameRaw) ->
                val addr = addrRaw.uppercase()
                val name = nameRaw.lowercase()
                val addrOk = addr.isNotBlank() && allowedAddrs.contains(addr)
                val nameOk = name.isNotBlank() && allowedNames.any { allow -> name.contains(allow) || allow.contains(name) }
                addrOk || nameOk
            }
            if (!matched) {
                val namesNotAllowed = candidates.map { it.second.ifBlank { it.first } }.filter { it.isNotBlank() }.distinct()
                routeMessage = "⚠️ 当前连接设备 ${namesNotAllowed.joinToString(" / ").ifBlank { "未知设备" }} 不在白名单，动作不执行"
                reason = "已连接设备不在白名单"
            } else {
                val namesAllowed = candidates.map { it.second.ifBlank { it.first } }.filter { it.isNotBlank() }.distinct()
                routeMessage = if (namesAllowed.isNotEmpty()) {
                    "✅ 已连接允许的设备: ${namesAllowed.joinToString(" / ")}"
                } else {
                    "✅ 已连接白名单设备"
                }
            }
        } else {
            val namesAny = candidates.map { it.second.ifBlank { it.first } }.filter { it.isNotBlank() }.distinct()
            routeMessage = if (namesAny.isNotEmpty()) {
                "ℹ️ 已连接蓝牙音频：${namesAny.joinToString(" / ")}"
            } else {
                "ℹ️ 已连接蓝牙音频设备"
            }
        }

        if (routeMessage != lastRouteLog) {
            lastRouteLog = routeMessage
            routeMessage?.let { logToUI(it) }
        }

        lastAudioRouteAllowed = reason == null
        return lastAudioRouteAllowed
    }

    private fun sendBroadcastToUI(x: Float, y: Float, z: Float, mag: Float, pole: String) {
        val status = getStatusText()
        if (!isScreenOn) return
        val intent = Intent("com.example.magnetcontroller.UPDATE_UI")
        intent.putExtra("x", x)
        intent.putExtra("y", y)
        intent.putExtra("z", z)
        intent.putExtra("mag", mag)
        intent.putExtra("pole", pole)
        intent.putExtra("status", status)
        val sampleHz = if (currentDelayUs > 0) 1_000_000f / currentDelayUs else 0f
        intent.putExtra("sample_hz", sampleHz)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun logToUI(message: String) {
        val intent = Intent("com.example.magnetcontroller.UPDATE_LOG")
        intent.putExtra("log", message)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        Log.d(TAG, message)
        addRecentLog(message)
    }

    private fun getStatusText(): String {
        return when (state) {
            State.IDLE -> "待机中..."
            State.TIMING -> if (isLongPressTriggered) "长按触发中" else "按压计时中..."
            State.COOLDOWN -> "冷却中..."
        }
    }

    private fun addRecentLog(message: String) {
        if (message.isBlank()) return
        if (message.startsWith("⚠️") || message.startsWith("✅") || message.startsWith("ℹ️")) return
        if (recentLogs.size >= maxRecentLogs) {
            recentLogs.removeFirst()
        }
        recentLogs.addLast(message)
        syncRecentLogsToUi()
    }

    private fun syncRecentLogsToUi() {
        val intent = Intent("com.example.magnetcontroller.UPDATE_RECENT_LOGS")
        intent.putStringArrayListExtra("logs", ArrayList(recentLogs))
        intent.setPackage(packageName)
        sendBroadcast(intent)
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
