
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MagnetService : Service(), SensorEventListener {
    companion object {
        const val ACTION_TRIGGER_VOICE = "com.example.magnetcontroller.TRIGGER_VOICE"
        const val ACTION_ZERO_SENSOR = "com.example.magnetcontroller.ZERO_SENSOR"

        private const val CHANNEL_ID = "MagnetServiceChannel"
        private const val TAG = "MagnetService"
        private const val MAX_RECENT_LOGS = 100
        private const val ACTION_COOLDOWN_MS = 900L
        private const val NOISE_SAMPLE_LIMIT = 20_000
        private const val FILTER_WINDOW_SIZE = 5
        private const val NOISE_MULTIPLIER = 3.0f
        private const val SOUND_RELEASE_DELAY_MS = 60_000L
        private val LONG_PRESS_PATTERN = longArrayOf(0, 200, 100, 200)
    }

    private enum class LogCategory { ROUTE, ACTION, STATUS, ERROR }

    private data class PolaritySample(val timestamp: Long, val x: Float, val z: Float)

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
    private var isScreenOn = true

    private val actionCooldownMs = ACTION_COOLDOWN_MS
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
    private var thresholdResetDebounceMs = 80L

    private var usePolarity = false
    private var polarityWindowMs = 80
    private var polarityMin = 50f
    private var polarityMax = 2000f
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
    private var allowAllOutputs: Boolean = false
    private var lastRouteLog: String? = null
    private var lastAudioRouteAllowed = false
    private var enableSoundFeedback = true
    private var enableVoiceFeedback = false
    private var enableVibrationFeedback = true

    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<String, Int>()
    private val soundReleaseHandler = Handler(Looper.getMainLooper())
    private val soundReleaseRunnable = Runnable { releaseSoundEffects() }

    private val magWindow = ArrayDeque<Float>()
    private var noiseMean = 0f
    private var noiseM2 = 0f
    private var noiseSamples = 0

    private val recentLogs = ArrayDeque<String>()
    private val polaritySamples = ArrayDeque<PolaritySample>()
    private var resetBelowSince = 0L

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.magnetcontroller.RELOAD_SETTINGS") {
                loadSettings()
                logToUI(getString(R.string.log_settings_reloaded), LogCategory.STATUS)
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
        thresholdResetDebounceMs = prefs.thresholdResetDebounceMs
        polarityMin = prefs.polarityMin
        polarityMax = prefs.polarityMax
        strongLockHoldMs = prefs.strongLockHoldMs
        allowedBtDevices = prefs.allowedBtDevices
        allowAllOutputs = prefs.allowAllOutputs
        enableSoundFeedback = prefs.enableFeedbackSound
        enableVoiceFeedback = prefs.enableFeedbackVoice
        enableVibrationFeedback = prefs.enableFeedbackVibration
        if (!enableVibrationFeedback) {
            stopVibration()
        }

        recentLogs.clear()
        recentLogs.addAll(prefs.recentLogs.takeLast(MAX_RECENT_LOGS))
        polaritySamples.clear()
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
        resetBelowSince = 0L
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
        syncRecentLogsToUi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_VOICE -> {
                val routeOk = isAudioRouteAllowed()
                playActionFeedback("voice", routeOk && enableVoiceFeedback)
                triggerVoiceAssistant()
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
            .setContentTitle(getString(R.string.notification_running))
            .setContentText(getString(R.string.notification_listening))
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
                getString(R.string.notification_channel_name),
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
                logToUI(getString(R.string.log_fallback_calibrated_sensor), LogCategory.STATUS)
            }
        magnetometer?.let {
            sensorManager.registerListener(this, it, samplingHighDelayUs, samplingHighDelayUs)
            currentDelayUs = samplingHighDelayUs
        } ?: run {
            logToUI(getString(R.string.log_register_sensor_failed), LogCategory.ERROR)
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
            resetBaseline()
        }

        val x = lastRawX - zeroOffsetX
        val y = lastRawY - zeroOffsetY
        val z = lastRawZ - zeroOffsetZ
        val magSq = x * x + y * y + z * z
        val magnitude = sqrt(magSq.toDouble()).toFloat()

        updateSamplingRate(magSq, now)
        handleAutoZero(magnitude, now)

        updatePolaritySamples(now, x, z, magnitude)
        val poleForUi = determineActivePole(now)
        sendBroadcastToUI(x, y, z, magnitude, poleForUi)

        processLogic(magnitude, now)
    }

    private fun resetBaseline(reason: String? = null) {
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
        polaritySamples.clear()
        resetBelowSince = 0L
        stopVibration()

        val baseMessage = getString(
            R.string.log_reset_baseline,
            zeroOffsetX.roundToInt(),
            zeroOffsetY.roundToInt(),
            zeroOffsetZ.roundToInt()
        )
        val finalMessage = if (reason.isNullOrBlank()) baseMessage else "$reason $baseMessage"
        logToUI(finalMessage, LogCategory.STATUS)
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
                    zeroReason = getString(
                        R.string.log_auto_zero_low,
                        autoZeroThreshold.roundToInt(),
                        autoZeroDurationMs / 1000f
                    )
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
                    zeroReason = getString(
                        R.string.log_auto_zero_stable,
                        autoZeroStabilityBand.roundToInt(),
                        autoZeroStabilityDurationMs / 1000f
                    )
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
            resetBaseline(zeroReason)
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
                    strongLockUntil = now + strongLockHoldMs
                    logToUI(getString(R.string.log_strong_suppression), LogCategory.STATUS)
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
        val routeAllowed = isAudioRouteAllowed()
        val promptSoundAllowed = routeAllowed && enableSoundFeedback
        val assistantFeedbackAllowed = routeAllowed && enableVoiceFeedback
        val vibrationAllowed = routeAllowed && enableVibrationFeedback
        val filteredMag = filterMagnitude(magnitude)

        if (!routeAllowed || !vibrationAllowed) {
            stopVibration()
        }

        if (now < strongLockUntil) {
            return
        }

        if (handleStrongSuppression(filteredMag, now, promptSoundAllowed)) {
            strongLockUntil = now + strongLockHoldMs
            return
        }

        val (adaptiveTrigger, adaptiveReset) = computeAdaptiveThresholds()

        when (state) {
            State.IDLE -> tryStartTrigger(filteredMag, adaptiveTrigger, now, vibrationAllowed, promptSoundAllowed)
            State.TIMING -> handleTimingState(
                filteredMag,
                adaptiveReset,
                now,
                routeAllowed,
                promptSoundAllowed,
                assistantFeedbackAllowed,
                vibrationAllowed
            )
            State.COOLDOWN -> {
                if (now - lastActionTime >= actionCooldownMs) {
                    state = State.IDLE
                    resetBelowSince = 0L
                }
            }
        }
    }

    private fun computeAdaptiveThresholds(): Pair<Float, Float> {
        val trigger = max(thresholdTrigger, noiseMean + NOISE_MULTIPLIER * getNoiseStd())
        val reset = max(thresholdReset, trigger * 0.6f)
        return trigger to reset
    }

    private fun tryStartTrigger(
        filteredMag: Float,
        adaptiveTrigger: Float,
        now: Long,
        vibrationAllowed: Boolean,
        promptSoundAllowed: Boolean
    ) {
        if (filteredMag > adaptiveTrigger && now - lastActionTime >= actionCooldownMs) {
            state = State.TIMING
            triggerStartTime = now
            isLongPressTriggered = false
            resetBelowSince = 0L
            if (vibrationAllowed) {
                startContinuousVibration()
            }
            if (promptSoundAllowed) {
                playPressSound()
            }
            activePole = determineActivePole(now)
        }
    }

    private fun handleTimingState(
        filteredMag: Float,
        adaptiveReset: Float,
        now: Long,
        routeAllowed: Boolean,
        promptSoundAllowed: Boolean,
        assistantFeedbackAllowed: Boolean,
        vibrationAllowed: Boolean
    ) {
        if (filteredMag < adaptiveReset) {
            if (resetBelowSince == 0L) resetBelowSince = now
            val debounceReached = now - resetBelowSince >= thresholdResetDebounceMs
            if (debounceReached) {
                stopVibration()
                if (!isLongPressTriggered) {
                    triggerConfiguredAction(
                        longPress = false,
                        routeAllowed = routeAllowed,
                        assistantAllowed = assistantFeedbackAllowed
                    )
                }
                state = State.COOLDOWN
                lastActionTime = now
                resetBelowSince = 0L
            }
        } else {
            resetBelowSince = 0L
            if (!isLongPressTriggered && now - triggerStartTime >= longPressThresholdMs) {
                stopVibration()
                if (vibrationAllowed) {
                    playDoubleBeep()
                }
                triggerConfiguredAction(
                    longPress = true,
                    routeAllowed = routeAllowed,
                    assistantAllowed = assistantFeedbackAllowed
                )
                isLongPressTriggered = true
                state = State.COOLDOWN
                lastActionTime = now
            }
        }
    }

    private fun triggerConfiguredAction(
        longPress: Boolean,
        routeAllowed: Boolean,
        assistantAllowed: Boolean
    ) {
        val poleForAction = if (usePolarity) activePole else "all"
        if (!usePolarity || poleForAction == "N" || poleForAction == "S" || poleForAction == "all") {
            val action = selectActionForPole(poleForAction, longPress)
            performAction(action, routeAllowed, assistantAllowed)
        }
    }

    private fun determineActivePole(now: Long): String {
        if (!usePolarity) return "all"
        val cutoff = now - polarityWindowMs
        while (polaritySamples.isNotEmpty() && polaritySamples.first().timestamp < cutoff) {
            polaritySamples.removeFirst()
        }
        if (polaritySamples.isEmpty()) return "all"
        val avgX = polaritySamples.map { it.x }.average().toFloat()
        val avgZ = polaritySamples.map { it.z }.average().toFloat()
        return classifyPole(avgX, avgZ)
    }

    private fun updatePolaritySamples(now: Long, x: Float, z: Float, magnitude: Float) {
        if (!usePolarity) {
            polaritySamples.clear()
            return
        }
        if (magnitude < polarityMin || magnitude > polarityMax) return
        polaritySamples.addLast(PolaritySample(now, x, z))
        val cutoff = now - polarityWindowMs
        while (polaritySamples.isNotEmpty() && polaritySamples.first().timestamp < cutoff) {
            polaritySamples.removeFirst()
        }
    }

    private fun classifyPole(x: Float, z: Float): String {
        return if (x >= z) "N" else "S"
    }

    private fun filterMagnitude(rawMag: Float): Float {
        magWindow.addLast(rawMag)
        if (magWindow.size > FILTER_WINDOW_SIZE) {
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
        if (noiseSamples > NOISE_SAMPLE_LIMIT) {
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

    private fun performAction(action: String, routeAllowed: Boolean, assistantAllowed: Boolean) {
        val label = actionLabel(action)

        if (!routeAllowed) {
            logAction(label, false)
            return
        }

        logAction(label, true)
        if (assistantAllowed) {
            playActionFeedback(action, true)
        }
        when (action) {
            "voice" -> triggerVoiceAssistant()
            "next" -> triggerMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> triggerMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
            "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
            "play_pause", "media" -> triggerMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            else -> triggerMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    private fun actionLabel(action: String): String {
        return when (action) {
            "voice" -> getString(R.string.label_voice)
            "next" -> getString(R.string.label_next)
            "previous" -> getString(R.string.label_previous)
            "volume_up" -> getString(R.string.label_volume_up)
            "volume_down" -> getString(R.string.label_volume_down)
            "play_pause", "media" -> getString(R.string.label_play_pause)
            else -> getString(R.string.label_play_pause)
        }
    }

    private fun logAction(label: String, executed: Boolean) {
        val message = if (executed) {
            getString(R.string.log_action_executed, label)
        } else {
            getString(R.string.log_action_skipped, label)
        }
        logToUI(message, LogCategory.ACTION)
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
                vibrator.vibrate(VibrationEffect.createWaveform(LONG_PRESS_PATTERN, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(LONG_PRESS_PATTERN, -1)
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
            Log.w(TAG, getString(R.string.log_sound_load_failed, name, e.message.orEmpty()))
        }
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
        soundReleaseHandler.postDelayed(soundReleaseRunnable, SOUND_RELEASE_DELAY_MS)
    }

    private fun playActionFeedback(action: String, feedbackAllowed: Boolean) {
        if (!feedbackAllowed) return
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

    private fun triggerVoiceAssistant() {
        val baseFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val attempts = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND).apply { addFlags(baseFlags) },
            Intent("android.intent.action.VOICE_ASSIST").apply { addFlags(baseFlags) },
            Intent(Intent.ACTION_ASSIST).apply { addFlags(baseFlags) }
        )
        var launched = false
        for (intent in attempts) {
            try {
                startActivity(intent)
                launched = true
                break
            } catch (_: Exception) {
            }
        }
        if (!launched) {
            logToUI(getString(R.string.log_voice_launch_error), LogCategory.ERROR)
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
            logToUI(getString(R.string.log_media_key_error, e.message.orEmpty()), LogCategory.ERROR)
        }
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            logToUI(getString(R.string.log_volume_error, e.message.orEmpty()), LogCategory.ERROR)
        }
    }

    private fun isAudioRouteAllowed(): Boolean {
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

        val hasBtOutput = candidates.isNotEmpty()

        var routeMessage: String? = null
        val allowed = when {
            allowAllOutputs -> {
                routeMessage = getString(R.string.log_route_allow_all)
                true
            }
            !hasBtOutput -> {
                routeMessage = getString(R.string.log_route_not_connected)
                false
            }
            allowedBtDevices.isEmpty() -> {
                val namesAny = candidates.map { it.second.ifBlank { it.first } }.filter { it.isNotBlank() }.distinct()
                routeMessage = if (namesAny.isNotEmpty()) {
                    getString(R.string.log_route_connected_generic, namesAny.joinToString(" / "))
                } else {
                    getString(R.string.log_route_connected_generic, getString(R.string.log_route_unknown_device))
                }
                true
            }
            else -> {
                val matched = candidates.any { (addrRaw, nameRaw) ->
                    val addr = addrRaw.uppercase()
                    val name = nameRaw.lowercase()
                    val addrOk = addr.isNotBlank() && allowedAddrs.contains(addr)
                    val nameOk = name.isNotBlank() && allowedNames.any { allow -> name.contains(allow) || allow.contains(name) }
                    addrOk || nameOk
                }
                if (matched) {
                    val namesAllowed = candidates.map { it.second.ifBlank { it.first } }.filter { it.isNotBlank() }.distinct()
                    val display = if (namesAllowed.isNotEmpty()) namesAllowed.joinToString(" / ") else getString(R.string.log_route_unknown_device)
                    routeMessage = getString(R.string.log_route_connected_allowed, display)
                    true
                } else {
                    val namesNotAllowed = candidates.map { it.second.ifBlank { it.first } }.filter { it.isNotBlank() }.distinct()
                    val display = namesNotAllowed.joinToString(" / ").ifBlank { getString(R.string.log_route_unknown_device) }
                    routeMessage = getString(R.string.log_route_blocked, display)
                    false
                }
            }
        }

        if (routeMessage != lastRouteLog) {
            lastRouteLog = routeMessage
            routeMessage?.let { logToUI(it, LogCategory.ROUTE) }
        }

        lastAudioRouteAllowed = allowed
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

    private fun logToUI(message: String, category: LogCategory = LogCategory.STATUS) {
        if (message.isBlank()) return
        val payload = formatLog(message, category)
        val intent = Intent("com.example.magnetcontroller.UPDATE_LOG").apply {
            putExtra("log", payload)
            putExtra("category", category.name)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, payload)
        addRecentLog(payload)
    }

    private fun formatLog(message: String, category: LogCategory): String {
        val prefix = when (category) {
            LogCategory.ROUTE -> "[ROUTE]"
            LogCategory.ACTION -> "[ACTION]"
            LogCategory.ERROR -> "[ERROR]"
            LogCategory.STATUS -> "[STATUS]"
        }
        return "$prefix $message"
    }

    private fun getStatusText(): String {
        return when (state) {
            State.IDLE -> getString(R.string.status_idle)
            State.TIMING -> if (isLongPressTriggered) getString(R.string.status_timing_long) else getString(R.string.status_timing)
            State.COOLDOWN -> getString(R.string.status_cooldown_only)
        }
    }

    private fun addRecentLog(message: String) {
        if (recentLogs.size >= MAX_RECENT_LOGS) {
            recentLogs.removeFirst()
        }
        recentLogs.addLast(message)
        prefs.recentLogs = recentLogs.toList()
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
