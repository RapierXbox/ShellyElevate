package me.rapierxbox.shellyelevatev2.helper.touch

import android.view.MotionEvent

// feeds one finger touches our own activities see to the global reader so it can learn
// how the raw panel axes line up with the screen. call it from dispatchTouchEvent
object TouchCalibrator {
    private var downX = 0f
    private var downY = 0f
    private var multi = false

    @JvmStatic
    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                multi = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> multi = true
            MotionEvent.ACTION_UP -> if (!multi) {
                TouchGestureMonitor.onScreenGesture(event.rawX - downX, event.rawY - downY, event.eventTime)
            }
        }
    }
}
