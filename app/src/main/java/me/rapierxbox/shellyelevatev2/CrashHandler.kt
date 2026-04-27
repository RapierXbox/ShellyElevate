package me.rapierxbox.shellyelevatev2

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val maxBytes = 512 * 1024

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val logFile = File(context.filesDir, "crash_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = "\n=== Crash @ $timestamp ===\n${Log.getStackTraceString(throwable)}\n"
            ensureCapacity(logFile, entry.toByteArray().size)
            logFile.appendText(entry)

            Log.e("CrashHandler", "App crashed", throwable)

            // Schedule a relaunch in 2s; we can't startActivity directly because
            // the process is about to die.
            val restartIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 2000, pendingIntent)
        } catch (e: Exception) {
            Log.e("CrashHandler", "Error while handling crash", e)
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }

    private fun ensureCapacity(logFile: File, incomingBytes: Int) {
        // Rotate to a single .prev backup when the next entry would exceed maxBytes.
        val currentSize = if (logFile.exists()) logFile.length() else 0L
        if (currentSize + incomingBytes <= maxBytes) return

        val backup = File(logFile.parentFile, "crash_log.prev.txt")
        runCatching { if (backup.exists()) backup.delete() }
        runCatching { logFile.copyTo(backup, overwrite = true) }
        runCatching { logFile.delete() }
    }
}
