package com.example.sampleapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
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

    private val dpEffects = mutableListOf<DynamicsProcessing>()
    private val leEffects = mutableListOf<LoudnessEnhancer>()

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
            updateStatus("Attaching effect to GLOBAL mix (session 0)…")

            // Release previous effects
            dpEffects.forEach { runCatching { it.release() } }
            dpEffects.clear()
            leEffects.forEach { runCatching { it.release() } }
            leEffects.clear()

            val gainDb = -10f

            // ── PRIMARY: DynamicsProcessing on session 0 ──
            val dpOk = tryAttachDp(0, gainDb)
            if (dpOk) {
                report.appendLine("✓ DynamicsProcessing on session 0")
            } else {
                report.appendLine("✗ DynamicsProcessing on session 0 FAILED")
            }

            // ── FALLBACK: LoudnessEnhancer on session 0 ──
            if (!dpOk) {
                val leOk = tryAttachLe(0, gainDb)
                if (leOk) {
                    report.appendLine("✓ LoudnessEnhancer on session 0 (fallback)")
                } else {
                    report.appendLine("✗ LoudnessEnhancer on session 0 FAILED")
                }
            }

            report.appendLine()
            report.appendLine("▶ Listen for the volume drop NOW.")
            report.appendLine("If it worked, this proves the global-mix approach.")
            report.appendLine("If it did NOT work, Funtouch is bypassing")
            report.appendLine("effects on the global mix entirely.")

            updateStatus(report.toString())
        } catch (t: Throwable) {
            updateStatus("Fatal: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun tryAttachDp(sessionId: Int, gainDb: Float): Boolean {
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
            dpEffects.add(effect)
            Log.d(TAG, "DP attached to session $sessionId, enabled=${effect.enabled}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "DP attach failed for $sessionId: ${t.message}", t)
            false
        }
    }

    private fun tryAttachLe(sessionId: Int, gainDb: Float): Boolean {
        return try {
            val le = LoudnessEnhancer(sessionId)
            // LoudnessEnhancer takes gain in millibels (mB).
            // Negative values attenuate; the effective range is roughly -1000 to +1000.
            le.setTargetGain((gainDb * 100).toInt())
            le.enabled = true
            leEffects.add(le)
            Log.d(TAG, "LE attached to session $sessionId, enabled=${le.enabled}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "LE attach failed for $sessionId: ${t.message}", t)
            false
        }
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
            .setContentText("Applying global mix gain…")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        dpEffects.forEach { runCatching { it.release() } }
        dpEffects.clear()
        leEffects.forEach { runCatching { it.release() } }
        leEffects.clear()
    }
}
