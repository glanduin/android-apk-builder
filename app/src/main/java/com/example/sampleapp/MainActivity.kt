package com.example.sampleapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusText.text = PoCService.currentStatus

        PoCService.statusCallback = { msg ->
            runOnUiThread { statusText.text = msg }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            try {
                handleClick()
            } catch (t: Throwable) {
                statusText.text = "CRASH CAUGHT:\n${t.javaClass.simpleName}: ${t.message}"
            }
        }

        requestNotificationPermissionIfNeeded()
    }

    private fun handleClick() {
        if (!Shizuku.pingBinder()) {
            statusText.text = "Shizuku binder is not alive.\nOpen Shizuku app and start the service, then try again."
            return
        }
        if (Shizuku.isPreV11()) {
            statusText.text = "Shizuku version too old (pre-v11). Update Shizuku."
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            startPoCService()
        } else {
            statusText.text = "Requesting Shizuku permission…"
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startPoCService()
            } else {
                statusText.text = "Shizuku permission denied."
            }
        }
    }

    private fun startPoCService() {
        try {
            val intent = Intent(this, PoCService::class.java)
            ContextCompat.startForegroundService(this, intent)
            statusText.text = "Service starting…"
        } catch (t: Throwable) {
            statusText.text = "Failed to start service:\n${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PoCService.statusCallback = null
    }
}
