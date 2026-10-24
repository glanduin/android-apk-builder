package com.example.sampleapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

    private lateinit var statusText: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var volumeSeek: SeekBar
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusText.text = AudioCaptureService.currentStatus
        AudioCaptureService.statusCallback = { msg -> runOnUiThread { statusText.text = msg } }

        volumeLabel = findViewById(R.id.volumeLabel)
        volumeSeek = findViewById(R.id.volumeSeek)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeLabel.text = "Slider: $progress%"
                if (fromUser) applyVolume(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.attachButton).setOnClickListener { attachToMusicolet() }
        findViewById<Button>(R.id.startButton).setOnClickListener { startCaptureFlow() }

        requestNotificationPermissionIfNeeded()
        requestRecordAudioPermissionIfNeeded()

        if (!isNotificationListenerEnabled()) {
            statusText.text = "Notification Listener NOT enabled.\n" +
                "Enable: Settings → Notifications → Special app access → " +
                "Notification access → Volume PoC → Allow.\n\n" +
                "Then play music in Musicolet and tap 'Attach'."
        } else {
            statusText.text = "Ready.\nPlay music in Musicolet, tap 'Attach', then drag the slider."
        }
    }

    private fun attachToMusicolet() {
        if (!isNotificationListenerEnabled()) {
            statusText.text = "Notification Listener not enabled.\n" +
                "Settings → Notifications → Special app access → " +
                "Notification access → Volume PoC → Allow."
            return
        }
        try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = msm.getActiveSessions(null)
            val pkgList = sessions.joinToString(", ") { it.packageName }
            val musicolet = sessions.find { it.packageName == "in.krosbits.musicolet" }
            if (musicolet == null) {
                statusText.text = "No Musicolet session.\nActive sessions: [$pkgList]\n" +
                    "Start music in Musicolet, then tap Attach again."
                return
            }
            mediaController = MediaController(this, musicolet.sessionToken)
            val pb = mediaController?.playbackInfo
            statusText.text = "✓ Attached to in.krosbits.musicolet\n" +
                "currentVolume=${pb?.currentVolume}  maxVolume=${pb?.maxVolume}\n\n" +
                "Drag the slider. If maxVolume is a big number (e.g. 100+), " +
                "Musicolet handles its own volume and we get fine-grained steps for free."
        } catch (t: Throwable) {
            statusText.text = "attach failed: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun applyVolume(progressPercent: Int) {
        val ctrl = mediaController
        if (ctrl == null) {
            statusText.text = "No controller. Tap 'Attach to Musicolet' first."
            return
        }
        try {
            val info = ctrl.playbackInfo
            val maxVol = info?.maxVolume?.takeIf { it > 0 } ?: 100
            val targetVol = (progressPercent.toFloat() / 100f * maxVol)
                .toInt()
                .coerceIn(0, maxVol)

            // The correct MediaController volume API: index-based, not float.
            ctrl.setVolumeTo(targetVol, 0)

            val after = ctrl.playbackInfo
            statusText.text =
                "setVolumeTo($targetVol) → reported: ${after?.currentVolume} / $maxVol"
        } catch (t: Throwable) {
            statusText.text = "setVolumeTo failed: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.contains(packageName)
    }

    private fun startCaptureFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Recording permission missing."
            requestRecordAudioPermissionIfNeeded()
            return
        }
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, MEDIA_PROJECTION_REQUEST)
    }

    @Deprecated("legacy")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != MEDIA_PROJECTION_REQUEST) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            statusText.text = "Screen-capture permission DENIED."
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
        mediaController = null
    }
}
