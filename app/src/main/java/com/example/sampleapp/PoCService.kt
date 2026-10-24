package com.example.sampleapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader

class PoCService : Service() {

    companion object {
        private const val TAG = "VolumePoC"
        private const val CHANNEL_ID = "VolumePoCChannel"
        var statusCallback: ((String) -> Unit)? = null
        var currentStatus: String = "Idle"
    }

    private val effects = mutableListOf<DynamicsProcessing>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        Thread { runPoC() }.start()
        return START_STICKY
    }

    private fun updateStatus(msg: String) {
        Log.d(TAG, msg)
        currentStatus = msg
        statusCallback?.invoke(msg)
    }

    private fun runPoC() {
        try {
            updateStatus("Running dumpsys media.audio_flinger via Shizuku…")

            val output = runShizukuCommandWithOutput(
                arrayOf("dumpsys", "media.audio_flinger")
            )

            if (output.isNullOrBlank()) {
                updateStatus("dumpsys returned nothing. Is Shizuku running?")
                return
            }
            Log.d(TAG, "dumpsys length: ${output.length}")

            val sessionIds = findAllActiveSessionIds(output)
            updateStatus("Found ${sessionIds.size} active session(s): $sessionIds")

            if (sessionIds.isEmpty()) {
                updateStatus("No active sessions. Play some music, then tap again.")
                return
            }

            var applied = 0
            for (id in sessionIds) {
                if (applyGainToSession(id, -10f)) applied++
            }
            updateStatus(
                "Applied −10 dB to $applied/${sessionIds.size} session(s). Listen for the drop!"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error in PoC", e)
            updateStatus("Error: ${e.message}")
        }
    }

    /**
     * Best-effort parser: finds every session whose nearby lines contain "ACTIVE".
     */
    private fun findAllActiveSessionIds(dumpsysOutput: String): List<Int> {
        val result = mutableListOf<Int>()
        val lines = dumpsysOutput.lines()
        val sessionRegex = Regex("""Session\s*(?:ID)?\s*[:=]?\s*(\d+)""")

        lines.forEachIndexed { index, line ->
            val match = sessionRegex.find(line) ?: return@forEachIndexed
            val id = match.groupValues[1].toIntOrNull() ?: return@forEachIndexed
            if (id <= 0) return@forEachIndexed

            val start = maxOf(0, index - 2)
            val end = minOf(lines.size, index + 8)
            val block = lines.subList(start, end).joinToString("\n")
            if (block.contains("ACTIVE", ignoreCase = true)) {
                result.add(id)
            }
        }
        return result.distinct()
    }

    private fun applyGainToSession(sessionId: Int, gainDb: Float): Boolean {
        return try {
            // Stereo config — safest default for media playback
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,      // stereo
                true,   // pre-EQ in use
                1,      // pre-EQ band count
                false,  // no MBC
                0,      // MBC band count
                false,  // no post-EQ
                0,      // post-EQ band count
                false   // no limiter
            ).build()

            val effect = DynamicsProcessing(0, sessionId, config)
            effect.setInputGainAllChannelsTo(gainDb)
            effects.add(effect)
            Log.d(TAG, "Attached −10 dB to session $sessionId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed session $sessionId: ${e.message}", e)
            false
        }
    }

    // ── Shizuku plumbing ───────────────────────────────────────

    private fun runShizukuCommandWithOutput(command: Array<String>): String? {
        return try {
            val process = newShizukuProcess(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val out = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                out.append(line).append("\n")
            }
            reader.close()
            process.waitFor()
            out.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku exec failed", e)
            null
        }
    }

    private fun newShizukuProcess(command: Array<String>): ShizukuRemoteProcess {
        val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
        val method = shizukuClass.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as ShizukuRemoteProcess
    }

    // ── Notification ───────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Volume PoC Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume PoC")
            .setContentText("Applying global gain…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        effects.forEach { runCatching { it.release() } }
        effects.clear()
    }
}
