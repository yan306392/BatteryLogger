package com.example.batterylogger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private var csvFile: File? = null

    companion object {
        private const val PREFS_NAME = "battery_logger_prefs"
        private const val KEY_BATTERY_OPT_REQUESTED = "battery_opt_requested"
        private const val KEY_TEST_COUNTER = "test_counter"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            requestPermissionsAndStart()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, BatteryLogService::class.java))
            statusText.text = "已停止记录\nCSV 文件路径：\n${csvFile?.absolutePath ?: "无"}"
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            exportCsv()
        }

        statusText.text = "就绪"
    }

    private fun getNextLogFile(): File {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val counter = prefs.getInt(KEY_TEST_COUNTER, 0) + 1
        prefs.edit().putInt(KEY_TEST_COUNTER, counter).apply()
        return File(getExternalFilesDir(null), "battery_log_$counter.csv")
    }

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
        // 只在第一次使用时跳转电池优化设置
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val alreadyRequested = prefs.getBoolean(KEY_BATTERY_OPT_REQUESTED, false)
        if (!alreadyRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            prefs.edit().putBoolean(KEY_BATTERY_OPT_REQUESTED, true).apply()
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
        startService()
    }

    private fun startService() {
        val newFile = getNextLogFile()
        csvFile = newFile
        val intent = Intent(this, BatteryLogService::class.java)
        intent.putExtra("csv_path", newFile.absolutePath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = "正在记录...\nCSV 文件路径：\n${newFile.absolutePath}"
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
