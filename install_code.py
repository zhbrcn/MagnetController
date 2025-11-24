import os
import shutil

# ==========================================
# MagnetController 完整版 v11
# ==========================================
# 新增功能：
# 1. 设置界面：可调节阈值、长按时间、磁极检测
# 2. 详细日志：追踪语音助手唤起失败原因
# 3. N/S 磁极分别触发不同操作
# ==========================================

def create_file(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content.strip())
    print(f"✅ 已写入: {path}")

# 1. AndroidManifest.xml
manifest = """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    
    <uses-feature android:name="android.hardware.sensor.compass" android:required="true" />
    
    <queries>
        <package android:name="com.google.android.googlequicksearchbox" />
        <package android:name="com.miui.voiceassist" />
        <package android:name="com.xiaomi.voiceassistant" />
        <intent>
            <action android:name="android.intent.action.ASSIST" />
        </intent>
    </queries>

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.MagnetController"
        tools:targetApi="31">
        
        <activity
            android:name="com.example.magnetcontroller.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        
        <activity
            android:name="com.example.magnetcontroller.SettingsActivity"
            android:label="设置"
            android:parentActivityName="com.example.magnetcontroller.MainActivity" />
        
        <service 
            android:name="com.example.magnetcontroller.MagnetService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
            
    </application>

</manifest>
"""

# 2. activity_main.xml
layout_main = """
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="24dp"
    tools:context=".MainActivity">

    <TextView
        android:id="@+id/tvTitle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="磁力服务 Pro"
        android:textSize="28sp"
        android:textStyle="bold"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="40dp"/>
    
    <Button
        android:id="@+id/btnSettings"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="⚙️ 设置"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="20dp" />

    <TextView
        android:id="@+id/tvMagnitude"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="0.0 μT"
        android:textSize="48sp"
        android:textStyle="bold"
        android:textColor="#FF0000"
        app:layout_constraintTop_toBottomOf="@id/tvTitle"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="40dp"/>

    <TextView
        android:id="@+id/tvPoleType"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="极性: 未检测"
        android:textSize="20sp"
        android:textColor="#0000FF"
        app:layout_constraintTop_toBottomOf="@id/tvMagnitude"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp"/>

    <TextView
        android:id="@+id/tvStatus"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="服务正在后台运行..."
        android:textSize="18sp"
        app:layout_constraintTop_toBottomOf="@id/tvPoleType"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="16dp"/>

    <LinearLayout
        android:id="@+id/layoutXYZ"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:background="#EEE"
        android:padding="16dp"
        android:layout_marginTop="40dp"
        app:layout_constraintTop_toBottomOf="@id/tvStatus"
        app:layout_constraintStart_toStartOf="parent">

        <TextView 
            android:id="@+id/tvX" 
            android:layout_width="match_parent" 
            android:layout_height="wrap_content" 
            android:text="X: 0" 
            android:textSize="16sp"/>
            
        <TextView 
            android:id="@+id/tvY" 
            android:layout_width="match_parent" 
            android:layout_height="wrap_content" 
            android:text="Y: 0" 
            android:textSize="16sp"/>
            
        <TextView 
            android:id="@+id/tvZ" 
            android:layout_width="match_parent" 
            android:layout_height="wrap_content" 
            android:text="Z: 0" 
            android:textSize="16sp"/>
    </LinearLayout>
    
    <TextView
        android:id="@+id/tvLog"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="日志..."
        android:textColor="#888"
        android:textSize="12sp"
        android:maxLines="5"
        android:scrollbars="vertical"
        android:layout_marginTop="20dp"
        app:layout_constraintTop_toBottomOf="@id/layoutXYZ"
        app:layout_constraintStart_toStartOf="parent" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="后台说明：应用已升级为后台服务模式。\\n点击右上角设置按钮可自定义功能。"
        android:textColor="#666"
        android:layout_marginBottom="40dp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>

</androidx.constraintlayout.widget.ConstraintLayout>
"""

# 3. activity_settings.xml
layout_settings = """
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="⚙️ 高级设置"
            android:textSize="24sp"
            android:textStyle="bold"
            android:layout_marginBottom="24dp"/>

        <!-- 触发阈值 -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="触发阈值 (μT)"
            android:textSize="16sp"
            android:textStyle="bold"/>
        
        <TextView
            android:id="@+id/tvThresholdValue"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="当前: 500 μT"
            android:textColor="#666"/>
            
        <SeekBar
            android:id="@+id/seekThreshold"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:max="1000"
            android:progress="500"
            android:layout_marginBottom="16dp"/>

        <!-- 长按时长 -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="长按时长 (秒)"
            android:textSize="16sp"
            android:textStyle="bold"/>
        
        <TextView
            android:id="@+id/tvLongPressValue"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="当前: 1.5 秒"
            android:textColor="#666"/>
            
        <SeekBar
            android:id="@+id/seekLongPress"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:max="50"
            android:progress="15"
            android:layout_marginBottom="16dp"/>

        <!-- 磁极检测 -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="磁极检测模式"
            android:textSize="16sp"
            android:textStyle="bold"
            android:layout_marginTop="16dp"/>
        
        <RadioGroup
            android:id="@+id/rgPoleMode"
            android:layout_width="match_parent"
            android:layout_height="wrap_content">
            
            <RadioButton
                android:id="@+id/rbBothPoles"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="所有磁极 (N极和S极均触发)"
                android:checked="true"/>
            
            <RadioButton
                android:id="@+id/rbNPole"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="仅N极 (Z轴正值)"/>
            
            <RadioButton
                android:id="@+id/rbSPole"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="仅S极 (Z轴负值)"/>
                
            <RadioButton
                android:id="@+id/rbDifferent"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="区分磁极 (N极=短按操作, S极=长按操作)"/>
        </RadioGroup>

        <!-- N极短按操作 -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="N极 短按操作"
            android:textSize="16sp"
            android:textStyle="bold"
            android:layout_marginTop="24dp"/>
        
        <RadioGroup
            android:id="@+id/rgNShort"
            android:layout_width="match_parent"
            android:layout_height="wrap_content">
            
            <RadioButton
                android:id="@+id/rbNShortMedia"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="播放/暂停"
                android:checked="true"/>
            
            <RadioButton
                android:id="@+id/rbNShortVoice"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="语音助手"/>
        </RadioGroup>

        <!-- N极长按操作 -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="N极 长按操作"
            android:textSize="16sp"
            android:textStyle="bold"
            android:layout_marginTop="16dp"/>
        
        <RadioGroup
            android:id="@+id/rgNLong"
            android:layout_width="match_parent"
            android:layout_height="wrap_content">
            
            <RadioButton
                android:id="@+id/rbNLongMedia"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="播放/暂停"/>
            
            <RadioButton
                android:id="@+id/rbNLongVoice"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="语音助手"
                android:checked="true"/>
        </RadioGroup>

        <!-- S极短按操作 -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="S极 短按操作"
            android:textSize="16sp"
            android:textStyle="bold"
            android:layout_marginTop="24dp"/>
        
        <RadioGroup
            android:id="@+id/rgSShort"
            android:layout_width="match_parent"
            android:layout_height="wrap_content">
            
            <RadioButton
                android:id="@+id/rbSShortMedia"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="播放/暂停"
                android:checked="true"/>
            
            <RadioButton
                android:id="@+id/rbSShortVoice"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="语音助手"/>
        </RadioGroup>

        <!-- S极长按操作 -->
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="S极 长按操作"
            android:textSize="16sp"
            android:textStyle="bold"
            android:layout_marginTop="16dp"/>
        
        <RadioGroup
            android:id="@+id/rgSLong"
            android:layout_width="match_parent"
            android:layout_height="wrap_content">
            
            <RadioButton
                android:id="@+id/rbSLongMedia"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="播放/暂停"/>
            
            <RadioButton
                android:id="@+id/rbSLongVoice"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="语音助手"
                android:checked="true"/>
        </RadioGroup>

        <Button
            android:id="@+id/btnSave"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="保存设置"
            android:layout_marginTop="32dp"
            android:layout_marginBottom="32dp"/>

    </LinearLayout>
</ScrollView>
"""

# 4. SettingsActivity.kt
settings_activity = """
package com.example.magnetcontroller

import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.example.magnetcontroller.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        binding.seekThreshold.progress = prefs.thresholdTrigger.toInt()
        binding.tvThresholdValue.text = "当前: ${prefs.thresholdTrigger.toInt()} μT"
        
        binding.seekLongPress.progress = (prefs.longPressDuration / 100).toInt()
        binding.tvLongPressValue.text = "当前: ${"%.1f".format(prefs.longPressDuration / 1000f)} 秒"
        
        when (prefs.poleMode) {
            "both" -> binding.rbBothPoles.isChecked = true
            "n_only" -> binding.rbNPole.isChecked = true
            "s_only" -> binding.rbSPole.isChecked = true
            "different" -> binding.rbDifferent.isChecked = true
        }
        
        if (prefs.nShortAction == "media") binding.rbNShortMedia.isChecked = true
        else binding.rbNShortVoice.isChecked = true
        
        if (prefs.nLongAction == "media") binding.rbNLongMedia.isChecked = true
        else binding.rbNLongVoice.isChecked = true
        
        if (prefs.sShortAction == "media") binding.rbSShortMedia.isChecked = true
        else binding.rbSShortVoice.isChecked = true
        
        if (prefs.sLongAction == "media") binding.rbSLongMedia.isChecked = true
        else binding.rbSLongVoice.isChecked = true
    }

    private fun setupListeners() {
        binding.seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvThresholdValue.text = "当前: $progress μT"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.seekLongPress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress / 10f
                binding.tvLongPressValue.text = "当前: ${"%.1f".format(seconds)} 秒"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        prefs.thresholdTrigger = binding.seekThreshold.progress.toFloat()
        prefs.longPressDuration = binding.seekLongPress.progress * 100L
        
        prefs.poleMode = when {
            binding.rbBothPoles.isChecked -> "both"
            binding.rbNPole.isChecked -> "n_only"
            binding.rbSPole.isChecked -> "s_only"
            binding.rbDifferent.isChecked -> "different"
            else -> "both"
        }
        
        prefs.nShortAction = if (binding.rbNShortMedia.isChecked) "media" else "voice"
        prefs.nLongAction = if (binding.rbNLongMedia.isChecked) "media" else "voice"
        prefs.sShortAction = if (binding.rbSShortMedia.isChecked) "media" else "voice"
        prefs.sLongAction = if (binding.rbSLongMedia.isChecked) "media" else "voice"
        
        // 通知服务重新加载配置
        val intent = Intent("com.example.magnetcontroller.RELOAD_SETTINGS")
        intent.setPackage(packageName)
        sendBroadcast(intent)
        
        finish()
    }
}
"""

# 5. AppPreferences.kt
app_prefs = """
package com.example.magnetcontroller

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("MagnetSettings", Context.MODE_PRIVATE)

    var thresholdTrigger: Float
        get() = prefs.getFloat("threshold_trigger", 500f)
        set(value) = prefs.edit().putFloat("threshold_trigger", value).apply()

    var thresholdReset: Float
        get() = prefs.getFloat("threshold_reset", 300f)
        set(value) = prefs.edit().putFloat("threshold_reset", value).apply()

    var longPressDuration: Long
        get() = prefs.getLong("long_press_duration", 1500L)
        set(value) = prefs.edit().putLong("long_press_duration", value).apply()

    var poleMode: String
        get() = prefs.getString("pole_mode", "both") ?: "both"
        set(value) = prefs.edit().putString("pole_mode", value).apply()

    var nShortAction: String
        get() = prefs.getString("n_short_action", "media") ?: "media"
        set(value) = prefs.edit().putString("n_short_action", value).apply()

    var nLongAction: String
        get() = prefs.getString("n_long_action", "voice") ?: "voice"
        set(value) = prefs.edit().putString("n_long_action", value).apply()

    var sShortAction: String
        get() = prefs.getString("s_short_action", "media") ?: "media"
        set(value) = prefs.edit().putString("s_short_action", value).apply()

    var sLongAction: String
        get() = prefs.getString("s_long_action", "voice") ?: "voice"
        set(value) = prefs.edit().putString("s_long_action", value).apply()
}
"""

# 6. MagnetService.kt (完整版)
service_code = """
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class MagnetService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private lateinit var prefs: AppPreferences
    
    private var triggerStartTime = 0L
    private var isLongPressTriggered = false
    private var isContinuousVibrating = false
    private var currentPole: String = "none" // "N", "S", "none"
    
    private val CHANNEL_ID = "MagnetServiceChannel"
    private val TAG = "MagnetService"
    
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.magnetcontroller.RELOAD_SETTINGS") {
                loadSettings()
                logToUI("✅ 设置已重新加载")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        createNotificationChannel()
        initSensor()
        registerReceiver(settingsReceiver, IntentFilter("com.example.magnetcontroller.RELOAD_SETTINGS"))
    }
    
    private fun loadSettings() {
        // 配置会动态从 prefs 加载，无需额外操作
        Log.d(TAG, "设置已加载: 阈值=${prefs.thresholdTrigger}, 长按=${prefs.longPressDuration}ms")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("磁力服务运行中")
            .setContentText("正在后台监听磁场变化...")
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
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        unregisterReceiver(settingsReceiver)
        stopVibration()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

            // 检测磁极
            currentPole = when {
                z > 20 -> "N"
                z < -20 -> "S"
                else -> "none"
            }

            sendBroadcastToUI(x, y, z, magnitude, currentPole)
            processLogic(magnitude, z)
        }
    }
    
    private fun sendBroadcastToUI(x: Float, y: Float, z: Float, mag: Float, pole: String) {
        val intent = Intent("com.example.magnetcontroller.UPDATE_UI")
        intent.putExtra("x", x)
        intent.putExtra("y", y)
        intent.putExtra("z", z)
        intent.putExtra("mag", mag)
        intent.putExtra("pole", pole)
        intent.putExtra("status", getStatusText())
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
            if (isLongPressTriggered) "已触发长按操作" else "检测到磁铁 (计时中...)"
        } else {
            "监听中..."
        }
    }

    private fun processLogic(magnitude: Float, zValue: Float) {
        val now = System.currentTimeMillis()
        
        // 检查磁极模式
        val shouldTrigger = when (prefs.poleMode) {
            "both" -> true
            "n_only" -> zValue > 20
            "s_only" -> zValue < -20
            "different" -> true
            else -> true
        }
        
        if (!shouldTrigger) return

        if (magnitude > prefs.thresholdTrigger) {
            if (triggerStartTime == 0L) {
                triggerStartTime = now
                isLongPressTriggered = false
                startContinuousVibration()
            } else {
                if (!isLongPressTriggered && (now - triggerStartTime > prefs.longPressDuration)) {
                    stopVibration()
                    playDoubleBeep()
                    
                    // 根据磁极和模式决定操作
                    val action = if (prefs.poleMode == "different") {
                        if (currentPole == "N") prefs.nLongAction else prefs.sLongAction
                    } else {
                        if (currentPole == "N") prefs.nLongAction else prefs.sLongAction
                    }
                    
                    if (action == "voice") {
                        triggerVoiceAssistant()
                    } else {
                        triggerMediaKey()
                    }
                    
                    isLongPressTriggered = true
                }
            }
        } else if (magnitude < prefs.thresholdReset) {
            if (triggerStartTime != 0L) {
                stopVibration()
                
                if (!isLongPressTriggered) {
                    // 短按操作
                    val action = if (prefs.poleMode == "different") {
                        if (currentPole == "N") prefs.nShortAction else prefs.sShortAction
                    } else {
                        if (currentPole == "N") prefs.nShortAction else prefs.sShortAction
                    }
                    
                    if (action == "voice") {
                        triggerVoiceAssistant()
                    } else {
                        triggerMediaKey()
                    }
                }
                
                triggerStartTime = 0L
                isLongPressTriggered = false
            }
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
            val pattern = longArrayOf(0, 50, 100, 50)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun triggerVoiceAssistant() {
        logToUI("🎤 尝试唤起语音助手...")
        
        var success = tryXiaomiVoiceAssist()
        if (success) {
            logToUI("✅ 方案1成功: 小米小爱")
            return
        }
        
        success = tryAssist()
        if (success) {
            logToUI("✅ 方案2成功: ACTION_ASSIST")
            return
        }
        
        success = tryVoiceSearch()
        if (success) {
            logToUI("✅ 方案3成功: 语音搜索")
            return
        }
        
        success = tryGoogleAssistant()
        if (success) {
            logToUI("✅ 方案4成功: Google Assistant")
            return
        }
        
        success = tryHeadsetLongPress()
        if (success) {
            logToUI("✅ 方案5成功: 耳机长按")
            return
        }
        
        logToUI("❌ 所有方案均失败，请检查设置")
    }
    
    private fun tryXiaomiVoiceAssist(): Boolean {
        return try {
            logToUI("🔄 尝试小米小爱方式1...")
            val intent = Intent("android.intent.action.VOICE_ASSIST").apply {
                setPackage("com.miui.voiceassist")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            try {
                logToUI("🔄 尝试小米小爱方式2...")
                val intent = Intent().apply {
                    setClassName("com.miui.voiceassist", "com.xiaomi.voiceassistant.CTAAlertActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                true
            } catch (ex: Exception) {
                try {
                    logToUI("🔄 尝试小米小爱方式3...")
                    val intent = Intent("android.intent.action.VOICE_ASSIST").apply {
                        setPackage("com.xiaomi.voiceassistant")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                } catch (ex2: Exception) {
                    logToUI("❌ 小米小爱失败: ${ex2.message}")
                    false
                }
            }
        }
    }
    
    private fun tryAssist(): Boolean {
        return try {
            logToUI("🔄 尝试标准 ASSIST...")
            val intent = Intent(Intent.ACTION_ASSIST).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            logToUI("❌ ASSIST 失败: ${e.message}")
            false
        }
    }
    
    private fun tryVoiceSearch(): Boolean {
        return try {
            logToUI("🔄 尝试语音搜索...")
            val intent = Intent("android.speech.action.VOICE_SEARCH_HANDS_FREE").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            logToUI("❌ 语音搜索失败: ${e.message}")
            false
        }
    }
    
    private fun tryGoogleAssistant(): Boolean {
        return try {
            logToUI("🔄 尝试 Google Assistant...")
            val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                setPackage("com.google.android.googlequicksearchbox")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            try {
                val componentIntent = Intent().apply {
                    setClassName(
                        "com.google.android.googlequicksearchbox",
                        "com.google.android.voicesearch.greco3.Greco3Activity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(componentIntent)
                true
            } catch (ex: Exception) {
                logToUI("❌ Google Assistant 失败: ${ex.message}")
                false
            }
        }
    }
    
    private fun tryHeadsetLongPress(): Boolean {
        return try {
            logToUI("🔄 尝试耳机长按模拟...")
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val eventTime = System.currentTimeMillis()
            
            val keyEventDown = KeyEvent(
                eventTime, 
                eventTime, 
                KeyEvent.ACTION_DOWN, 
                KeyEvent.KEYCODE_HEADSETHOOK, 
                0
            )
            val keyEventUp = KeyEvent(
                eventTime + 1000,
                eventTime + 1000, 
                KeyEvent.ACTION_UP, 
                KeyEvent.KEYCODE_HEADSETHOOK, 
                0
            )
            
            audioManager.dispatchMediaKeyEvent(keyEventDown)
            Thread.sleep(1000)
            audioManager.dispatchMediaKeyEvent(keyEventUp)
            true
        } catch (e: Exception) {
            logToUI("❌ 耳机长按失败: ${e.message}")
            false
        }
    }

    private fun triggerMediaKey() {
        logToUI("▶️ 触发播放/暂停")
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = System.currentTimeMillis()
        val keyEventDown = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
        val keyEventUp = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
        try {
            audioManager.dispatchMediaKeyEvent(keyEventDown)
            audioManager.dispatchMediaKeyEvent(keyEventUp)
        } catch (e: Exception) {
            logToUI("❌ 媒体按键失败: ${e.message}")
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
"""

# 7. MainActivity.kt
main_activity = """
package com.example.magnetcontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.magnetcontroller.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logBuffer = mutableListOf<String>()
    
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.magnetcontroller.UPDATE_UI") {
                val x = intent.getFloatExtra("x", 0f)
                val y = intent.getFloatExtra("y", 0f)
                val z = intent.getFloatExtra("z", 0f)
                val mag = intent.getFloatExtra("mag", 0f)
                val pole = intent.getStringExtra("pole") ?: "none"
                val status = intent.getStringExtra("status") ?: ""
                
                throttleUiUpdate(x, y, z, mag, pole, status)
            } else if (intent?.action == "com.example.magnetcontroller.UPDATE_LOG") {
                val log = intent.getStringExtra("log") ?: ""
                addLog(log)
            }
        }
    }
    
    private var lastUiUpdateTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startMagnetService()
        
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
    
    private fun startMagnetService() {
        val serviceIntent = Intent(this, MagnetService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("com.example.magnetcontroller.UPDATE_UI")
            addAction("com.example.magnetcontroller.UPDATE_LOG")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(updateReceiver)
    }
    
    private fun throttleUiUpdate(x: Float, y: Float, z: Float, magnitude: Float, pole: String, status: String) {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdateTime > 100) { 
            binding.tvX.text = "X: ${"%.1f".format(x)}"
            binding.tvY.text = "Y: ${"%.1f".format(y)}"
            binding.tvZ.text = "Z: ${"%.1f".format(z)}"
            binding.tvMagnitude.text = "${"%.0f".format(magnitude)} μT"
            
            val poleText = when (pole) {
                "N" -> "极性: N极 (北极)"
                "S" -> "极性: S极 (南极)"
                else -> "极性: 未检测"
            }
            binding.tvPoleType.text = poleText
            binding.tvPoleType.setTextColor(
                when (pole) {
                    "N" -> android.graphics.Color.BLUE
                    "S" -> android.graphics.Color.RED
                    else -> android.graphics.Color.GRAY
                }
            )
            
            binding.tvStatus.text = "状态: $status"
            
            if (status.contains("触发")) {
                binding.tvStatus.setTextColor(android.graphics.Color.BLUE)
            } else if (status.contains("检测到")) {
                binding.tvStatus.setTextColor(android.graphics.Color.MAGENTA)
            } else {
                binding.tvStatus.setTextColor(android.graphics.Color.BLACK)
            }
            lastUiUpdateTime = now
        }
    }
    
    private fun addLog(message: String) {
        logBuffer.add(0, message)
        if (logBuffer.size > 5) {
            logBuffer.removeLast()
        }
        binding.tvLog.text = logBuffer.joinToString("\n")
    }
}
"""

if not os.path.exists("gradlew"):
    print("❌ 错误：未检测到 gradlew 文件。请在项目根目录运行。")
else:
    print("🚀 开始配置完整版 MagnetController v11...")
    
    # 创建所有文件
    create_file("app/src/main/AndroidManifest.xml", manifest)
    create_file("app/src/main/res/layout/activity_main.xml", layout_main)
    create_file("app/src/main/res/layout/activity_settings.xml", layout_settings)
    create_file("app/src/main/java/com/example/magnetcontroller/MainActivity.kt", main_activity)
    create_file("app/src/main/java/com/example/magnetcontroller/SettingsActivity.kt", settings_activity)
    create_file("app/src/main/java/com/example/magnetcontroller/AppPreferences.kt", app_prefs)
    create_file("app/src/main/java/com/example/magnetcontroller/MagnetService.kt", service_code)

    print("\n✨ 配置完成！")
    print("\n🎯 新功能：")
    print("1. ⚙️  高级设置界面 - 点击主界面右上角「设置」按钮")
    print("2. 🎚️  可调节触发阈值 (100-1000 μT)")
    print("3. ⏱️  可调节长按时长 (0.5-5.0 秒)")
    print("4. 🧲 磁极识别 - 区分 N极/S极")
    print("5. 🎮 独立操作设置 - N极和S极可分别设置短按/长按操作")
    print("6. 📊 实时日志 - 在主界面底部显示调试信息")
    print("\n👉 请执行：")
    print("   1. Build -> Clean Project")
    print("   2. Build -> Rebuild Project")
    print("   3. Run")
    print("\n📱 使用建议：")
    print("   - 先在主界面观察磁极类型（N极/S极）")
    print("   - 进入设置界面配置你需要的触发方式")
    print("   - 查看底部日志了解语音助手唤起的详细过程")
    print("   - 如果语音助手仍失败，日志会显示具体哪个方案失败及原因")