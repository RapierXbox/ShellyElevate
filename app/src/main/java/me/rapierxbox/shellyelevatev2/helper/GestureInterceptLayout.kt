package me.rapierxbox.shellyelevatev2.helper

import android.util.Log
import android.content.Context
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintLayout
import me.rapierxbox.shellyelevatev2.BuildConfig
import kotlin.math.abs

class GestureInterceptLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    companion object {
        private const val TAG = "GestureIntercept"
    }

    var swipeHelper: SwipeHelper? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minMovePx = touchSlop * 0.3f
    private val pinchDeltaRatio = 0.35f

    private var intercepting = false
    private val downX = SparseArray<Float>()
    private val downY = SparseArray<Float>()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        Log.i(TAG, "onIntercept action=${ev.actionMasked} pointers=${ev.pointerCount} interceptingBefore=$intercepting")
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                intercepting = false
                downX.clear()
                downY.clear()
                downX.put(ev.getPointerId(0), ev.x)
                downY.put(ev.getPointerId(0), ev.y)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "ACTION_DOWN pointers=${ev.pointerCount} x=${ev.x} y=${ev.y}")
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                downX.put(ev.getPointerId(idx), ev.getX(idx))
                downY.put(ev.getPointerId(idx), ev.getY(idx))
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "ACTION_POINTER_DOWN pointers=${ev.pointerCount} index=$idx")
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2 && !intercepting) {
                    intercepting = shouldStealGesture(ev)
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "ACTION_MOVE pointers=${ev.pointerCount} intercepting=$intercepting")
                    }
                }
            }
        }

        if (!intercepting) {
            swipeHelper?.onTouchEvent(ev)
        } else if (BuildConfig.DEBUG && ev.actionMasked == MotionEvent.ACTION_MOVE) {
            Log.d(TAG, "Gesture intercepted by parent layout")
        }
        Log.i(TAG, "onIntercept return intercepting=$intercepting")
        return intercepting
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        Log.i(TAG, "onTouchEvent action=${ev.actionMasked} pointers=${ev.pointerCount} intercepting=$intercepting")
        swipeHelper?.onTouchEvent(ev)
        if (BuildConfig.DEBUG && (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL)) {
            Log.d(TAG, "onTouchEvent end action=${ev.actionMasked} intercepting=$intercepting")
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            intercepting = false
        }
        return true
    }

    private fun shouldStealGesture(ev: MotionEvent): Boolean {
        val n = ev.pointerCount
        if (n < 2) {
            if (BuildConfig.DEBUG) Log.d(TAG, "shouldStealGesture=false reason=pointerCount_lt_2")
            return false
        }

        var maxMove = 0f
        var refSign = 0f
        var refIsVertical = false
        var activeCount = 0
        var signMismatch = false
        var axisMismatch = false

        for (i in 0 until n) {
            val id = ev.getPointerId(i)
            val sx = downX.get(id) ?: run {
                if (BuildConfig.DEBUG) Log.d(TAG, "shouldStealGesture=false reason=missingStartX pointerId=$id")
                return false
            }
            val sy = downY.get(id) ?: run {
                if (BuildConfig.DEBUG) Log.d(TAG, "shouldStealGesture=false reason=missingStartY pointerId=$id")
                return false
            }
            val dx = ev.getX(i) - sx
            val dy = ev.getY(i) - sy
            val mag = maxOf(abs(dx), abs(dy))
            if (mag > maxMove) maxMove = mag

            if (mag < minMovePx) continue

            val isVertical = abs(dy) >= abs(dx)
            val sign = Math.signum(if (isVertical) dy else dx)
            activeCount++
            if (refSign == 0f) {
                refSign = sign
                refIsVertical = isVertical
            } else {
                if (sign != refSign) signMismatch = true
                if (isVertical != refIsVertical) axisMismatch = true
            }
        }

        if (maxMove < touchSlop * 1.5f) {
            if (BuildConfig.DEBUG) Log.d(TAG, "shouldStealGesture=false reason=maxMove_below_threshold maxMove=$maxMove threshold=${touchSlop * 1.5f}")
            return false
        }
        if (activeCount < 2) {
            if (BuildConfig.DEBUG) Log.d(TAG, "shouldStealGesture=false reason=activeCount_lt_2 activeCount=$activeCount")
            return false
        }
        if (signMismatch || axisMismatch) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "shouldStealGesture=false reason=signOrAxisMismatch signMismatch=$signMismatch axisMismatch=$axisMismatch")
            }
            return false
        }

        if (n == 2) {
            val id0 = ev.getPointerId(0)
            val id1 = ev.getPointerId(1)
            val sx0 = downX.get(id0)
            val sy0 = downY.get(id0)
            val sx1 = downX.get(id1)
            val sy1 = downY.get(id1)
            if (sx0 != null && sy0 != null && sx1 != null && sy1 != null) {
                val startDist = Math.hypot((sx1 - sx0).toDouble(), (sy1 - sy0).toDouble())
                val curDist = Math.hypot(
                    (ev.getX(1) - ev.getX(0)).toDouble(),
                    (ev.getY(1) - ev.getY(0)).toDouble()
                )
                if (startDist > 0 && abs(curDist - startDist) / startDist > pinchDeltaRatio) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "shouldStealGesture=false reason=pinchDetected startDist=$startDist curDist=$curDist")
                    }
                    return false
                }
            }
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "shouldStealGesture=true")
        return true
    }
}
