package com.example.batterylogger

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnExport: Button
    private var csvFile: File? = null
    private var isRecording = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "battery_logger_prefs"
        private const val KEY_BATTERY_OPT_REQUESTED = "battery_opt_requested"
        private const val KEY_TEST_COUNTER = "test_counter"
        private const val KEY_IS_RECORDING = "is_recording"
        private const val KEY_CURRENT_FILE = "current_file"
        private const val NOTIFICATION_PERMISSION_REQUEST = 101
    }

    private val serviceChecker = object : Runnable {
        override fun run() {
            // 如果应用认为正在记录，但服务实际已经停止，则恢复按钮状态
            if (isRecording && !isServiceRunning(BatteryLogService::class.java)) {
                isRecording = false
                prefs.edit().putBoolean(KEY_IS_RECORDING, false).apply()
                updateButtonState()
                statusText.text = "记录已中断（服务被系统停止）"
            }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnExport = findViewById(R.id.btnExport)

        isRecording = prefs.getBoolean(KEY_IS_RECORDING, false)
        val path = prefs.getString(KEY_CURRENT_FILE, null)
        if (path != null) csvFile = File(path)

        btnStart.setOnClickListener { requestPermissionsAndStart() }
        btnStop.setOnClickListener { stopRecording() }
        btnExport.setOnClickListener { exportCsv() }

        // 打开应用立即请求通知权限（Android 13+）
        requestNotificationPermission()

        // 打开应用立即启动监控服务，显示常驻通知与实时电压
        startMonitorService()

        updateButtonState()
        if (isRecording && csvFile != null) {
            statusText.text = "正在记录...\nCSV 文件路径：\n${csvFile?.absolutePath}"
        } else {
            statusText.text = "就绪"
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isRecording = prefs.getBoolean(KEY_IS_RECORDING, false)
        val path = prefs.getString(KEY_CURRENT_FILE, null)
        if (path != null) csvFile = File(path)
        updateButtonState()
        handler.post(serviceChecker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(serviceChecker)
    }

    private fun startMonitorService() {
        val intent = Intent(this, BatteryLogService::class.java).apply {
            action = BatteryLogService.ACTION_START_MONITOR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun getNextLogFile(): File {
        val counter = prefs.getInt(KEY_TEST_COUNTER, 0) + 1
        prefs.edit().putInt(KEY_TEST_COUNTER, counter).apply()
        return File(getExternalFilesDir(null), "battery_log_$counter.csv")
    }

    private fun requestPermissionsAndStart() {
        // 通知权限已经在 onCreate 中请求，这里只处理电池优化
        val alreadyRequested = prefs.getBoolean(KEY_BATTERY_OPT_REQUESTED, false)
        if (!alreadyRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            prefs.edit().putBoolean(KEY_BATTERY_OPT_REQUESTED, true).apply()
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
        startRecording()
    }

    private fun startRecording() {
        val newFile = getNextLogFile()
        csvFile = newFile
        isRecording = true
        prefs.edit()
            .putBoolean(KEY_IS_RECORDING, true)
            .putString(KEY_CURRENT_FILE, newFile.absolutePath)
            .apply()
        val intent = Intent(this, BatteryLogService::class.java).apply {
            action = BatteryLogService.ACTION_START_RECORD
            putExtra("csv_path", newFile.absolutePath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateButtonState()
        statusText.text = "正在记录...\nCSV 文件路径：\n${newFile.absolutePath}"
    }

    private fun stopRecording() {
        isRecording = false
        prefs.edit().putBoolean(KEY_IS_RECORDING, false).apply()
        val intent = Intent(this, BatteryLogService::class.java).apply {
            action = BatteryLogService.ACTION_STOP_RECORD
        }
        startService(intent)
        updateButtonState()
        statusText.text = "已停止记录\nCSV 文件路径：\n${csvFile?.absolutePath ?: "无"}"
    }

    private fun updateButtonState() {
        btnStart.isEnabled = !isRecording
        btnStop.isEnabled = isRecording
        btnStart.alpha = if (isRecording) 0.5f else 1.0f
        btnStop.alpha = if (isRecording) 1.0f else 0.5f
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) return true
        }
        return false
    }

    private fun exportCsv() {
        val file = csvFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "CSV 文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出 CSV"))
    }
}
