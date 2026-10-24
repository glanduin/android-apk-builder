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
import kotlin.math.abs

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
        var gainUpdateCallback: ((Float) -> Unit)? = null
        var currentStatus: String = "Idle"
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureThread: Thread? = null

    @Volatile private var isCapturing = false
    private var savedStreamVolume: Int = -1
    private var sourceModified = false
    private var usingFloat = false

    override fun onCreate() {
        super.onCreate()
        try { createNotificationChannel() } catch (t: Throwable) { Log.e(TAG, "channel", t) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCaptureAndRestore(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> { }
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

        val getOut = shell("cmd media_session volume --stream 3 --get")
        report.appendLine("--get: ${getOut.lines().lastOrNull { it.isNotBlank() } ?: ""}")
        savedStreamVolume = parseVolumeIndex(getOut)
        report.appendLine("Saved original index: $savedStreamVolume")

        shell("cmd media_session volume --stream 3 --set 0")
        shell("cmd media_session volume --stream 3 --mute")
        sourceModified = true
        report.appendLine("Source: set 0 + mute")

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

        val outAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build()

        val sampleRate = 48000

        var ok = tryInitFloat(captureConfig, outAttr, sampleRate)
        if (ok) {
            usingFloat = true
            report.appendLine("Encoding: FLOAT (32-bit)")
        } else {
            report.appendLine("FLOAT failed, trying 16-bit…")
            ok = tryInitShort(captureConfig, outAttr, sampleRate)
            if (ok) {
                usingFloat = false
                report.appendLine("Encoding: 16-bit PCM")
            }
        }
        if (!ok) {
            updateStatus("$report\nFailed to initialize audio.")
            restoreSourceVolume()
            return
        }

        isCapturing = true
        audioRecord?.startRecording()
        audioTrack?.play()

        report.appendLine()
        report.appendLine("✓ Capturing with ${if (usingFloat) "FLOAT" else "16-bit"} @ ${sampleRate}Hz.")
        report.appendLine("Peak-normalization active (source over-drive corrected).")
        report.appendLine("Watch notification for live peak reading.")
        updateStatus(report.toString())
        updateNotification("Starting…")

        captureThread = thread(name = "capture-loop") {
            if (usingFloat) captureLoopFloat() else captureLoopShort()
        }
    }

    private fun tryInitFloat(
        captureConfig: AudioPlaybackCaptureConfiguration,
        outAttr: AudioAttributes,
        sampleRate: Int
    ): Boolean {
        return try {
            val enc = AudioFormat.ENCODING_PCM_FLOAT
            val inMask = AudioFormat.CHANNEL_IN_STEREO
            val outMask = AudioFormat.CHANNEL_OUT_STEREO

            val recMinBuf = AudioRecord.getMinBufferSize(sampleRate, inMask, enc)
            if (recMinBuf <= 0) return false

            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(enc).setSampleRate(sampleRate).setChannelMask(inMask).build()
                )
                .setBufferSizeInBytes(recMinBuf * 8)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return false }

            val trkMinBuf = AudioTrack.getMinBufferSize(sampleRate, outMask, enc)
            if (trkMinBuf <= 0) { record.release(); return false }

            val track = AudioTrack.Builder()
                .setAudioAttributes(outAttr)
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(enc).setSampleRate(sampleRate).setChannelMask(outMask).build()
                )
                .setBufferSizeInBytes(trkMinBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                record.release(); track.release(); return false
            }

            audioRecord = record
            audioTrack = track
            true
        } catch (t: Throwable) {
            Log.e(TAG, "tryInitFloat failed", t)
            false
        }
    }

    private fun tryInitShort(
        captureConfig: AudioPlaybackCaptureConfiguration,
        outAttr: AudioAttributes,
        sampleRate: Int
    ): Boolean {
        return try {
            val enc = AudioFormat.ENCODING_PCM_16BIT
            val inMask = AudioFormat.CHANNEL_IN_STEREO
            val outMask = AudioFormat.CHANNEL_OUT_STEREO

            val recMinBuf = AudioRecord.getMinBufferSize(sampleRate, inMask, enc)
            if (recMinBuf <= 0) return false

            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(enc).setSampleRate(sampleRate).setChannelMask(inMask).build()
                )
                .setBufferSizeInBytes(recMinBuf * 8)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return false }

            val trkMinBuf = AudioTrack.getMinBufferSize(sampleRate, outMask, enc)
            if (trkMinBuf <= 0) { record.release(); return false }

            val track = AudioTrack.Builder()
                .setAudioAttributes(outAttr)
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(enc).setSampleRate(sampleRate).setChannelMask(outMask).build()
                )
                .setBufferSizeInBytes(trkMinBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                record.release(); track.release(); return false
            }

            audioRecord = record
            audioTrack = track
            true
        } catch (t: Throwable) {
            Log.e(TAG, "tryInitShort failed", t)
            false
        }
    }

    private fun captureLoopFloat() {
        val record = audioRecord ?: return
        val track = audioTrack ?: return
        // Stereo interleaved: need 2× frames as sample count
        val buffer = FloatArray(maxOf(record.bufferSizeInFrames * 2, 8192))

        var everSawSignal = false
        var zeroCount = 0
        var fallbackTriggered = false
        val fallbackThreshold = 60

        // Peak-hold limiter state — rises fast, falls slow (no pumping)
        var heldPeak = 1.0f
        val decayPerBuffer = 0.9995f
        var bufferCount = 0
        var maxPeakEver = 0f

        while (isCapturing) {
            val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) { if (read < 0) break else continue }

            var bufferPeak = 0f
            for (i in 0 until read) {
                val a = abs(buffer[i])
                if (a > bufferPeak) bufferPeak = a
            }
            if (bufferPeak > maxPeakEver) maxPeakEver = bufferPeak

            // Fallback detection (volume 0 killed capture)
            if (bufferPeak > 0.005f) { everSawSignal = true; zeroCount = 0 }
            else if (bufferPeak == 0f) {
                zeroCount++
                if (!fallbackTriggered && !everSawSignal && zeroCount >= fallbackThreshold) {
                    shell("cmd media_session volume --stream 3 --unmute")
                    shell("cmd media_session volume --stream 3 --set 1")
                    gain = 15f
                    gainUpdateCallback?.invoke(gain)
                    fallbackTriggered = true
                    updateStatus("Volume 0 + mute killed capture. Fallback: unmuted, volume→1, gain→15×.")
                }
            } else zeroCount = 0

            // Peak-hold limiter: normalize source over-drive, then apply user gain
            heldPeak = maxOf(bufferPeak, heldPeak * decayPerBuffer)
            val normalize = if (heldPeak > 1.0f) 1.0f / heldPeak else 1.0f
            val totalGain = normalize * gain

            for (i in 0 until read) {
                buffer[i] = (buffer[i] * totalGain).coerceIn(-1f, 1f)
            }
            track.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING)

            bufferCount++
            if (bufferCount % 50 == 0) {
                updateNotification(
                    "Peak ${"%.2f".format(heldPeak)} | g×${"%.1f".format(gain)} → ${"%.2f".format(totalGain)}"
                )
            }
        }
        Log.d(TAG, "session end. max source peak: $maxPeakEver")
    }

    private fun captureLoopShort() {
        val record = audioRecord ?: return
        val track = audioTrack ?: return
        val buffer = ShortArray(maxOf(record.bufferSizeInFrames * 2, 8192))

        var everSawSignal = false
        var zeroCount = 0
        var fallbackTriggered = false
        val fallbackThreshold = 60

        var heldPeak = 1.0f
        val decayPerBuffer = 0.9995f
        var bufferCount = 0
        var maxPeakEver = 0f

        while (isCapturing) {
            val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) { if (read < 0) break else continue }

            var bufferPeak = 0f
            for (i in 0 until read) {
                val a = abs(buffer[i].toInt()) / 32768f
                if (a > bufferPeak) bufferPeak = a
            }
            if (bufferPeak > maxPeakEver) maxPeakEver = bufferPeak

            if (bufferPeak > 0.005f) { everSawSignal = true; zeroCount = 0 }
            else if (bufferPeak == 0f) {
                zeroCount++
                if (!fallbackTriggered && !everSawSignal && zeroCount >= fallbackThreshold) {
                    shell("cmd media_session volume --stream 3 --unmute")
                    shell("cmd media_session volume --stream 3 --set 1")
                    gain = 15f
                    gainUpdateCallback?.invoke(gain)
                    fallbackTriggered = true
                    updateStatus("Volume 0 + mute killed capture (16-bit). Fallback: unmuted, volume→1, gain→15×.")
                }
            } else zeroCount = 0

            heldPeak = maxOf(bufferPeak, heldPeak * decayPerBuffer)
            val normalize = if (heldPeak > 1.0f) 1.0f / heldPeak else 1.0f
            val totalGain = normalize * gain

            for (i in 0 until read) {
                val x = buffer[i].toFloat() / 32768f
                val y = (x * totalGain).coerceIn(-1f, 1f)
                buffer[i] = (y * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            track.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING)

            bufferCount++
            if (bufferCount % 50 == 0) {
                updateNotification(
                    "Peak ${"%.2f".format(heldPeak)} | g×${"%.1f".format(gain)} → ${"%.2f".format(totalGain)}"
                )
            }
        }
        Log.d(TAG, "session end. max source peak: $maxPeakEver")
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
        updateStatus("Stopped. Original volume restored to index $savedStreamVolume.")
    }

    private fun restoreSourceVolume() {
        if (!sourceModified) return
        val target = if (savedStreamVolume in 0..99) savedStreamVolume else 10
        shell("cmd media_session volume --stream 3 --unmute")
        shell("cmd media_session volume --stream 3 --set $target")
        Log.d(TAG, "restored STREAM_MUSIC to $target")
        sourceModified = false
    }

    private fun parseVolumeIndex(out: String): Int {
        Regex("""volume\s*(?:is|[:=])\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(out)?.let { return it.groupValues[1].toInt() }
        Regex("""index\s*[=:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(out)?.let { return it.groupValues[1].toInt() }
        Regex("""^\s*(\d+)\s*$""", RegexOption.MULTILINE)
            .find(out)?.let { return it.groupValues[1].toInt() }
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
