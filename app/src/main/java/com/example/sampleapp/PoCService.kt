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
        Thread { runDiagnostic() }.start()
        return START_STICKY
    }

    private fun updateStatus(msg: String) {
        Log.d(TAG, msg)
        currentStatus = msg
        statusCallback?.invoke(msg)
    }

    private fun runDiagnostic() {
        try {
            val report = StringBuilder()

            val flingerDump = runShizukuCommandWithOutput(arrayOf("dumpsys", "media.audio_flinger"))
            val audioDump = runShizukuCommandWithOutput(arrayOf("dumpsys", "audio"))

            // ── FLINGER ──
            report.appendLine("═══ AUDIO FLINGER ═══")
            if (flingerDump.isNullOrBlank()) {
                report.appendLine("(null/empty)")
            } else {
                val lines = flingerDump.lines()
                report.appendLine("${lines.size} lines")
                report.appendLine()
                report.appendLine("Threads:")
                var threadCount = 0
                for (i in lines.indices) {
                    val t = lines[i].trim()
                    if (t.startsWith("Output thread", true) || t.startsWith("Input thread", true)) {
                        threadCount++
                        val nameMatch = Regex("""name\s+(\w+)""").find(t)
                        val name = nameMatch?.groupValues?.get(1) ?: "?"
                        var countStr = "?"
                        for (j in i + 1 until minOf(i + 500, lines.size)) {
                            val lt = lines[j].trim()
                            if (lt.startsWith("Output thread", true) || lt.startsWith("Input thread", true)) break
                            if (lt.contains("Tracks of which", true)) {
                                countStr = lt
                                break
                            }
                        }
                        report.appendLine("  [$name] $countStr")
                    }
                }
                if (threadCount == 0) report.appendLine("  (none)")
                report.appendLine()

                report.appendLine("Active sessions (from tracks):")
                val sess = findAllActiveSessionIds(flingerDump)
                report.appendLine("  $sess")
                report.appendLine()

                report.appendLine("Global session refs:")
                var inRefs = false
                var foundRefs = false
                for (line in lines) {
                    val t = line.trim()
                    if (t.startsWith("Global session refs", true)) { inRefs = true; continue }
                    if (inRefs) {
                        if (t.startsWith("Output thread", true) || t.startsWith("Input thread", true)) break
                        if (t.isNotEmpty()) { report.appendLine("  $t"); foundRefs = true }
                    }
                }
                if (!foundRefs) report.appendLine("  (empty)")
            }
            report.appendLine()

            // ── AUDIO MANAGER ──
            report.appendLine("═══ DUMPSYS AUDIO ═══")
            if (audioDump.isNullOrBlank()) {
                report.appendLine("(null/empty)")
            } else {
                val lines = audioDump.lines()
                report.appendLine("${lines.size} lines")
                report.appendLine("Players (piid/sessionId):")
                val playerLines = lines.filter {
                    it.contains("Player piid:", true) ||
                    (it.contains("sessionId:", true) && it.contains("state:", true))
                }
                if (playerLines.isEmpty()) {
                    report.appendLine("  (none)")
                } else {
                    playerLines.take(25).forEach { report.appendLine("  " + it.trim()) }
                    if (playerLines.size > 25) report.appendLine("  ...(${playerLines.size - 25} more)")
                }
                report.appendLine()
                report.appendLine("All sessionIds mentioned:")
                val idRegex = Regex("""sessionId[:=]\s*(\d+)""")
                val allIds = mutableSetOf<Int>()
                lines.forEach { l -> idRegex.findAll(l).forEach { m ->
                    m.groupValues[1].toIntOrNull()?.let { if (it > 0) allIds.add(it) }
                } }
                report.appendLine("  ${allIds.sorted()}")
            }

            updateStatus(report.toString())
        } catch (t: Throwable) {
            updateStatus("Fatal: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun findAllActiveSessionIds(dumpsysOutput: String): List<Int> {
        val sessionIds = mutableListOf<Int>()
        val lines = dumpsysOutput.lines()
        var inSection = false
        var sessionCol = -1
        var activeCol = -1
        var sCol = -1

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.contains("Tracks of which", true) &&
                line.contains("are active", true)
            ) {
                val m = Regex("""(\d+)\s+Tracks of which\s+(\d+)\s+are active""").find(line)
                val a = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
                inSection = a > 0
                sessionCol = -1; activeCol = -1; sCol = -1
                continue
            }
            if (!inSection) continue
            if (sessionCol == -1 &&
                line.contains("Session", true) &&
                line.contains("Active", true) &&
                line.contains("Id", true)
            ) {
                val headers = line.split(Regex("""\s+"""))
                sessionCol = headers.indexOfFirst { it.equals("Session", true) }
                activeCol = headers.indexOfFirst { it.equals("Active", true) }
                sCol = headers.indexOfFirst { it.equals("S", true) }
                continue
            }
            if (line.isBlank() ||
                line.startsWith("Output thread", true) ||
                line.startsWith("Input thread", true)
            ) { inSection = false; continue }

            if (sessionCol >= 0) {
                val cols = line.split(Regex("""\s+"""))
                if (cols.size > maxOf(sessionCol, activeCol, sCol)) {
                    val active = (activeCol >= 0 && cols[activeCol].equals("yes", true)) ||
                                 (sCol >= 0 && cols[sCol].equals("A", true))
                    if (active) cols[sessionCol].toIntOrNull()?.let { if (it > 0) sessionIds.add(it) }
                }
            }
        }
        return sessionIds.distinct()
    }

    private fun applyGainToSession(sessionId: Int, gainDb: Float): Boolean {
        return try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, true, 1, false, 0, false, 0, false
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
            .setContentText("Diagnostic running…")
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
