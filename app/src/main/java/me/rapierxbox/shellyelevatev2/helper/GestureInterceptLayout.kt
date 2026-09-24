package me.rapierxbox.shellyelevatev2.helper

import android.content.Context
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.constraintlayout.widget.ConstraintLayout
import me.rapierxbox.shellyelevatev2.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

// root of the kiosk layout that feeds every touch to the swipe helper and steals
// multi finger swipes from the webview while leaving pinch zoom to the page
class GestureInterceptLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    var swipeHelper: SwipeHelper? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minMovePx = touchSlop * 0.3f
    private val stealMovePx = touchSlop * 1.5f

    private var intercepting = false
    private val downX = SparseArray<Float>()
    private val downY = SparseArray<Float>()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                intercepting = false
                downX.clear()
                downY.clear()
                rememberDown(ev, 0)
            }

            MotionEvent.ACTION_POINTER_DOWN -> rememberDown(ev, ev.actionIndex)

            MotionEvent.ACTION_MOVE -> {
                if (!intercepting && shouldStealGesture(ev)) {
                    intercepting = true
                    cancelWebViewTouchStream(ev)
                }
            }
        }

        if (!intercepting) swipeHelper?.onTouchEvent(ev)
        return intercepting
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        swipeHelper?.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            intercepting = false
        }
        return true
    }

    private fun rememberDown(ev: MotionEvent, index: Int) {
        val id = ev.getPointerId(index)
        downX.put(id, ev.getX(index))
        downY.put(id, ev.getY(index))
    }

    // viewgroup would send the cancel on the next event anyway but doing it now
    // avoids a frame where the webview still thinks it owns the touch
    private fun cancelWebViewTouchStream(sourceEvent: MotionEvent) {
        val webView = findViewById<WebView?>(R.id.myWebView) ?: return
        val cancel = MotionEvent.obtain(sourceEvent)
        cancel.action = MotionEvent.ACTION_CANCEL
        webView.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    // true for a multi finger swipe where every moving finger travels the same axis and direction
    private fun shouldStealGesture(ev: MotionEvent): Boolean {
        val pointerCount = ev.pointerCount
        if (pointerCount < 2) return false

        var maxMove = 0f
        var refSign = 0f
        var refIsVertical = false
        var activeCount = 0

        for (i in 0 until pointerCount) {
            val id = ev.getPointerId(i)
            val startX = downX.get(id) ?: return false
            val startY = downY.get(id) ?: return false
            val dx = ev.getX(i) - startX
            val dy = ev.getY(i) - startY
            val move = maxOf(abs(dx), abs(dy))
            if (move > maxMove) maxMove = move

            if (move < minMovePx) continue

            val isVertical = abs(dy) >= abs(dx)
            val sign = (if (isVertical) dy else dx).sign
            activeCount++
            if (refSign == 0f) {
                refSign = sign
                refIsVertical = isVertical
            } else if (sign != refSign || isVertical != refIsVertical) {
                return false
            }
        }

        if (maxMove < stealMovePx || activeCount < 2) return false
        return pointerCount != 2 || !isPinch(ev)
    }

    // two fingers whose distance changed a lot are zooming and not swiping
    private fun isPinch(ev: MotionEvent): Boolean {
        val id0 = ev.getPointerId(0)
        val id1 = ev.getPointerId(1)
        val startX0 = downX.get(id0) ?: return false
        val startY0 = downY.get(id0) ?: return false
        val startX1 = downX.get(id1) ?: return false
        val startY1 = downY.get(id1) ?: return false

        val startDist = hypot(startX1 - startX0, startY1 - startY0)
        if (startDist <= 0f) return false
        val curDist = hypot(ev.getX(1) - ev.getX(0), ev.getY(1) - ev.getY(0))
        return abs(curDist - startDist) / startDist > PINCH_DELTA_RATIO
    }

    private companion object {
        const val PINCH_DELTA_RATIO = 0.35f
    }
}
