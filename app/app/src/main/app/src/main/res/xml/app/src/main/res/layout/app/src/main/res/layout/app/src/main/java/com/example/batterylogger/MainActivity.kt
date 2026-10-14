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
    private lateinit var csvFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        csvFile = File(getExternalFilesDir(null), "battery_log.csv")

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            requestPermissionsAndStart()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, BatteryLogService::class.java))
            statusText.text = "已停止记录"
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            exportCsv()
        }

        statusText.text = "CSV 文件路径：\n${csvFile.absolutePath}"
    }

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
        startService()
    }

    private fun startService() {
        val intent = Intent(this, BatteryLogService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = "正在记录...\nCSV 文件路径：\n${csvFile.absolutePath}"
    }

    private fun exportCsv() {
        if (!csvFile.exists()) {
            Toast.makeText(this, "CSV 文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", csvFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出 CSV"))
    }
}
