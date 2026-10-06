package me.rapierxbox.shellyelevatev2.helper

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintLayout
import me.rapierxbox.shellyelevatev2.helper.touch.SwipeClassifier

// root of the kiosk layout that feeds every touch to the swipe helper and steals
// multi finger swipes from the display module while leaving pinch zoom to the page
// it works in dispatchTouchEvent and not onInterceptTouchEvent because a child that calls
// requestDisallowInterceptTouchEvent like the webview while it scrolls would otherwise hide
// the rest of the gesture from the swipe helper and from the steal check
class GestureInterceptLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    var swipeHelper: SwipeHelper? = null

    // the view of the active display module that owns the touch stream
    // the cancel on a steal now walks the whole view tree so it reaches this view anyway
    var gestureTarget: (() -> View?)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val deadZonePx = touchSlop * 0.5f
    private val stealMovePx = touchSlop * 1.5f

    private var stolen = false

    // where each finger went down by pointer id and scratch arrays so a move allocates nothing
    private val downX = FloatArray(MAX_POINTERS)
    private val downY = FloatArray(MAX_POINTERS)
    private val isDown = BooleanArray(MAX_POINTERS)
    private val startX = FloatArray(MAX_POINTERS)
    private val startY = FloatArray(MAX_POINTERS)
    private val nowX = FloatArray(MAX_POINTERS)
    private val nowY = FloatArray(MAX_POINTERS)
    private val motion = SwipeClassifier.Motion()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                stolen = false
                isDown.fill(false)
                rememberDown(ev, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> rememberDown(ev, ev.actionIndex)
        }

        swipeHelper?.onTouchEvent(ev)

        if (stolen) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) stolen = false
            return true
        }
        if (action == MotionEvent.ACTION_MOVE && shouldStealGesture(ev)) {
            stolen = true
            cancelChildren(ev)
            return true
        }
        super.dispatchTouchEvent(ev)
        // claims every gesture so the rest of it keeps coming even when no child wanted the down
        return true
    }

    // the swipe helper is fed in dispatchTouchEvent already
    override fun onTouchEvent(ev: MotionEvent): Boolean = true

    private fun rememberDown(ev: MotionEvent, index: Int) {
        val id = ev.getPointerId(index)
        // pointer ids are small and reused so the id indexes the arrays directly
        if (id !in 0 until MAX_POINTERS) return
        isDown[id] = true
        downX[id] = ev.getX(index)
        downY[id] = ev.getY(index)
    }

    // a cancel through the view group sends it to every child holding the touch and clears
    // its touch targets so the module stops scrolling or zooming right away
    private fun cancelChildren(sourceEvent: MotionEvent) {
        val cancel = MotionEvent.obtain(sourceEvent)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    // true for a multi finger swipe where the moving fingers head the same way and do not pinch
    // same judgement as the final classification just on the way so far
    private fun shouldStealGesture(ev: MotionEvent): Boolean {
        val pointerCount = ev.pointerCount
        if (pointerCount < 2) return false
        var n = 0
        for (i in 0 until pointerCount) {
            val id = ev.getPointerId(i)
            if (id !in 0 until MAX_POINTERS || !isDown[id]) return false
            startX[n] = downX[id]
            startY[n] = downY[id]
            nowX[n] = ev.getX(i)
            nowY[n] = ev.getY(i)
            n++
        }
        if (!SwipeClassifier.analyze(n, startX, startY, nowX, nowY, deadZonePx, motion)) return false
        return motion.meanTravel >= stealMovePx
    }

    private companion object {
        const val MAX_POINTERS = 10
    }
}
