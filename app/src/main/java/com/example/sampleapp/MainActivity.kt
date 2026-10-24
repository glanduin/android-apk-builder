package com.example.sampleapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val RECORD_AUDIO_PERMISSION_REQUEST = 1001
    private val NOTIFICATION_PERMISSION_REQUEST = 1002
    private val MEDIA_PROJECTION_REQUEST = 1003
    private val MAX_GAIN = 20f

    private lateinit var statusText: TextView
    private lateinit var gainLabel: TextView
    private lateinit var gainSeek: SeekBar
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        gainLabel = findViewById(R.id.gainLabel)
        gainSeek = findViewById(R.id.gainSeek)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        AudioCaptureService.statusCallback = { msg -> runOnUiThread { statusText.text = msg } }

        // Listen for gain changes coming FROM the service (fallback auto-boost)
        AudioCaptureService.gainUpdateCallback = { g ->
            runOnUiThread {
                val progress = ((g / MAX_GAIN) * 100).toInt().coerceIn(0, 100)
                if (gainSeek.progress != progress) gainSeek.progress = progress
            }
        }

        gainSeek.max = 100
        gainSeek.progress = 5                     // 1.0× — safe default if volume 0 gives full signal
        AudioCaptureService.gain = 1f
        gainLabel.text = "Gain: 5%  (1.0×)"

        gainSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val g = progress / 100f * MAX_GAIN
                gainLabel.text = "Gain: $progress%  (${"%.1f".format(g)}×)"
                AudioCaptureService.gain = g
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.startButton).setOnClickListener { startFlow() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP
            })
        }

        requestNotificationPermissionIfNeeded()
        requestRecordAudioPermissionIfNeeded()
    }

    private fun startFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Recording permission missing."
            requestRecordAudioPermissionIfNeeded()
            return
        }
        if (!rikka.shizuku.Shizuku.pingBinder()) {
            statusText.text = "Shizuku is not running. Start Shizuku first."
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST)
    }

    @Deprecated("legacy")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != MEDIA_PROJECTION_REQUEST) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            statusText.text = "Screen-capture permission denied."
            return
        }
        val si = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, si)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            }
        }
    }

    private fun requestRecordAudioPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_REQUEST)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioCaptureService.statusCallback = null
        AudioCaptureService.gainUpdateCallback = null
    }
}
