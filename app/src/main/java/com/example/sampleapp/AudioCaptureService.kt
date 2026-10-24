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
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCapture"
        private const val CHANNEL_ID = "AudioCaptureChannel"

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        @Volatile var gain: Float = 1.0f

        var statusCallback: ((String) -> Unit)? = null
        var currentStatus: String = "Idle"
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureThread: Thread? = null

    @Volatile private var isCapturing = false
    private var savedStreamVolume: Int = -1
    private var mutedSource = false

    override fun onCreate() {
        super.onCreate()
        try { createNotificationChannel() } catch (t: Throwable) { Log.e(TAG, "channel", t) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCaptureAndRestore(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> { /* fall through */ }
            else -> { updateStatus("Service started without action"); return START_NOT_STICKY }
        }

        try {
            startForeground(1, buildNotification("Starting…"))
        } catch (t: Throwable) {
            updateStatus("startForeground failed: ${t.message}")
            stopSelf(); return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            updateStatus("Missing projection result.")
            return START_NOT_STICKY
        }

        try { startCapture(resultCode, resultData) }
        catch (t: Throwable) { updateStatus("startCapture failed: ${t.message}") }
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val report = StringBuilder()

        // ── Read original STREAM_MUSIC volume via shell, then duck to 1 ──
        val getOut = shell("cmd media_session volume --stream 3 --get")
        report.appendLine("--get output:\n${getOut.trim()}\n")
        savedStreamVolume = parseVolumeIndex(getOut)
        report.appendLine("Saved original volume index: $savedStreamVolume")

        // Duck to 1 (not 0): Musicolet won't trigger its "pause on 0" logic,
        // and capture still gets non-silent audio.
        val setOut = shell("cmd media_session volume --stream 3 --set 1")
        report.appendLine("--set 1 output: ${setOut.trim()}")
        mutedSource = true

        // ── Capture setup (exclude our own UID) ──
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        if (mediaProjection == null) {
            updateStatus("$report\nMediaProjection null")
            restoreSourceVolume()
            return
        }

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()

        val sampleRate = 44100
        val inMask = AudioFormat.CHANNEL_IN_STEREO
        val outMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, inMask, encoding)
        if (minBuf <= 0) {
            updateStatus("$report\ngetMinBufferSize=$minBuf")
            restoreSourceVolume()
            return
        }

        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate).setChannelMask(inMask).build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            updateStatus("$report\nAudioRecord state=${record.state}")
            restoreSourceVolume(); record.release(); return
        }
        audioRecord = record

        // ── Output: USAGE_ALARM (unaffected by STREAM_MUSIC mute) ──
        val outAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(outAttr)
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate).setChannelMask(outMask).build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            updateStatus("$report\nAudioTrack state=${track.state}")
            restoreSourceVolume(); record.release(); track.release(); return
        }
        audioTrack = track

        isCapturing = true
        record.startRecording()
        track.play()

        report.appendLine()
        report.appendLine("✓ Capturing. Original ducked to 1, our output via ALARM stream.")
        report.appendLine("Move the slider to change gain in real time.")
        report.appendLine("If nothing is heard: raise ALARM stream volume (Settings → Sound).")
        updateStatus(report.toString())
        updateNotification("Capturing with gain=${(gain * 100).toInt()}%")

        captureThread = thread(name = "capture-loop") {
            val buffer = ShortArray(minBuf / 2)
            while (isCapturing) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val g = gain
                    for (i in 0 until read) {
                        buffer[i] = (buffer[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    audioTrack?.write(buffer, 0, read)
                } else if (read < 0) break
            }
        }
    }

    private fun stopCaptureAndRestore() {
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
        restoreSourceVolume()
        updateStatus("Stopped. Original volume restored.")
    }

    private fun restoreSourceVolume() {
        if (!mutedSource) return
        val target = if (savedStreamVolume in 1..99) savedStreamVolume else 10
        shell("cmd media_session volume --stream 3 --set $target")
        Log.d(TAG, "restored STREAM_MUSIC to $target")
        mutedSource = false
    }

    private fun parseVolumeIndex(out: String): Int {
        // Typical output: "volume: 10, stream: 3" or just "10"
        Regex("""volume[:=]\s*(\d+)""").find(out)?.let { return it.groupValues[1].toInt() }
        Regex("""^\s*(\d+)\s*$""", RegexOption.MULTILINE).find(out)?.let { return it.groupValues[1].toInt() }
        return -1
    }

    private fun shell(command: String): String {
        return try {
            if (!Shizuku.pingBinder()) return "(shizuku not running)"
            val args = command.split(" ").toTypedArray()
            val c = Class.forName("rikka.shizuku.Shizuku")
            val m = c.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val proc = m.invoke(null, args, null, null) as ShizukuRemoteProcess
            val out = BufferedReader(InputStreamReader(proc.inputStream))
                .useLines { it.joinToString("\n") }
            val err = BufferedReader(InputStreamReader(proc.errorStream))
                .useLines { it.joinToString("\n") }
            proc.waitFor()
            "$out\n$err".trim()
        } catch (t: Throwable) {
            "shell error: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun updateStatus(msg: String) {
        Log.d(TAG, msg)
        currentStatus = msg
        statusCallback?.invoke(msg)
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, buildNotification(text))
        } catch (_: Throwable) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Audio Capture", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fine-Grained Volume")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopCaptureAndRestore()
    }
}
