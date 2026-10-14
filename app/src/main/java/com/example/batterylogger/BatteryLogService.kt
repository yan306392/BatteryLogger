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
    private var recordJob: Job? = null
    private var lastLevel = -1
    private var lastRecordTime = 0L
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var batteryReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra("csv_path")
        if (path != null) {
            csvFile = File(path)
        }
        val file = csvFile
        if (file == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!file.exists()) {
            try {
                file.writeText("time,Voltage,Battery level\n")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        registerBatteryReceiver()
        recordJob?.cancel()
        recordJob = serviceScope.launch {
            while (isActive) {
                recordBattery(force = true)
                delay(10_000)
            }
        }
        return START_STICKY
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
                if (percent != lastLevel && percent != -1) {
                    lastLevel = percent
                    recordBattery(force = false)
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun startForegroundWithNotification() {
        val channelId = "battery_log_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "电池记录", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("电池记录中")
            .setContentText("每10秒记录一次电池信息")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(1, notification)
    }

    private fun recordBattery(force: Boolean) {
        val file = csvFile ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastRecordTime < 2000) return

        try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val time = dateFormat.format(Date())
            val line = "$time,$voltage,$percent\n"
            file.appendText(line)
            lastRecordTime = now
            if (percent != -1) lastLevel = percent
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        recordJob?.cancel()
        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) { }
        }
        batteryReceiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
