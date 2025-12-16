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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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

import android.util.Log

import android.view.KeyEvent

import androidx.core.app.NotificationCompat

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

    private var activePole: String = "none"

    private var lockedPole: String = "none"

    private var sampleWindowStart = 0L

    private var lastMagnitude = 0f

    private val sampleWindow = mutableListOf<FloatArray>()

    private var lastActionTime = 0L

    private var wakeLock: PowerManager.WakeLock? = null

    private var lastUiMag = -1f

    private var lastUiPole = "none"

    private var lastUiStatus = ""

    private var isScreenOn = true



    private val actionCooldownMs = 900L

    private var longPressThresholdMs = 1500L

    private var midWindowLower = 200f

    private var midWindowUpper = 500f

    private var strongLockThreshold = 600f

    private var energyThresholdSq = 100f * 100f

    private var energyHoldMs = 2000L

    private var samplingHighDelayUs = 20_000

    private var samplingLowDelayUs = 66_000

    private var currentDelayUs = 0

    private var belowEnergySince = 0L

    private var resetBelowSince = 0L

    private var autoZeroThreshold = 80f

    private var autoZeroDurationMs = 4000L

    private var autoZeroStabilityBand = 20f

    private var autoZeroStabilityDurationMs = 4000L

    private var autoZeroSince = 0L

    private var autoZeroStableStart = 0L

    private var autoZeroStableMin = 0f

    private var autoZeroStableMax = 0f

    private var autoZeroLatched = false

    private var startupZeroPending = true

    private var strongSuppressionThreshold = 1800f

    private var strongSuppressionDurationMs = 400L

    private var strongSuppressionJitter = 40f

    private var strongSuppressionStart = 0L

    private var strongSuppressionMin = 0f

    private var strongSuppressionMax = 0f

    private var strongSuppressionLatched = false

    private var usePolarity = true

    private var allowedBtDevices: Set<String> = emptySet()

    private var lastRouteAllowed = true

    private var lastRouteLogTime = 0L

    private var lastRouteReason = ""

    private var soundPool: SoundPool? = null

    private val soundIds = mutableMapOf<String, Int>()

    private val soundReleaseHandler = Handler(Looper.getMainLooper())

    private val soundReleaseDelayMs = 60_000L

    private val soundReleaseRunnable = Runnable { releaseSoundEffects() }

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

        midWindowLower = maxOf(prefs.polarityMin, 200f)

        midWindowUpper = minOf(prefs.polarityMax, 500f).coerceAtLeast(midWindowLower + 10f)

        strongLockThreshold = maxOf(prefs.polarityMax, 600f)

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

        usePolarity = prefs.poleMode == "different"

        allowedBtDevices = prefs.allowedBtDevices

        belowEnergySince = 0L

        resetBelowSince = 0L

        autoZeroSince = 0L

        autoZeroStableStart = 0L

        autoZeroStableMin = 0f

        autoZeroStableMax = 0f

        autoZeroLatched = false

        strongSuppressionStart = 0L

        strongSuppressionLatched = false

        strongSuppressionMin = 0f

        strongSuppressionMax = 0f

        lastRouteAllowed = true

        lastRouteLogTime = 0L

        lastRouteReason = ""

        if (!usePolarity) {

            activePole = "none"

            lockedPole = "none"

        }

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

                resetBaseline()

                return START_STICKY

            }

        }



        startupZeroPending = true



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

            .setContentTitle(getString(R.string.notification_running))
            .setContentText(getString(R.string.notification_listening))
            .setSmallIcon(android.R.drawable.ic_menu_compass)

            .setContentIntent(pendingIntent)

            .addAction(android.R.drawable.ic_btn_speak_now, getString(R.string.notification_voice), triggerPending)
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

        } ?: run {

            logToUI("❌ 无法获取磁力计传感器，已停止监听")
            stopSelf()

        }

    }



    private fun applySamplingDelay(delayUs: Int) {

        if (magnetometer == null) return

        if (currentDelayUs == delayUs && currentDelayUs != 0) return

        sensorManager.unregisterListener(this, magnetometer)

        sensorManager.registerListener(this, magnetometer, delayUs, delayUs)

        currentDelayUs = delayUs

        logToUI("⚙️ 采样频率已切换为 ${"%.1f".format(1_000_000f / delayUs)} Hz")
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

    }



    override fun onSensorChanged(event: SensorEvent?) {

        if (event?.sensor?.type != Sensor.TYPE_MAGNETIC_FIELD && event?.sensor?.type != Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) return



        val now = System.currentTimeMillis()

        lastRawX = event.values[0]

        lastRawY = event.values[1]

        lastRawZ = event.values[2]



        if (startupZeroPending) {

            resetBaseline("✅ 已手动归零")
            startupZeroPending = false

        }



        val x = lastRawX - zeroOffsetX

        val y = lastRawY - zeroOffsetY

        val z = lastRawZ - zeroOffsetZ

        val magSq = x * x + y * y + z * z

        val magnitude = sqrt(magSq.toDouble()).toFloat()



        updateSamplingRate(magSq, now)

        handleAutoZero(magnitude, now)



        val poleForUi = if (usePolarity) updatePolarity(x, y, z, magnitude, now) else "all"



        sendBroadcastToUI(x, y, z, magnitude, poleForUi)

        processLogic(x, z, magnitude, now, poleForUi)

    }



    private fun resetBaseline(logMessage: String = "✅ 已手动归零") {
        zeroOffsetX = lastRawX

        zeroOffsetY = lastRawY

        zeroOffsetZ = lastRawZ



        activePole = "none"

        lockedPole = "none"

        sampleWindowStart = 0L

        lastMagnitude = 0f

        sampleWindow.clear()

        triggerStartTime = 0L

        isLongPressTriggered = false

        resetBelowSince = 0L

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

        stopVibration()



        lastUiMag = -1f

        logToUI("$logMessage (X=${zeroOffsetX.roundToInt()}, Y=${zeroOffsetY.roundToInt()}, Z=${zeroOffsetZ.roundToInt()})")

    }



    private fun handleAutoZero(magnitude: Float, now: Long) {

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

                    zeroReason = "磁场连续低于 ${autoZeroThreshold.roundToInt()} μT 持续 ${"%.1f".format(autoZeroDurationMs / 1000f)} 秒"

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

                    zeroReason = "磁场波动维持在 ±${autoZeroStabilityBand.roundToInt()} μT 内持续 ${"%.1f".format(autoZeroStabilityDurationMs / 1000f)} 秒"

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

            resetBaseline("🧭 $zeroReason，已自动归零")

            autoZeroLatched = true

            autoZeroSince = 0L

            autoZeroStableStart = 0L

        }

    }



    private fun handleStrongSuppression(magnitude: Float, now: Long): Boolean {

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

                    logToUI("⚠️ 强磁稳定贴合，已忽略本次触发")

                    playPressFailureChime()

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



    private fun isAudioRouteAllowed(now: Long): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val btTypes = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST
        )

        val candidates = mutableListOf<Pair<String, String>>() // addr, name
        try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            outputs.filter { btTypes.contains(it.type) }.forEach { dev ->
                val addr = try {
                    dev.address.orEmpty()
                } catch (_: SecurityException) {
                    ""
                }
                val name = dev.productName?.toString().orEmpty()
                candidates.add(addr to name)
            }
        } catch (_: Exception) {
        }

        // 补充已连接蓝牙配置文件设备
        val (connAddrs, connNames) = getConnectedBluetoothIds()
        connAddrs.forEach { candidates.add(it to "") }
        connNames.forEach { candidates.add("" to it) }

        var reason: String? = null
        val allowedAddrs = allowedBtDevices.filter { it.contains(":") }.map { it.uppercase() }.toSet()
        val allowedNames = allowedBtDevices.filter { it.startsWith("name::") }.map { it.removePrefix("name::").lowercase() }.toSet()

        if (candidates.isEmpty()) {
            reason = "未检测到蓝牙输出，已忽略触发（避免外放/有线）"
            logToUI("⚠️ 未检测到蓝牙输出，已忽略触发（避免外放/有线）")
        } else if (allowedBtDevices.isNotEmpty()) {
            val matched = candidates.any { (addrRaw, nameRaw) ->
                val addr = addrRaw.uppercase()
                val name = nameRaw.lowercase()
                val addrOk = addr.isNotBlank() && allowedAddrs.contains(addr)
                val nameOk = name.isNotBlank() && allowedNames.any { allow -> name.contains(allow) || allow.contains(name) }
                addrOk || nameOk
            }
            if (!matched) {
                val names = candidates.map { it.second.ifBlank { it.first } }.filter { it.isNotBlank() }.distinct()
                logToUI("⚠️ 当前连接音频设备 ${names.joinToString(" / ").ifBlank { "未知设备" }} 未匹配白名单，已忽略触发")
                reason = "当前蓝牙设备未在白名单，已忽略触发"
            } else {
                val names = candidates.map { it.second.ifBlank { it.first } }.filter { it.isNotBlank() }.distinct()
                if (names.isNotEmpty()) {
                    logToUI("✅ 当前连接音频设备 ${names.joinToString(" / ")} 已匹配白名单")
                } else {
                    logToUI("✅ 蓝牙音频已连接且匹配白名单")
                }
            }
        } else {
            val names = candidates.map { it.second.ifBlank { it.first } }.filter { it.isNotBlank() }.distinct()
            if (names.isNotEmpty()) {
                logToUI("✅ 检测到蓝牙音频设备：${names.joinToString(" / ")}（未开启白名单限制）")
            } else {
                logToUI("✅ 检测到蓝牙音频输出（未开启白名单限制）")
            }
        }

        val allowed = reason == null
        if (!allowed && (lastRouteReason != reason || now - lastRouteLogTime > 2000)) {
            lastRouteLogTime = now
            lastRouteReason = reason ?: ""
        } else if (allowed) {
            lastRouteReason = ""
        }
        lastRouteAllowed = allowed
        return allowed
    }

    private fun getConnectedBluetoothIds(): Pair<Set<String>, Set<String>> {
        return try {
            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val profiles = listOf(
                BluetoothProfile.HEADSET,
                BluetoothProfile.A2DP,
                BluetoothProfile.HEARING_AID
            )
            val addrSet = mutableSetOf<String>()
            val nameSet = mutableSetOf<String>()
            profiles.forEach { profile ->
                try {
                    val devices = manager.getConnectedDevices(profile)
                    devices.forEach { dev ->
                        addrSet.add(dev.address.uppercase())
                        dev.name?.let { nameSet.add(it.lowercase()) }
                    }
                } catch (_: SecurityException) {
                } catch (_: Exception) {
                }
            }
            addrSet to nameSet
        } catch (_: Exception) {
            emptySet<String>() to emptySet()
        }
    }



    private fun updatePolarity(x: Float, y: Float, z: Float, magnitude: Float, now: Long): String {

        val instantPole = classifyPole(x, z)

        val inMidWindow = magnitude in midWindowLower..midWindowUpper



        if (lockedPole != "none" && magnitude >= strongLockThreshold) {

            lastMagnitude = magnitude

            return lockedPole

        }



        if (inMidWindow) {

            if (sampleWindowStart == 0L || lastMagnitude < midWindowLower) {

                sampleWindowStart = now

                sampleWindow.clear()

            }

            sampleWindow.add(floatArrayOf(x, y, z))



            val elapsed = now - sampleWindowStart

            if ((elapsed >= 30L && (elapsed >= 80L || magnitude > midWindowUpper)) || sampleWindow.size >= 10) {

                val pole = evaluateWindowPole()

                if (pole != "none") {

                    lockedPole = pole

                }

                sampleWindowStart = 0L

                sampleWindow.clear()

            }

        } else {

            if (sampleWindowStart > 0L && magnitude < midWindowLower) {

                sampleWindowStart = 0L

                sampleWindow.clear()

            }

        }



        lastMagnitude = magnitude



        return when {

            lockedPole != "none" -> lockedPole

            magnitude >= prefs.polarityMin -> instantPole

            else -> "none"

        }

    }



    private fun evaluateWindowPole(): String {

        if (sampleWindow.size < 3) return "none"



        val count = sampleWindow.size

        val avgX = sampleWindow.sumOf { it[0].toDouble() } / count

        val avgY = sampleWindow.sumOf { it[1].toDouble() } / count

        val avgZ = sampleWindow.sumOf { it[2].toDouble() } / count



        val varX = sampleWindow.sumOf {

            val diff = it[0] - avgX

            diff * diff

        } / count

        val varY = sampleWindow.sumOf {

            val diff = it[1] - avgY

            diff * diff

        } / count

        val varZ = sampleWindow.sumOf {

            val diff = it[2] - avgZ

            diff * diff

        } / count

        val maxVar = maxOf(varX, varY, varZ)



        if (maxVar > 900.0) return "none"



        return classifyPole(avgX.toFloat(), avgZ.toFloat())

    }



    private fun classifyPole(x: Float, z: Float): String = if (x >= z) "N" else "S"



    private fun sendBroadcastToUI(x: Float, y: Float, z: Float, mag: Float, pole: String) {

        val status = getStatusText()

        lastUiMag = mag

        lastUiPole = pole

        lastUiStatus = status



        if (!isScreenOn) return



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



    private fun processLogic(x: Float, zValue: Float, magnitude: Float, now: Long, poleForUi: String) {
        val routeAllowed = isAudioRouteAllowed(now)

        if (handleStrongSuppression(magnitude, now)) return



        val inCooldown = now - lastActionTime < actionCooldownMs

        if (triggerStartTime == 0L && inCooldown) return



        val poleInstant = if (usePolarity) {

            when {

                poleForUi == "N" || poleForUi == "S" -> poleForUi

                else -> classifyPole(x, zValue)

            }

        } else {

            "none"

        }



        if (magnitude > prefs.thresholdTrigger) {

            resetBelowSince = 0L

            if (triggerStartTime == 0L) {

                triggerStartTime = now

                isLongPressTriggered = false

                playPressSound()

                startContinuousVibration()

                activePole = if (usePolarity) resolvePoleForAction(poleForUi, poleInstant) else "all"

            } else {

                if (usePolarity) {

                    activePole = resolvePoleForAction(poleForUi, poleInstant)

                }



                if (!isLongPressTriggered && (now - triggerStartTime >= longPressThresholdMs)) {

                    stopVibration()

                    isContinuousVibrating = false

                    playDoubleBeep()



                    val poleForAction = if (usePolarity) resolvePoleForAction(poleForUi, poleInstant) else "all"

                    if (usePolarity && (poleForAction != "N" && poleForAction != "S")) return



                    val action = selectActionForPole(poleForAction, true)

                    performAction(action, routeAllowed)

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

                    val poleForAction = if (usePolarity) resolvePoleForAction(poleForUi, poleInstant) else "all"

                    if (usePolarity && (poleForAction != "N" && poleForAction != "S")) return



                    val action = selectActionForPole(poleForAction, false)

                    performAction(action, routeAllowed)

                    lastActionTime = now

                }



                triggerStartTime = 0L

                isLongPressTriggered = false

                activePole = "none"

                lockedPole = "none"

                sampleWindowStart = 0L

                sampleWindow.clear()

                lastMagnitude = magnitude

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



    private fun resolvePoleForAction(poleForUi: String, poleInstant: String): String {

        return when {

            lockedPole == "N" || lockedPole == "S" -> lockedPole

            poleForUi == "N" || poleForUi == "S" -> poleForUi

            poleInstant == "N" || poleInstant == "S" -> poleInstant

            activePole == "N" || activePole == "S" -> activePole

            else -> "none"

        }

    }



    private fun selectActionForPole(pole: String, longPress: Boolean): String {

        if (!usePolarity || prefs.poleMode != "different") {

            return if (longPress) prefs.allLongAction else prefs.allShortAction

        }



        val actionPole = if (pole == "N") pole else "S"

        return if (longPress) {

            if (actionPole == "N") prefs.nLongAction else prefs.sLongAction

        } else {

            if (actionPole == "N") prefs.nShortAction else prefs.sShortAction

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



    private fun cancelActiveTrigger() {

        triggerStartTime = 0L

        isLongPressTriggered = false

        stopVibration()

        isContinuousVibrating = false

        activePole = "none"

        lockedPole = "none"

        sampleWindowStart = 0L

        sampleWindow.clear()

    }



    private fun performAction(action: String, allowed: Boolean) {
        val label = when (action) {
            "voice" -> "语音助手"
            "next" -> "下一曲"
            "previous" -> "上一曲"
            "volume_up" -> "音量 +"
            "volume_down" -> "音量 -"
            "play_pause", "media" -> "播放/暂停"
            else -> "播放/暂停"
        }

        if (!allowed) {
            logAction(label, false)
            return
        }

        logAction(label, true)
        playActionSound(action)

        when (action) {
            "voice" -> triggerVoiceAssistant(alreadyPlayedSound = true)
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
        logToUI("▶️ $label $suffix")
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

        playSound("press")

    }



    private fun playPressFailureChime() {

        val intervals = listOf(0L, 150L, 300L)

        intervals.forEach { delay ->

            soundReleaseHandler.postDelayed({ playPressSound() }, delay)

        }

    }



    private fun playSound(key: String?) {

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



    private fun triggerVoiceAssistant(alreadyPlayedSound: Boolean = false) {

        if (!alreadyPlayedSound) {

            playSound("assistant")

        }



        if (AccessibilityVoiceService.requestVoice(this)) {

            logToUI("▶️ 正在通过无障碍服务唤醒助手...")

            return

        }



        acquireWakeLock(4000)

        try {

            logToUI("⚠️ 无障碍服务未启用，无法通过系统助手唤醒；已为你打开辅助功能设置")

            AccessibilityVoiceService.requestVoice(this)

        } finally {

            releaseWakeLock()

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

            logToUI("⚠️ 获取唤醒锁失败: ${e.message}")

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

            logToUI("⚠️ VOICE_COMMAND Google 调用失败: ${e.message}")

            false

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
            logToUI("⚠️ 媒体按键失败: ${e.message}")
        }
    }



    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

