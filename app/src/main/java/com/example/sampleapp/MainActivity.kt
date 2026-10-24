package com.example.sampleapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private val TAG = "ShellMuteTest"
    private val NOTIFICATION_PERMISSION_REQUEST = 1002

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusText.text = "Shizuku binder alive: ${Shizuku.pingBinder()}\n\n" +
            "Start music in Musicolet, then tap one button at a time.\n" +
            "After each tap, LISTEN: did the music drop to silence or get quieter?\n\n" +
            "Report back which buttons worked (if any)."

        findViewById<Button>(R.id.cmdMediaSession).setOnClickListener {
            runShellTest("cmd media_session volume --stream 3 --set 0")
        }
        findViewById<Button>(R.id.cmdAudio).setOnClickListener {
            runShellTest("cmd audio set-volume --stream 3 --index 0")
        }
        findViewById<Button>(R.id.cmdSettings).setOnClickListener {
            runShellTest("settings put system volume_music 0")
        }
        findViewById<Button>(R.id.cmdServiceCall).setOnClickListener {
            // Raw binder: setStreamVolume(stream=3, index=0, flags=0, callingPackage=null, uid=2000)
            runShellTest("service call audio 10 i32 3 i32 0 i32 0 i32 0 s16 com.android.shell i32 2000")
        }

        requestNotificationPermissionIfNeeded()
    }

    private fun runShellTest(command: String) {
        if (!Shizuku.pingBinder()) {
            statusText.text = "Shizuku binder not alive. Start Shizuku first."
            return
        }
        statusText.text = "Running:\n$command\n\nWaiting for result…"
        Thread {
            try {
                val args = command.split(" ").toTypedArray()
                val proc = newShizukuProcess(args)

                val out = BufferedReader(InputStreamReader(proc.inputStream))
                    .bufferedLines()
                val err = BufferedReader(InputStreamReader(proc.errorStream))
                    .bufferedLines()
                val exit = proc.waitFor()

                val report = buildString {
                    appendLine("Command:")
                    appendLine(command)
                    appendLine()
                    appendLine("exit = $exit")
                    appendLine()
                    appendLine("stdout:")
                    appendLine(if (out.isBlank()) "(empty)" else out)
                    appendLine()
                    appendLine("stderr:")
                    appendLine(if (err.isBlank()) "(empty)" else err)
                    appendLine()
                    appendLine("▶ Did the music drop / go quieter? Note which button, then report back.")
                }

                runOnUiThread { statusText.text = report }
            } catch (t: Throwable) {
                runOnUiThread {
                    statusText.text = "Exception running:\n$command\n\n" +
                        "${t.javaClass.simpleName}: ${t.message}"
                }
            }
        }.start()
    }

    private fun BufferedReader.bufferedLines(): String =
        useLines { it.joinToString("\n") }

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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }
    }
}
