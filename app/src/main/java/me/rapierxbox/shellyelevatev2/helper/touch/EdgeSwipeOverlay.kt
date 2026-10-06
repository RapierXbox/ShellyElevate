package me.rapierxbox.shellyelevatev2.helper.touch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.SparseArray
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.helper.ForegroundActivities
import me.rapierxbox.shellyelevatev2.helper.PrivilegedShell
import me.rapierxbox.shellyelevatev2.switcher.SnapshotStore
import kotlin.math.abs

// last resort when no touchscreen node can be read. a thin invisible strip along the bottom edge
// catches swipes that start there over any app. one finger up from the edge opens the switcher like
// on a phone and multi finger swipes still go through the shared classifier
// it only exists while none of our own screens is in front since those see every touch themselves
// the strip swallows taps on the app below like its bottom nav bar so it stays thin. the window has
// no split touch so once the first finger lands in it every further finger reaches it too
// it also watches outside touches so using another app keeps the screensaver away
object EdgeSwipeOverlay {
    private const val TAG = "EdgeSwipeOverlay"
    // the panel edge reads poorly so the strip is at least this tall in dp or this share of the
    // screen height whichever is more. any taller and it eats too much of the app below
    private const val STRIP_DP = 20
    private const val STRIP_MIN_FRACTION = 0.035f
    // share of the screen height a one finger swipe from the edge has to travel
    private const val EDGE_TRIGGER_FRACTION = 0.06f
    // or this share when it moves at least this fast
    private const val EDGE_FLICK_FRACTION = 0.03f
    private const val EDGE_FLICK_VELOCITY_PX_MS = 0.3f
    // share of the screen height after which the preview of the app below is taken
    private const val SNAPSHOT_FRACTION = 0.03f
    // activity switches report a short gap with nothing resumed so the strip waits this long
    private const val SHOW_DELAY_MS = 400L
    // the screensaver idle timer is fed at most this often
    private const val IDLE_PING_INTERVAL_MS = 1000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var strip: View? = null
    private var grantTried = false
    private var lastIdlePingMs = 0L

    private val showRunnable = Runnable { strip?.visibility = View.VISIBLE }

    // main thread
    fun enable(context: Context) {
        if (strip != null) return
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            if (grantTried) {
                Log.w(TAG, "overlay permission missing so swipes over other apps are unavailable")
                return
            }
            // the grant normally ran at start already. one more try off the main thread
            grantTried = true
            Thread({
                PrivilegedShell.allowAppOp(app.packageName, "SYSTEM_ALERT_WINDOW")
                mainHandler.post { enable(app) }
            }, "EdgeStripGrant").start()
            return
        }
        val metrics = app.resources.displayMetrics
        val stripPx = maxOf(STRIP_DP * metrics.density, metrics.heightPixels * STRIP_MIN_FRACTION).toInt()
        val view = StripView(app)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            stripPx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        try {
            (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "adding the edge strip failed: ${e.message}")
            return
        }
        strip = view
        view.visibility = if (ForegroundActivities.anyResumed()) View.GONE else View.VISIBLE
        ForegroundActivities.addListener { ownScreenInFront ->
            mainHandler.removeCallbacks(showRunnable)
            if (ownScreenInFront) strip?.visibility = View.GONE else mainHandler.postDelayed(showRunnable, SHOW_DELAY_MS)
        }
        Log.i(TAG, "edge swipe strip active")
    }

    // any touch while another app is in front counts as use. outside touches arrive once per
    // gesture with zeroed coordinates which is all this needs
    private fun onUserTouch() {
        mScreenManager?.onTouchEvent()
        val now = SystemClock.uptimeMillis()
        if (now - lastIdlePingMs < IDLE_PING_INTERVAL_MS) return
        lastIdlePingMs = now
        mScreenSaverManager?.onTouchEvent(null)
    }

    @SuppressLint("ViewConstructor")
    private class StripView(context: Context) : View(context) {
        private class Pointer(val startX: Float, val startY: Float, var endX: Float, var endY: Float)

        private val pointers = SparseArray<Pointer>()
        // a lifted pointer id is handed to the next finger so lifted fingers are kept apart
        private val lifted = ArrayList<Pointer>()
        private var snapshotTaken = false
        private var maxPointers = 0
        private var startMs = 0L
        private var lastJoinMs = 0L
        private val screenH = context.resources.displayMetrics.heightPixels.toFloat()
        private val screenW = context.resources.displayMetrics.widthPixels.toFloat()

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> onUserTouch()
                MotionEvent.ACTION_DOWN -> {
                    onUserTouch()
                    pointers.clear()
                    lifted.clear()
                    add(event, 0)
                    maxPointers = 1
                    snapshotTaken = false
                    startMs = event.eventTime
                    lastJoinMs = 0
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    add(event, event.actionIndex)
                    maxPointers = maxOf(maxPointers, event.pointerCount)
                    lastJoinMs = event.eventTime
                }
                MotionEvent.ACTION_MOVE -> {
                    updateEnds(event)
                    // the app below is still fully visible here so this is the moment for its preview
                    val first = pointers.valueAt(0)
                    if (!snapshotTaken && first != null && first.startY - first.endY > screenH * SNAPSHOT_FRACTION) {
                        snapshotTaken = true
                        SnapshotStore.capture(context)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    updateEnds(event)
                    val id = event.getPointerId(event.actionIndex)
                    pointers.get(id)?.let {
                        lifted += it
                        pointers.remove(id)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    updateEnds(event)
                    finish(event.eventTime)
                    pointers.clear()
                    lifted.clear()
                }
                MotionEvent.ACTION_CANCEL -> {
                    pointers.clear()
                    lifted.clear()
                }
            }
            return true
        }

        private fun updateEnds(event: MotionEvent) {
            for (i in 0 until event.pointerCount) {
                pointers.get(event.getPointerId(i))?.let {
                    it.endX = event.screenX(i)
                    it.endY = event.screenY(i)
                }
            }
        }

        private fun add(event: MotionEvent, index: Int) {
            val x = event.screenX(index)
            val y = event.screenY(index)
            pointers.put(event.getPointerId(index), Pointer(x, y, x, y))
        }

        // named apart from the api 29 getRawX(int) member which older releases lack
        private fun MotionEvent.screenX(index: Int) = getX(index) + (rawX - x)
        private fun MotionEvent.screenY(index: Int) = getY(index) + (rawY - y)

        private fun finish(endMs: Long) {
            if (pointers.size() == 0) return
            if (maxPointers == 1) {
                val p = pointers.valueAt(0)
                val up = p.startY - p.endY
                if (up <= abs(p.endX - p.startX)) return
                val velocity = up / (endMs - startMs).coerceAtLeast(1L)
                val far = up >= screenH * EDGE_TRIGGER_FRACTION
                val flick = up >= screenH * EDGE_FLICK_FRACTION && velocity >= EDGE_FLICK_VELOCITY_PX_MS
                if (far || flick) SwipeActions.openSwitcher()
                return
            }
            val tracks = ArrayList<SwipeClassifier.Track>(lifted.size + pointers.size())
            for (p in lifted) tracks += SwipeClassifier.Track(p.startX, p.startY, p.endX, p.endY)
            for (i in 0 until pointers.size()) {
                val p = pointers.valueAt(i)
                tracks += SwipeClassifier.Track(p.startX, p.startY, p.endX, p.endY)
            }
            val duration = endMs - if (lastJoinMs > 0) lastJoinMs else startMs
            SwipeClassifier.classify(tracks, maxPointers, duration, minOf(screenW, screenH))
                ?.let { SwipeActions.dispatch(it) }
        }
    }
}
