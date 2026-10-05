package me.rapierxbox.shellyelevatev2.helper.touch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.edit
import me.rapierxbox.shellyelevatev2.Constants.APP_SWITCHER_GESTURE_OFF
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.helper.ForegroundActivities
import me.rapierxbox.shellyelevatev2.helper.InputMonitor
import me.rapierxbox.shellyelevatev2.helper.PrivilegedShell
import me.rapierxbox.shellyelevatev2.switcher.SnapshotStore
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

// reads the touchscreen straight from /dev/input so swipes work over any app in front
// while one of our activities is resumed its own views handle touches through SwipeHelper
// so nothing fires twice
// readers are tried from cheapest to most invasive: native read, native read after a chmod,
// getevent, getevent through su and finally an edge strip overlay that needs no input access at all
object TouchGestureMonitor : InputMonitor.TouchCallback, MultiTouchTracker.Listener {
    private const val TAG = "TouchGestureMonitor"

    // keeps the screensaver idle timer fed without a main thread post per frame
    private const val ACTIVITY_PING_INTERVAL_MS = 1000L

    // share of the screen the fingers must travel the switcher way before a snapshot is taken
    // so pinches and scrolls on the dashboard never pay for a screencap
    private const val SNAPSHOT_TRIGGER_FRACTION = 0.06f

    // a raw gesture and an in app gesture ending this close together are the same touch
    private const val CALIBRATION_MATCH_MS = 300L

    // "0003 0035 000001f4"
    private const val RAW_LINE_LENGTH = 18

    private const val PREFS = "AppSwitcher"
    private const val KEY_AXIS_MAP = "touchAxisMap"
    private const val SHELL_TIMEOUT_MS = 4000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracker = MultiTouchTracker(this)

    private lateinit var appContext: Context
    private var touchscreen: InputMonitor.Touchscreen? = null
    private var monitor: InputMonitor? = null

    @Volatile
    private var axisMap = TouchAxisMap()

    @Volatile
    private var lastPingMs = 0L

    // switcher gesture of the running gesture read once when it starts. only touched on the reader thread
    private var snapshotFingers = 0
    private var snapshotDirection: SwipeClassifier.Direction? = null
    private val meanDelta = FloatArray(2)
    private val mapped = FloatArray(2)

    // last one finger gesture from each side for calibration. guarded by the calibration lock
    private val calibrationLock = Any()
    private var rawSample: FloatArray? = null
    private var rawSampleMs = 0L
    private var screenSample: FloatArray? = null
    private var screenSampleMs = 0L

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

    // which reader is active. reported by the http api so a broken setup can be diagnosed remotely
    @Volatile
    @JvmStatic
    var status = "starting"
        private set

    @JvmStatic
    fun start(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        // device discovery touches /dev/input and may shell out so keep it off the main thread
        Thread({
            try {
                startReader()
            } catch (t: Throwable) {
                status = "failed: ${t.message}"
                Log.e(TAG, "touch reader failed to start", t)
            }
        }, "TouchReaderStart").start()
    }

    private fun startReader() {
        if (startNative()) return

        val getevent = findWithGetevent(emptyList())
        if (getevent != null) {
            useTouchscreen(getevent)
            startGetevent(emptyList(), getevent.path, "getevent")
            return
        }

        val su = suPrefix()
        if (su != null) {
            val viaSu = findWithGetevent(su)
            if (viaSu != null) {
                useTouchscreen(viaSu)
                startGetevent(su, viaSu.path, "su getevent")
                return
            }
        }

        status = "edge strip, touchscreen not readable"
        Log.w(TAG, "no readable touchscreen so swipes over other apps use the edge strip")
        mainHandler.post { EdgeSwipeOverlay.enable(appContext) }
    }

    // opens the node from our own process. a denied node gets one chmod attempt through the shell
    private fun startNative(): Boolean {
        if (!InputMonitor.isAvailable()) return false
        val nodes = File("/dev/input").list()?.filter { it.startsWith("event") }?.sorted() ?: return false
        var denied = emptyList<String>()
        for (attempt in 0..1) {
            val locked = ArrayList<String>()
            for (node in nodes) {
                val path = "/dev/input/$node"
                val probe = InputMonitor.probe(path) ?: return false
                val screen = probe.touchscreen
                if (screen != null) {
                    val m = InputMonitor()
                    if (!m.startTouch(this, path)) continue
                    monitor = m
                    useTouchscreen(screen)
                    isRunning = true
                    status = if (attempt == 0) "native $path" else "native $path after chmod"
                    Log.i(TAG, "reading touches natively from $screen")
                    return true
                }
                if (probe.permissionDenied()) locked += path
            }
            denied = locked
            if (locked.isEmpty() || attempt == 1) break
            // reading is all we need so only other read is added
            val result = PrivilegedShell.runShell("chmod o+r ${locked.joinToString(" ")}")
            Log.i(TAG, "chmod on ${locked.size} locked input nodes exit ${result.exitCode}")
        }
        if (denied.isNotEmpty()) Log.w(TAG, "input nodes not readable: $denied")
        return false
    }

    private fun useTouchscreen(screen: InputMonitor.Touchscreen) {
        touchscreen = screen
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        axisMap = if (prefs.contains(KEY_AXIS_MAP)) {
            TouchAxisMap.decode(prefs.getInt(KEY_AXIS_MAP, 0))
        } else {
            TouchAxisMap.guess(screen.maxX - screen.minX, screen.maxY - screen.minY,
                screenSize[0].toInt(), screenSize[1].toInt())
        }
        Log.i(TAG, "touch axis map ${axisMap.encode()}")
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
        axisMap.mapDelta(meanDelta[0] / (screen.maxX - screen.minX), meanDelta[1] / (screen.maxY - screen.minY), mapped)
        val travelled = when (direction) {
            SwipeClassifier.Direction.UP -> -mapped[1]
            SwipeClassifier.Direction.DOWN -> mapped[1]
            SwipeClassifier.Direction.LEFT -> -mapped[0]
            SwipeClassifier.Direction.RIGHT -> mapped[0]
        }
        if (travelled < SNAPSHOT_TRIGGER_FRACTION) return
        // once per gesture
        snapshotDirection = null
        SnapshotStore.capture(appContext)
    }

    override fun onGestureEnd(gesture: MultiTouchTracker.Gesture) {
        snapshotDirection = null
        val screen = touchscreen ?: return
        val spanX = (screen.maxX - screen.minX).toFloat()
        val spanY = (screen.maxY - screen.minY).toFloat()

        if (gesture.maxPointers == 1 && gesture.tracks.size == 1) {
            val t = gesture.tracks[0]
            offerRawSample((t.endX - t.startX) / spanX, (t.endY - t.startY) / spanY, gesture.endMs)
        }
        // our own views already handle this gesture
        if (ForegroundActivities.anyResumed()) return

        val w = screenSize[0]
        val h = screenSize[1]
        val map = axisMap
        val start = FloatArray(2)
        val end = FloatArray(2)
        val tracks = gesture.tracks.map {
            map.map((it.startX - screen.minX) / spanX, (it.startY - screen.minY) / spanY, start)
            map.map((it.endX - screen.minX) / spanX, (it.endY - screen.minY) / spanY, end)
            SwipeClassifier.Track(start[0] * w, start[1] * h, end[0] * w, end[1] * h)
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

    // calibration

    // one finger gesture our own screens saw in screen pixels. main thread
    @JvmStatic
    fun onScreenGesture(dxPx: Float, dyPx: Float, upTimeMs: Long) {
        if (touchscreen == null) return
        synchronized(calibrationLock) {
            screenSample = floatArrayOf(dxPx / screenSize[0], dyPx / screenSize[1])
            screenSampleMs = upTimeMs
            tryCalibrate()
        }
    }

    private fun offerRawSample(du: Float, dv: Float, endMs: Long) {
        synchronized(calibrationLock) {
            rawSample = floatArrayOf(du, dv)
            rawSampleMs = endMs
            tryCalibrate()
        }
    }

    // both pipelines report the same touch at nearly the same time in whichever order
    private fun tryCalibrate() {
        val raw = rawSample ?: return
        val screen = screenSample ?: return
        if (kotlin.math.abs(rawSampleMs - screenSampleMs) > CALIBRATION_MATCH_MS) return
        rawSample = null
        screenSample = null
        val map = axisMap
        if (map.learn(raw[0], raw[1], screen[0], screen[1])) {
            Log.i(TAG, "touch axis map learned ${map.encode()}")
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_AXIS_MAP, map.encode()) }
        }
    }

    // getevent

    // a working su prefix or null. both the common su -c form and the aosp su uid form are tried
    private fun suPrefix(): List<String>? {
        for (prefix in listOf(listOf("su", "-c"), listOf("su", "0"))) {
            val out = runQuick(prefix + "id") ?: continue
            if (out.contains("uid=0")) return prefix
        }
        return null
    }

    // parses getevent -pl which lists every readable node with its axes and ranges
    private fun findWithGetevent(prefix: List<String>): InputMonitor.Touchscreen? {
        val command = if (prefix.isEmpty()) listOf("getevent", "-pl") else prefix + "getevent -pl"
        val lines = runQuick(command)?.lines() ?: return null
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

    // output of a short command or null when it failed or hung. su may pop a prompt so it is time boxed
    private fun runQuick(command: List<String>): String? {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return null
        }
        val out = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
            } catch (_: IOException) {
            }
        }
        reader.start()
        val deadline = SystemClock.uptimeMillis() + SHELL_TIMEOUT_MS
        var exit: Int? = null
        while (exit == null && SystemClock.uptimeMillis() < deadline) {
            try {
                exit = process.exitValue()
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(50)
            }
        }
        if (exit == null) {
            process.destroy()
            return null
        }
        reader.join(500)
        return out.toString()
    }

    private fun startGetevent(prefix: List<String>, path: String, label: String) {
        val command = if (prefix.isEmpty()) listOf("getevent", path) else prefix + "getevent $path"
        Thread({ runGetevent(command, label) }, "TouchReaderGetevent").start()
    }

    // raw getevent prints "0003 0035 000001f4" per event when given a single node
    private fun runGetevent(command: List<String>, label: String) {
        var process: Process? = null
        try {
            process = ProcessBuilder(command).redirectErrorStream(true).start()
            isRunning = true
            status = label
            Log.i(TAG, "reading touches through $label from $touchscreen")
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
            Log.w(TAG, "$label touch reader stopped: ${e.message}")
        } finally {
            isRunning = false
            status = "$label stopped, edge strip"
            process?.destroy()
            // the edge strip keeps at least the switcher reachable over other apps
            mainHandler.post { EdgeSwipeOverlay.enable(appContext) }
        }
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
}
