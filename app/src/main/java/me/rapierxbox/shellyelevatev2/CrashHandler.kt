package me.rapierxbox.shellyelevatev2

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // log first so the trace reaches logcat even if writing the file fails
        Log.e(TAG, "App crashed", throwable)
        try {
            appendToCrashLog(throwable)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing crash log", e)
        }
        try {
            if (shouldRelaunch()) scheduleRelaunch()
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling relaunch", e)
        } finally {
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    private fun appendToCrashLog(throwable: Throwable) {
        val logFile = File(context.filesDir, CRASH_LOG_NAME)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "\n=== Crash @ $timestamp ===\n${Log.getStackTraceString(throwable)}\n"
        rotateIfNeeded(logFile, entry.toByteArray().size)
        logFile.appendText(entry)
    }

    // the process is about to die so startActivity would go nowhere and an alarm relaunches us instead
    private fun scheduleRelaunch() {
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
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + RELAUNCH_DELAY_MS, pendingIntent)
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
        // commit since the process dies right after this
        prefs.edit().putLong(SP_CRASH_LAST, now).putInt(SP_CRASH_COUNT, count).commit()

        if (count > MAX_RAPID_CRASHES) {
            Log.e(TAG, "crash loop detected ($count rapid crashes), not relaunching")
            return false
        }
        return true
    }

    // keeps a single previous file once the next entry would push the log past the cap
    private fun rotateIfNeeded(logFile: File, incomingBytes: Int) {
        val currentSize = if (logFile.exists()) logFile.length() else 0L
        if (currentSize + incomingBytes <= MAX_LOG_BYTES) return

        val backup = File(logFile.parentFile, CRASH_LOG_PREV_NAME)
        runCatching { logFile.copyTo(backup, overwrite = true) }
        runCatching { logFile.delete() }
    }

    companion object {
        private const val TAG = "CrashHandler"

        private const val CRASH_LOG_NAME = "crash_log.txt"
        private const val CRASH_LOG_PREV_NAME = "crash_log.prev.txt"
        private const val MAX_LOG_BYTES = 512 * 1024

        private const val SP_CRASH_COUNT = "crashHandlerCount"
        private const val SP_CRASH_LAST = "crashHandlerLastMs"
        private const val CRASH_WINDOW_MS = 60_000L
        private const val MAX_RAPID_CRASHES = 5
        private const val RELAUNCH_DELAY_MS = 2000L
    }
}
