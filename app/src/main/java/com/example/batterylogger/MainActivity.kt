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
import android.os.Environment
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
    private lateinit var btnShare: Button
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
        private const val STORAGE_PERMISSION_REQUEST = 102
    }

    private val serviceChecker = object : Runnable {
        override fun run() {
            if (isRecording && !isServiceRunning(BatteryLogService::class.java)) {
                isRecording = false
                prefs.edit().putBoolean(KEY_IS_RECORDING, false).apply()
                updateButtonState()
                statusText.text = "记录已中断（服务被系统停止）\n上次文件：Download/${csvFile?.name ?: ""}"
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
        btnShare = findViewById(R.id.btnExport)

        val path = prefs.getString(KEY_CURRENT_FILE, null)
        if (path != null) csvFile = File(path)

        btnStart.setOnClickListener { requestPermissionsAndStart() }
        btnStop.setOnClickListener { stopRecording() }
        btnShare.setOnClickListener { shareFile() }

        requestNotificationPermission()
        startMonitorService()

        // 核心修复：启动时检查真实状态，清除假死状态
        syncUiStateWithService()
    }

    private fun syncUiStateWithService() {
        val prefsRecording = prefs.getBoolean(KEY_IS_RECORDING, false)
        val serviceActuallyRunning = isServiceRunning(BatteryLogService::class.java)

        if (prefsRecording && !serviceActuallyRunning) {
            // 假死状态：本地说在记录，但服务其实已经死了（比如自动关机）
            isRecording = false
            prefs.edit().putBoolean(KEY_IS_RECORDING, false).apply()
            statusText.text = "上次记录已结束（异常关机或系统清理）\n文件已保存：\nDownload/${csvFile?.name ?: ""}"
        } else {
            isRecording = prefsRecording
            if (isRecording && csvFile != null) {
                statusText.text = "正在记录...\n文件：Download/${csvFile?.name}"
            } else {
                statusText.text = "就绪"
            }
        }
        updateButtonState()
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
        syncUiStateWithService()
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
        val name = "battery_log_$counter.csv"
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        return File(downloadDir, name)
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true
        }
        val result = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        return result == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsAndStart() {
        if (!hasStoragePermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                STORAGE_PERMISSION_REQUEST
            )
            return
        }
        val alreadyRequested = prefs.getBoolean(KEY_BATTERY_OPT_REQUESTED, false)
        if (!alreadyRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            prefs.edit().putBoolean(KEY_BATTERY_OPT_REQUESTED, true).apply()
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
        startRecording()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestPermissionsAndStart()
            } else {
                Toast.makeText(this, "需要存储权限才能保存到 Download 文件夹", Toast.LENGTH_LONG).show()
            }
        }
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
        statusText.text = "正在记录...\n文件：Download/${newFile.name}"
    }

    private fun stopRecording() {
        isRecording = false
        prefs.edit().putBoolean(KEY_IS_RECORDING, false).apply()
        val intent = Intent(this, BatteryLogService::class.java).apply {
            action = BatteryLogService.ACTION_STOP_RECORD
        }
        startService(intent)
        updateButtonState()
        statusText.text = "已停止记录\n文件已保存到：\nDownload/${csvFile?.name ?: ""}"
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

    private fun shareFile() {
        val file = csvFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "CSV 文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享 CSV"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
