package com.example.batterylogger

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BatteryLogService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var csvFile: File? = null
    private var isRecording = false
    private var recordJob: Job? = null
    private var monitorJob: Job? = null
    private var lastRecordTime = 0L
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var batteryReceiver: BroadcastReceiver? = null
    private var currentVoltage = -1
    private var currentLevel = -1
    private var lastNotifiedLevel = -1

    companion object {
        const val ACTION_START_MONITOR = "com.example.batterylogger.START_MONITOR"
        const val ACTION_START_RECORD = "com.example.batterylogger.START_RECORD"
        const val ACTION_STOP_RECORD = "com.example.batterylogger.STOP_RECORD"
        private const val CHANNEL_ID = "battery_log_channel"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "battery_logger_prefs"
        private const val KEY_IS_RECORDING = "is_recording"

        private val VOLTAGE_PATHS = arrayOf(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/battery/Voltage_Now",
            "/sys/class/power_supply/battery/batt_vol",
            "/sys/class/power_supply/battery/Battery_Voltage_Now",
            "/sys/class/power_supply/battery/voltage_avg",
            "/sys/class/power_supply/sprd_battery/voltage_now",
            "/sys/class/power_supply/battery/batt_voltage",
            "/sys/class/power_supply/bms/voltage_now"
        )
    }

    override fun onCreate() {
        super.onCreate()
        // 核心修复：服务只要被重新创建，说明之前的记录必定已中断，强制重置状态
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_RECORDING, false).apply()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("电池监控", "正在获取电压..."))
        registerBatteryReceiver()
        startMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        when (intent?.action) {
            ACTION_START_MONITOR -> { }
            ACTION_START_RECORD -> {
                val path = intent.getStringExtra("csv_path")
                if (path != null) {
                    csvFile = File(path)
                    isRecording = true
                    prefs.edit().putBoolean(KEY_IS_RECORDING, true).apply()
                    
                    if (!csvFile!!.exists()) {
                        try {
                            csvFile!!.parentFile?.mkdirs()
                            csvFile!!.writeText("time,Voltage,Battery level\n")
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    startRecordingLoop()
                    updateNotification("记录中", "电压: ${currentVoltage}mV  电量: ${currentLevel}%")
                }
            }
            ACTION_STOP_RECORD -> {
                isRecording = false
                prefs.edit().putBoolean(KEY_IS_RECORDING, false).apply()
                recordJob?.cancel()
                recordJob = null
                updateNotification("电池监控", "电压: ${currentVoltage}mV  电量: ${currentLevel}%")
            }
        }
        return START_STICKY
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                updateBatteryInfo()
                updateNotification(
                    if (isRecording) "记录中" else "电池监控",
                    "电压: ${currentVoltage}mV  电量: ${currentLevel}%"
                )
                delay(1000)
            }
        }
    }

    private fun startRecordingLoop() {
        recordJob?.cancel()
        recordJob = serviceScope.launch {
            while (isActive && isRecording) {
                writeLog(force = true)
                delay(10_000)
            }
        }
    }

    private fun updateBatteryInfo() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        currentLevel = if (level >= 0 && scale > 0) level * 100 / scale else -1
        currentVoltage = if (voltage > 0) voltage else readVoltageFromSysfs()
    }

    private fun readVoltageFromSysfs(): Int {
        for (path in VOLTAGE_PATHS) {
            try {
                val file = File(path)
                if (!file.exists() || !file.canRead()) continue
                val raw = file.readText().trim()
                val value = raw.toLongOrNull() ?: continue
                if (value <= 0) continue
                return if (value > 10000) (value / 1000).toInt() else value.toInt()
            } catch (e: Exception) { }
        }
        return -1
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
                val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)

                if (percent != -1) currentLevel = percent
                if (voltage > 0) currentVoltage = voltage

                if (isRecording && percent != -1 && percent != lastNotifiedLevel) {
                    lastNotifiedLevel = percent
                    writeLog(force = false, level = percent, voltage = currentVoltage)
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "电池记录", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    private fun writeLog(force: Boolean, level: Int = currentLevel, voltage: Int = currentVoltage) {
        if (!isRecording) return
        val file = csvFile ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastRecordTime < 2000) return

        try {
            val time = dateFormat.format(Date())
            val finalVoltage = if (voltage > 0) voltage else readVoltageFromSysfs()
            val line = "$time,$finalVoltage,$level\n"
            file.appendText(line)
            lastRecordTime = now
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        recordJob?.cancel()
        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) { }
        }
        batteryReceiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
