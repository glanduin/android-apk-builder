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

    // Slider maps 0..100% to gain 0.0..1.0. Unity at 100%.
    // Source from capture already peaks at 1.0, so gain > 1.0 would always clip.
    private val MAX_GAIN = 1.0f

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

        AudioCaptureService.gainUpdateCallback = { g ->
            runOnUiThread {
                val p = ((g / MAX_GAIN) * 100).toInt().coerceIn(0, 100)
                if (gainSeek.progress != p) gainSeek.progress = p
            }
        }

        gainSeek.max = 100
        gainSeek.progress = 100                 // 100% = unity = your normal max
        AudioCaptureService.gain = 1.0f
        gainLabel.text = "Volume: 100%  (1.00× — same as normal max)"

        gainSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val g = progress / 100f * MAX_GAIN
                val note = when {
                    g < 0.02f -> "silent"
                    g < 0.20f -> "very quiet"
                    g < 0.60f -> "quieter than normal"
                    g < 0.98f -> "close to normal"
                    else -> "full volume"
                }
                gainLabel.text = "Volume: $progress%  (${"%.2f".format(g)}× — $note)"
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
