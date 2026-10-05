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

    // share of the screen the fingers must travel the switcher way before a snapshot is taken
    // so pinches and scrolls on the dashboard never pay for a screencap
    private const val SNAPSHOT_TRIGGER_FRACTION = 0.06f

    // "0003 0035 000001f4"
    private const val RAW_LINE_LENGTH = 18

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracker = MultiTouchTracker(this)

    private lateinit var appContext: Context
    private var touchscreen: InputMonitor.Touchscreen? = null
    private var monitor: InputMonitor? = null
    private var geteventProcess: Process? = null

    @Volatile
    private var lastPingMs = 0L

    // switcher gesture of the running gesture read once when it starts. only touched on the reader thread
    private var snapshotFingers = 0
    private var snapshotDirection: SwipeClassifier.Direction? = null
    private val meanDelta = FloatArray(2)

    // the panel never rotates so the size is read once
    private val screenSize by lazy {
        val metrics = DisplayMetrics()
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        floatArrayOf(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    }

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
        maybeCaptureSnapshot()
        val now = SystemClock.uptimeMillis()
        if (now - lastPingMs < ACTIVITY_PING_INTERVAL_MS) return
        lastPingMs = now
        mainHandler.post {
            // our own views reset the idle timer themselves
            if (!ForegroundActivities.anyResumed()) mScreenSaverManager?.onTouchEvent(null)
        }
    }

    override fun onGestureStart(pointers: Int) {
        val gesture = SwipeActions.switcherGesture()
        snapshotFingers = 0
        snapshotDirection = null
        if (gesture == APP_SWITCHER_GESTURE_OFF) return
        // swipe_<fingers>_<direction>
        val parts = gesture.split('_')
        if (parts.size != 3) return
        snapshotFingers = parts[1].toIntOrNull() ?: 0
        snapshotDirection = SwipeClassifier.Direction.entries.firstOrNull { it.name.equals(parts[2], ignoreCase = true) }
    }

    // grabs the screen once the gesture clearly heads the switcher way so the card is ready when it opens
    private fun maybeCaptureSnapshot() {
        val direction = snapshotDirection ?: return
        val screen = touchscreen ?: return
        if (tracker.liveCount != snapshotFingers) return
        tracker.liveMeanDelta(meanDelta)
        val dx = meanDelta[0] / (screen.maxX - screen.minX)
        val dy = meanDelta[1] / (screen.maxY - screen.minY)
        val travelled = when (direction) {
            SwipeClassifier.Direction.UP -> -dy
            SwipeClassifier.Direction.DOWN -> dy
            SwipeClassifier.Direction.LEFT -> -dx
            SwipeClassifier.Direction.RIGHT -> dx
        }
        if (travelled < SNAPSHOT_TRIGGER_FRACTION) return
        // once per gesture
        snapshotDirection = null
        SnapshotStore.capture(appContext)
    }

    override fun onGestureEnd(gesture: MultiTouchTracker.Gesture) {
        snapshotDirection = null
        val screen = touchscreen ?: return
        // our own views already handle this gesture
        if (ForegroundActivities.anyResumed()) return
        val w = screenSize[0]
        val h = screenSize[1]
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

    // parses a hex run without allocating. -1 for a bad digit which only matters for type and code
    private fun hex(line: String, from: Int, to: Int): Int {
        var result = 0
        for (i in from until to) {
            val digit = Character.digit(line[i], 16)
            if (digit < 0) return -1
            result = (result shl 4) or digit
        }
        return result
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
                    // fixed columns so no split or regex runs per event
                    if (line.length < RAW_LINE_LENGTH || line[4] != ' ' || line[9] != ' ') continue
                    val type = hex(line, 0, 4)
                    val code = hex(line, 5, 9)
                    if (type < 0 || code < 0) continue
                    // values are 32 bit two complement so a released tracking id reads ffffffff
                    val value = hex(line, 10, 18)
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
