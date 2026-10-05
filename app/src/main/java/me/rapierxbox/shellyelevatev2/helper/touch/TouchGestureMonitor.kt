package me.rapierxbox.shellyelevatev2.helper.touch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import me.rapierxbox.shellyelevatev2.Constants.APP_SWITCHER_GESTURE_OFF
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.helper.ForegroundActivities
import me.rapierxbox.shellyelevatev2.helper.InputMonitor
import me.rapierxbox.shellyelevatev2.switcher.SnapshotStore
import java.io.BufferedReader
import java.io.InputStreamReader

// reads the touchscreen straight from /dev/input so swipes work over any app in front
// while one of our activities is resumed its own views handle touches through SwipeHelper
// so nothing fires twice
object TouchGestureMonitor : InputMonitor.TouchCallback, MultiTouchTracker.Listener {
    private const val TAG = "TouchGestureMonitor"

    // keeps the screensaver idle timer fed without a main thread post per frame
    private const val ACTIVITY_PING_INTERVAL_MS = 1000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracker = MultiTouchTracker(this)

    private lateinit var appContext: Context
    private var touchscreen: InputMonitor.Touchscreen? = null
    private var monitor: InputMonitor? = null
    private var geteventProcess: Process? = null

    @Volatile
    private var lastPingMs = 0L

    @Volatile
    @JvmStatic
    var isRunning = false
        private set

    @JvmStatic
    fun start(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        // device discovery touches /dev/input so keep it off the main thread
        Thread({
            try {
                startReader()
            } catch (t: Throwable) {
                Log.e(TAG, "touch reader failed to start", t)
            }
        }, "TouchReaderStart").start()
    }

    private fun startReader() {
        val native = InputMonitor.findTouchscreen()
        if (native != null) {
            touchscreen = native
            val m = InputMonitor()
            if (m.startTouch(this, native.path)) {
                monitor = m
                isRunning = true
                Log.i(TAG, "reading touches natively from $native")
                return
            }
        }
        // the app process may not be allowed to open the node while the elevated exec context is
        val viaGetevent = findTouchscreenWithGetevent() ?: run {
            Log.w(TAG, "no readable touchscreen so swipes only work inside the app")
            return
        }
        touchscreen = viaGetevent
        Thread({ runGetevent(viaGetevent.path) }, "TouchReaderGetevent").start()
    }

    // called on the native reader thread
    override fun onTouchEvents(events: IntArray, count: Int) {
        val now = SystemClock.uptimeMillis()
        for (i in 0 until count) {
            tracker.onEvent(events[i * 3], events[i * 3 + 1], events[i * 3 + 2], now)
        }
    }

    override fun onTouchActivity() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPingMs < ACTIVITY_PING_INTERVAL_MS) return
        lastPingMs = now
        mainHandler.post {
            // our own views reset the idle timer themselves
            if (!ForegroundActivities.anyResumed()) mScreenSaverManager?.onTouchEvent(null)
        }
    }

    override fun onGestureStart(pointers: Int) {
        // grab the screen early so the switcher can show it the moment the swipe ends
        val gesture = SwipeActions.switcherGesture()
        if (gesture == APP_SWITCHER_GESTURE_OFF) return
        if (gesture.startsWith("swipe_${pointers}_")) SnapshotStore.capture(appContext)
    }

    override fun onGestureEnd(gesture: MultiTouchTracker.Gesture) {
        val screen = touchscreen ?: return
        val metrics = displayMetrics()
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val spanX = (screen.maxX - screen.minX).toFloat()
        val spanY = (screen.maxY - screen.minY).toFloat()
        val tracks = gesture.tracks.map {
            SwipeClassifier.Track(
                (it.startX - screen.minX) / spanX * w, (it.startY - screen.minY) / spanY * h,
                (it.endX - screen.minX) / spanX * w, (it.endY - screen.minY) / spanY * h
            )
        }
        val duration = gesture.endMs - if (gesture.maxPointers > 1) gesture.lastJoinMs else gesture.startMs
        val swipe = SwipeClassifier.classify(tracks, gesture.maxPointers, duration, minOf(w, h) / 3f)

        mainHandler.post {
            if (ForegroundActivities.anyResumed()) return@post
            if (swipe != null) {
                SwipeActions.dispatch(swipe)
            } else {
                // a tap over another app wakes the screen just like a tap on the dashboard
                mScreenManager?.onTouchEvent()
            }
        }
    }

    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    // parses getevent -pl which lists every node with its axes and ranges
    private fun findTouchscreenWithGetevent(): InputMonitor.Touchscreen? {
        val lines = try {
            val process = ProcessBuilder("getevent", "-pl").redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            out
        } catch (e: Exception) {
            Log.w(TAG, "getevent -pl failed: ${e.message}")
            return null
        }
        var device: String? = null
        var x: IntArray? = null
        var y: IntArray? = null
        for (line in lines + "add device end") {
            val trimmed = line.trim()
            if (trimmed.startsWith("add device")) {
                if (device != null && x != null && y != null) {
                    return InputMonitor.Touchscreen(device, x[0], x[1], y[0], y[1])
                }
                device = trimmed.substringAfter(':').trim().takeIf { it.startsWith("/dev/input") }
                x = null
                y = null
            } else if (trimmed.contains("ABS_MT_POSITION_X")) {
                x = parseRange(trimmed)
            } else if (trimmed.contains("ABS_MT_POSITION_Y")) {
                y = parseRange(trimmed)
            }
        }
        return null
    }

    // "ABS_MT_POSITION_X : value 0, min 0, max 719, fuzz 0, flat 0, resolution 0"
    private fun parseRange(line: String): IntArray? {
        val min = Regex("min (-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val max = Regex("max (-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (max > min) intArrayOf(min, max) else null
    }

    // raw getevent prints "0003 0035 000001f4" per event when given a single node
    private fun runGetevent(path: String) {
        try {
            val process = ProcessBuilder("getevent", path).redirectErrorStream(true).start()
            geteventProcess = process
            isRunning = true
            Log.i(TAG, "reading touches through getevent from $touchscreen")
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size != 3) continue
                    val type = parts[0].toIntOrNull(16) ?: continue
                    val code = parts[1].toIntOrNull(16) ?: continue
                    // values are 32 bit two complement so a released tracking id reads ffffffff
                    val value = parts[2].toLongOrNull(16)?.toInt() ?: continue
                    tracker.onEvent(type, code, value, SystemClock.uptimeMillis())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getevent touch reader stopped: ${e.message}")
        } finally {
            isRunning = false
            geteventProcess?.destroy()
            geteventProcess = null
        }
    }
}
