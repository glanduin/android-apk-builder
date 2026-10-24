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
        try { createNotificationChannel() } catch (t: Throwable) { Log.e(TAG, "channel", t) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(1, buildNotification())
        } catch (t: Throwable) {
            updateStatus("startForeground FAILED: ${t.javaClass.simpleName}: ${t.message}")
            stopSelf(); return START_NOT_STICKY
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
            val report = StringBuilder()
            updateStatus("Running dumpsys…")

            val flingerDump = runShizukuCommandWithOutput(arrayOf("dumpsys", "media.audio_flinger"))
            if (flingerDump.isNullOrBlank()) {
                updateStatus("dumpsys media.audio_flinger returned nothing.")
                return
            }

            // PRIMARY: Global session refs — the authoritative source
            val fromRefs = parseGlobalSessionRefs(flingerDump)
            report.appendLine("Global session refs found: $fromRefs")

            // FALLBACK: track tables
            val fromTracks = parseTrackTables(flingerDump)
            report.appendLine("Track tables found: $fromTracks")

            val targets = (fromRefs + fromTracks).distinct()
            report.appendLine("Targeting: $targets")
            report.appendLine()

            if (targets.isEmpty()) {
                updateStatus("$report\nNo sessions to target.\nMake sure music is playing.")
                return
            }

            // Release any previous effects so we can re-attach cleanly
            effects.forEach { runCatching { it.release() } }
            effects.clear()

            var ok = 0
            var fail = 0
            for (id in targets) {
                if (tryAttach(id, -10f)) {
                    ok++
                    report.appendLine("✓ session $id  (−10 dB)")
                } else {
                    fail++
                    report.appendLine("✗ session $id  attach FAILED")
                }
            }
            report.appendLine()
            report.appendLine("Attached: $ok  /  Failed: $fail")
            if (ok > 0) {
                report.appendLine()
                report.appendLine("▶ Listen for the volume drop NOW.")
                report.appendLine("Tap again to re-attach if it didn't take effect.")
            }

            updateStatus(report.toString())
        } catch (t: Throwable) {
            updateStatus("Fatal: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Parses the "Global session refs:" block, which lists every audio session
     * currently held by any app in AudioFlinger.
     *
     * Example format:
     *   Global session refs:
     *     session  cnt   pid    uid  name
     *     1713     2     9469   10212  in.krosbits.musicolet
     */
    private fun parseGlobalSessionRefs(output: String): List<Int> {
        val sessions = mutableListOf<Int>()
        val lines = output.lines()
        var inRefs = false
        var headerSeen = false

        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("Global session refs", true)) {
                inRefs = true
                headerSeen = false
                continue
            }
            if (!inRefs) continue
            if (t.startsWith("Output thread", true) ||
                t.startsWith("Input thread", true) ||
                t.startsWith("Hardware status", true) ||
                t.startsWith("Standby Time", true) ||
                t.startsWith("mHifiState", true) ||
                t.startsWith("mLowPower", true)
            ) break

            if (t.startsWith("session", true) && t.contains("cnt", true)) {
                headerSeen = true
                continue
            }
            if (headerSeen && t.isNotEmpty()) {
                val parts = t.split(Regex("""\s+"""))
                if (parts.size >= 5) {
                    parts[0].toIntOrNull()?.let { if (it > 0) sessions.add(it) }
                }
            }
        }
        return sessions.distinct()
    }

    private fun parseTrackTables(output: String): List<Int> {
        val sessions = mutableListOf<Int>()
        val lines = output.lines()
        var inSection = false
        var sessionCol = -1
        var activeCol = -1
        var sCol = -1

        for (line in lines) {
            val t = line.trim()
            if (t.contains("Tracks of which", true) && t.contains("are active", true)) {
                val m = Regex("""(\d+)\s+Tracks of which\s+(\d+)\s+are active""").find(t)
                val a = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
                inSection = a > 0
                sessionCol = -1; activeCol = -1; sCol = -1
                continue
            }
            if (!inSection) continue
            if (sessionCol == -1 &&
                t.contains("Session", true) &&
                t.contains("Active", true) &&
                t.contains("Id", true)
            ) {
                val headers = t.split(Regex("""\s+"""))
                sessionCol = headers.indexOfFirst { it.equals("Session", true) }
                activeCol = headers.indexOfFirst { it.equals("Active", true) }
                sCol = headers.indexOfFirst { it.equals("S", true) }
                continue
            }
            if (t.isBlank() ||
                t.startsWith("Output thread", true) ||
                t.startsWith("Input thread", true)
            ) { inSection = false; continue }

            if (sessionCol >= 0) {
                val cols = t.split(Regex("""\s+"""))
                if (cols.size > maxOf(sessionCol, activeCol, sCol)) {
                    val active =
                        (activeCol >= 0 && cols[activeCol].equals("yes", true)) ||
                        (sCol >= 0 && cols[sCol].equals("A", true))
                    if (active) {
                        cols[sessionCol].toIntOrNull()?.let { if (it > 0) sessions.add(it) }
                    }
                }
            }
        }
        return sessions.distinct()
    }

    private fun tryAttach(sessionId: Int, gainDb: Float): Boolean {
        return try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,      // stereo
                true,   // pre-EQ used
                1,      // 1 pre-EQ band
                false,  // no MBC
                0,
                false,  // no post-EQ
                0,
                false   // no limiter
            ).build()

            val effect = DynamicsProcessing(0, sessionId, config)
            effect.setInputGainAllChannelsTo(gainDb)

            // Verify the effect actually got enabled
            if (!effect.enabled) {
                Log.w(TAG, "Effect attached to $sessionId but not enabled")
            }
            effects.add(effect)
            Log.d(TAG, "Attached $gainDb dB to session $sessionId (enabled=${effect.enabled})")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Attach failed for session $sessionId: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun runShizukuCommandWithOutput(command: Array<String>): String? {
        return try {
            val process = newShizukuProcess(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val out = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) out.append(line).append("\n")
            reader.close()
            process.waitFor()
            out.toString()
        } catch (t: Throwable) {
            Log.e(TAG, "Shizuku exec failed", t); null
        }
    }

    private fun newShizukuProcess(command: Array<String>): ShizukuRemoteProcess {
        val c = Class.forName("rikka.shizuku.Shizuku")
        val m = c.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, command, null, null) as ShizukuRemoteProcess
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Volume PoC", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume PoC")
            .setContentText("Attaching effects…")
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
