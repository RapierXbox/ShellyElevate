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

    companion object {
        private const val SP_CRASH_COUNT = "crashHandlerCount"
        private const val SP_CRASH_LAST = "crashHandlerLastMs"
        private const val CRASH_WINDOW_MS = 60_000L
        private const val MAX_RAPID_CRASHES = 5
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val logFile = File(context.filesDir, "crash_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = "\n=== Crash @ $timestamp ===\n${Log.getStackTraceString(throwable)}\n"
            ensureCapacity(logFile, entry.toByteArray().size)
            logFile.appendText(entry)

            Log.e("CrashHandler", "App crashed", throwable)

            if (shouldRelaunch()) {
                // schedule a relaunch in 2s; we cant startActivity directly because
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
            }
        } catch (e: Exception) {
            Log.e("CrashHandler", "Error while handling crash", e)
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }

    private fun shouldRelaunch(): Boolean {
        val prefs = context.getSharedPreferences(Constants.SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

        // lite mode never auto launches the kiosk activity
        if (prefs.getBoolean(Constants.SP_LITE_MODE, false)) return false

        // back off after repeated rapid crashes so a broken startup does not
        // relaunch loop every two seconds forever
        val now = System.currentTimeMillis()
        val last = prefs.getLong(SP_CRASH_LAST, 0L)
        val count = if (now - last < CRASH_WINDOW_MS) prefs.getInt(SP_CRASH_COUNT, 0) + 1 else 1
        // commit because the process dies right after this
        prefs.edit().putLong(SP_CRASH_LAST, now).putInt(SP_CRASH_COUNT, count).commit()

        if (count > MAX_RAPID_CRASHES) {
            Log.e("CrashHandler", "crash loop detected ($count rapid crashes), not relaunching")
            return false
        }
        return true
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
