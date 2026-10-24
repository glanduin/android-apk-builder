package com.example.sampleapp

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCapture"
        private const val CHANNEL_ID = "AudioCaptureChannel"

        const val ACTION_START = "ACTION_START"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        var statusCallback: ((String) -> Unit)? = null
        var currentStatus: String = "Idle"
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureThread: Thread? = null

    @Volatile private var isCapturing = false

    private var originalStreamVolume: Int = -1
    private var originalStreamMuted: Boolean = false
    private var didMuteStream: Boolean = false

    override fun onCreate() {
        super.onCreate()
        try { createNotificationChannel() } catch (t: Throwable) { Log.e(TAG, "channel", t) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            updateStatus("Service started without ACTION_START")
            return START_NOT_STICKY
        }

        try {
            startForeground(1, buildNotification("Capturing and replaying audio…"))
        } catch (t: Throwable) {
            updateStatus("startForeground FAILED: ${t.javaClass.simpleName}: ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            updateStatus("Missing projection result — cannot capture.")
            return START_NOT_STICKY
        }

        try {
            startCapture(resultCode, resultData)
        } catch (t: Throwable) {
            Log.e(TAG, "startCapture failed", t)
            updateStatus("startCapture failed:\n${t.javaClass.simpleName}: ${t.message}")
        }
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val report = StringBuilder()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            updateStatus("MediaProjection is null — permission not granted?")
            return
        }

        // ── Step 1: Mute the source stream so the original stops playing ──
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalStreamVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        originalStreamMuted = am.isStreamMute(AudioManager.STREAM_MUSIC)

        try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        } catch (t: Throwable) {
            report.appendLine("adjustStreamVolume(ADJUST_MUTE) threw: ${t.message}")
        }

        val nowMuted = am.isStreamMute(AudioManager.STREAM_MUSIC)
        didMuteStream = nowMuted && !originalStreamMuted
        if (nowMuted) {
            report.appendLine("✓ STREAM_MUSIC muted (original silenced)")
        } else {
            report.appendLine("✗ STREAM_MUSIC mute FAILED — you will hear double audio.")
            report.appendLine("  (Funtouch blocks volume APIs. We proceed anyway.)")
        }

        // Check STREAM_ALARM volume — this is where OUR output will play
        val alarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val alarmMax = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        report.appendLine("Alarm stream volume: $alarmVol / $alarmMax")
        if (alarmVol == 0) {
            report.appendLine("⚠ Alarm volume is 0 — our replay will be silent.")
            report.appendLine("  Raise the 'Alarm' slider to hear our output.")
        }
        report.appendLine()

        // ── Step 2: Set up capture (exclude our own UID to prevent feedback) ──
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()

        val sampleRate = 44100
        val inChannelMask = AudioFormat.CHANNEL_IN_STEREO
        val outChannelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBufBytes = AudioRecord.getMinBufferSize(sampleRate, inChannelMask, encoding)
        if (minBufBytes <= 0) {
            updateStatus("$report\ngetMinBufferSize returned $minBufBytes — audio not available.")
            return
        }

        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(inChannelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBufBytes * 4)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            updateStatus("$report\nAudioRecord not initialized (state=${record.state}).")
            record.release()
            return
        }
        audioRecord = record

        // ── Step 3: Route our replay via STREAM_ALARM so the mute doesn't hit it ──
        // USAGE_ALARM maps to STREAM_ALARM, which is unaffected by STREAM_MUSIC mute.
        val outAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(outAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(outChannelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBufBytes * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            updateStatus("$report\nAudioTrack not initialized (state=${track.state}).")
            track.release()
            record.release()
            restoreStreamState(am)
            return
        }
        audioTrack = track

        isCapturing = true
        record.startRecording()
        track.play()
        report.appendLine("Capturing. You should hear ONLY our replay.")
        report.appendLine("If you hear echo, mute FAILED — see above.")
        updateStatus(report.toString())
        updateNotification("Capturing — replay via alarm stream")

        captureThread = thread(name = "capture-loop") {
            val buffer = ShortArray(minBufBytes / 2)
            var totalRead = 0L
            while (isCapturing) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    // Phase 1: pass-through. Phase 2 will apply gain here.
                    audioTrack?.write(buffer, 0, read)
                    totalRead += read
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                    break
                }
            }
            Log.d(TAG, "capture loop ended, total samples read: $totalRead")
        }
    }

    private fun restoreStreamState(am: AudioManager) {
        try {
            if (didMuteStream && !originalStreamMuted) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            if (originalStreamVolume >= 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, originalStreamVolume, 0)
            }
            Log.d(TAG, "restored STREAM_MUSIC to vol=$originalStreamVolume muted=$originalStreamMuted")
        } catch (t: Throwable) {
            Log.e(TAG, "restoreStreamState failed", t)
        }
    }

    private fun updateStatus(msg: String) {
        Log.d(TAG, msg)
        currentStatus = msg
        statusCallback?.invoke(msg)
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotification(text))
        } catch (_: Throwable) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Audio Capture", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Capture PoC")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isCapturing = false
        try { captureThread?.join(500) } catch (_: Throwable) {}
        captureThread = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        restoreStreamState(am)
    }
}
