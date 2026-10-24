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
        try {
            createNotificationChannel()
        } catch (t: Throwable) {
            Log.e(TAG, "Channel create failed", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(1, buildNotification())
        } catch (t: Throwable) {
            updateStatus("startForeground FAILED: ${t.javaClass.simpleName}: ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }
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
                updateStatus("dumpsys returned nothing. Shizuku exec failed.")
                return
            }
            Log.d(TAG, "dumpsys length: ${output.length}")

            val sessionIds = findAllActiveSessionIds(output)
            updateStatus("Found ${sessionIds.size} active session(s): $sessionIds")

            if (sessionIds.isEmpty()) {
                // Dump a useful snippet of the raw output so we can diagnose the format
                val keywords = listOf("Session", "Track", "Active", "Output thread", "Input thread", "Player", "uid")
                val relevant = output.lines()
                    .filter { line -> keywords.any { line.contains(it, ignoreCase = true) } }
                    .take(40)
                    .joinToString("\n")
                val snippet = if (relevant.isNotBlank()) relevant else output.take(2000)
                updateStatus(
                    "No active sessions found.\n\n---RELEVANT LINES FROM DUMPSYS---\n$snippet"
                )
                return
            }

            var applied = 0
            for (id in sessionIds) {
                if (applyGainToSession(id, -10f)) applied++
            }
            updateStatus(
                "Applied −10 dB to $applied/${sessionIds.size} session(s). Listen for the drop!"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "PoC fatal", t)
            updateStatus("PoC exception: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Column-aware parser for the tabular dumpsys media.audio_flinger output.
     *
     * Looks for a section starting with "N Tracks of which M are active", reads
     * the header row to find the positions of "Session", "Active" and "S" columns,
     * then extracts the Session ID from every data row whose Active column is "yes"
     * or whose S column is "A".
     */
    private fun findAllActiveSessionIds(dumpsysOutput: String): List<Int> {
        val sessionIds = mutableListOf<Int>()
        val lines = dumpsysOutput.lines()

        var inTrackSection = false
        var sessionCol = -1
        var activeCol = -1
        var sCol = -1

        for (rawLine in lines) {
            val line = rawLine.trim()

            // 1. Section start: "3 Tracks of which 1 are active"
            if (line.contains("Tracks of which", ignoreCase = true) &&
                line.contains("are active", ignoreCase = true)
            ) {
                val match = Regex("""(\d+)\s+Tracks of which\s+(\d+)\s+are active""")
                    .find(line)
                val activeCount = match?.groupValues?.get(2)?.toIntOrNull()
                inTrackSection = (activeCount != null && activeCount > 0)
                sessionCol = -1
                activeCol = -1
                sCol = -1
                continue
            }

            if (!inTrackSection) continue

            // 2. Header row (contains both "Session" and "Active")
            if (sessionCol == -1 &&
                line.contains("Session", ignoreCase = true) &&
                line.contains("Active", ignoreCase = true)
            ) {
                val headers = line.split(Regex("""\s+"""))
                sessionCol = headers.indexOfFirst { it.equals("Session", ignoreCase = true) }
                activeCol = headers.indexOfFirst { it.equals("Active", ignoreCase = true) }
                sCol = headers.indexOfFirst { it.equals("S", ignoreCase = true) }
                continue
            }

            // 3. Section end: blank line or a new thread block
            if (line.isBlank() ||
                line.startsWith("Output thread", ignoreCase = true) ||
                line.startsWith("Input thread", ignoreCase = true)
            ) {
                inTrackSection = false
                continue
            }

            // 4. Data row
            if (sessionCol >= 0) {
                val cols = line.split(Regex("""\s+"""))
                if (cols.size > maxOf(sessionCol, activeCol, sCol)) {
                    val isActive =
                        (activeCol >= 0 && cols[activeCol].equals("yes", ignoreCase = true)) ||
                        (sCol >= 0 && cols[sCol].equals("A", ignoreCase = true))
                    if (isActive) {
                        cols[sessionCol].toIntOrNull()?.let { id ->
                            if (id > 0) sessionIds.add(id)
                        }
                    }
                }
            }
        }

        return sessionIds.distinct()
    }

    private fun applyGainToSession(sessionId: Int, gainDb: Float): Boolean {
        return try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true,
                1,
                false,
                0,
                false,
                0,
                false
            ).build()

            val effect = DynamicsProcessing(0, sessionId, config)
            effect.setInputGainAllChannelsTo(gainDb)
            effects.add(effect)
            Log.d(TAG, "Attached −10 dB to session $sessionId")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Effect attach failed for session $sessionId: ${t.message}", t)
            false
        }
    }

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
        } catch (t: Throwable) {
            Log.e(TAG, "Shizuku exec failed", t)
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Volume PoC Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume PoC")
            .setContentText("Applying global gain…")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        effects.forEach { runCatching { it.release() } }
        effects.clear()
    }
}
