package me.rapierxbox.shellyelevatev2.switcher

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// horizontal pages that follow the finger and settle on springs. the middle page can be flung up to dismiss it
// pages are plain children of the same size moved only through translation and scale so nothing is
// laid out again while they move
class SpringPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onPageTap(index: Int)
        fun onPageDismissed(index: Int)
        fun canDismiss(index: Int): Boolean
        fun onOutsideTap()
        fun onPageSettled(index: Int)
    }

    var listener: Listener? = null

    private var pageWidth = 0
    private var pageHeight = 0
    private val gap = 20f * resources.displayMetrics.density
    private val stride get() = pageWidth + gap
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = 1100f * resources.displayMetrics.density

    // fractional index of the page in the middle
    private var position = 0f
    private val positionHolder = FloatValueHolder()
    private val positionSpring = SpringAnimation(positionHolder).apply {
        spring = SpringForce().setStiffness(STIFFNESS_PAGE).setDampingRatio(DAMPING_PAGE)
        addUpdateListener { _, value, _ ->
            position = value
            layoutPages()
        }
        addEndListener { _, canceled, _, _ -> if (!canceled) listener?.onPageSettled(currentPage) }
    }

    // pages after a dismissed one start a page to the right and spring into the gap
    private var shiftFrom = Int.MAX_VALUE
    private var shift = 0f
    private val shiftSpring = SpringAnimation(FloatValueHolder()).apply {
        spring = SpringForce(0f).setStiffness(STIFFNESS_PAGE).setDampingRatio(DAMPING_PAGE)
        addUpdateListener { _, value, _ ->
            shift = value
            layoutPages()
        }
        addEndListener { _, _, _, _ ->
            shiftFrom = Int.MAX_VALUE
            shift = 0f
        }
    }

    private enum class Drag { NONE, PAGES, DISMISS }

    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var startPosition = 0f
    private var touchedIndex = -1
    private var dismissing: View? = null
    private var velocityTracker: VelocityTracker? = null

    val currentPage: Int get() = position.roundToInt().coerceIn(0, (childCount - 1).coerceAtLeast(0))

    fun setPageSize(width: Int, height: Int) {
        pageWidth = width
        pageHeight = height
        for (i in 0 until childCount) getChildAt(i).layoutParams = pageParams()
        layoutPages()
    }

    fun addPage(view: View, index: Int = -1) {
        // fading an overlapping page would cost an offscreen pass every frame
        view.forceHasOverlappingRendering(false)
        view.translationY = 0f
        addView(view, index, pageParams())
        layoutPages()
    }

    fun jumpTo(index: Int) {
        positionSpring.cancel()
        shiftSpring.cancel()
        position = index.toFloat()
        positionHolder.value = position
        layoutPages()
    }

    fun animateTo(index: Int, velocity: Float = 0f) {
        positionHolder.value = position
        positionSpring.setStartVelocity(velocity)
        positionSpring.animateToFinalPosition(index.coerceIn(0, (childCount - 1).coerceAtLeast(0)).toFloat())
    }

    private fun pageParams() = LayoutParams(pageWidth, pageHeight, Gravity.CENTER)

    // translation scale and fade from the distance to the middle
    private fun layoutPages() {
        if (pageWidth == 0) return
        val step = stride
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            var distance = i - position
            if (i >= shiftFrom) distance += shift / step
            child.translationX = distance * step
            val falloff = min(abs(distance), 1f)
            val scale = 1f - 0.08f * falloff
            child.scaleX = scale
            child.scaleY = scale
            if (child !== dismissing) child.alpha = 1f - 0.3f * falloff
        }
    }

    // touch

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                begin(ev)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (drag == Drag.NONE) decide(ev)
                return drag != Drag.NONE
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.NONE) decide(ev)
                dragTo(ev)
            }
            MotionEvent.ACTION_UP -> release(ev, canceled = false)
            MotionEvent.ACTION_CANCEL -> release(ev, canceled = true)
        }
        return true
    }

    private fun track(ev: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(ev)
    }

    private fun begin(ev: MotionEvent) {
        velocityTracker?.clear()
        track(ev)
        downX = ev.x
        downY = ev.y
        drag = Drag.NONE
        // catching a moving page stops it under the finger
        if (positionSpring.isRunning) positionSpring.cancel()
        startPosition = position
        touchedIndex = pageAt(ev.x, ev.y)
    }

    private fun decide(ev: MotionEvent) {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
            drag = Drag.PAGES
            startPosition = position
            downX = ev.x
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (dy < -touchSlop && abs(dy) > abs(dx) && touchedIndex == currentPage &&
            listener?.canDismiss(touchedIndex) == true
        ) {
            drag = Drag.DISMISS
            downY = ev.y
            dismissing = getChildAt(touchedIndex)
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    private fun dragTo(ev: MotionEvent) {
        when (drag) {
            Drag.PAGES -> {
                val raw = startPosition - (ev.x - downX) / stride
                val last = (childCount - 1).toFloat()
                // past either end the page resists like a scroll view rubber band
                position = when {
                    raw < 0f -> FluidMotion.rubberBand(raw * stride, width.toFloat()) / stride
                    raw > last -> last + FluidMotion.rubberBand((raw - last) * stride, width.toFloat()) / stride
                    else -> raw
                }
                layoutPages()
            }
            Drag.DISMISS -> {
                val child = dismissing ?: return
                val dy = ev.y - downY
                child.translationY = if (dy < 0) dy else FluidMotion.rubberBand(dy, pageHeight.toFloat())
                child.alpha = 1f - min(abs(child.translationY) / pageHeight, 1f) * 0.5f
            }
            Drag.NONE -> {}
        }
    }

    private fun release(ev: MotionEvent, canceled: Boolean) {
        val tracker = velocityTracker
        tracker?.computeCurrentVelocity(1000)
        val vx = tracker?.xVelocity ?: 0f
        val vy = tracker?.yVelocity ?: 0f
        when (drag) {
            Drag.PAGES -> {
                val start = startPosition.roundToInt()
                // the throw is projected with scroll view deceleration so a hard flick can pass several pages
                // and the spring starts at the finger velocity so the hand off has no jolt
                val projected = position - FluidMotion.project(vx) / stride
                val target = projected.roundToInt().coerceIn(start - MAX_PAGES_PER_FLING, start + MAX_PAGES_PER_FLING)
                animateTo(target, -vx / stride)
            }
            Drag.DISMISS -> {
                val child = dismissing
                if (child != null) {
                    val projected = child.translationY + FluidMotion.project(vy)
                    if (!canceled && (vy < -flingVelocity || projected < -pageHeight * 0.5f)) {
                        flingAway(child, vy)
                    } else {
                        springBack(child, vy)
                    }
                }
            }
            Drag.NONE -> if (!canceled && abs(ev.x - downX) < touchSlop && abs(ev.y - downY) < touchSlop) {
                tap()
            } else if (!positionSpring.isRunning) {
                animateTo(currentPage)
            }
        }
        drag = Drag.NONE
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun tap() {
        val index = touchedIndex
        when {
            index < 0 -> listener?.onOutsideTap()
            index == currentPage -> listener?.onPageTap(index)
            // a peeking page comes to the middle first
            else -> animateTo(index)
        }
    }

    private fun springBack(child: View, velocity: Float) {
        dismissing = null
        SpringAnimation(child, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring.setStiffness(STIFFNESS_RETURN).dampingRatio = DAMPING_RETURN
            setStartVelocity(velocity)
            start()
        }
        SpringAnimation(child, DynamicAnimation.ALPHA, 1f).apply {
            spring.setStiffness(STIFFNESS_RETURN).dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            start()
        }
    }

    private fun flingAway(child: View, velocity: Float) {
        SpringAnimation(child, DynamicAnimation.TRANSLATION_Y, -height.toFloat()).apply {
            spring.setStiffness(STIFFNESS_FLING).dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            // the card keeps the speed of the finger and is never slower than a decent flick
            setStartVelocity(min(velocity, -flingVelocity))
            addEndListener { _, _, _, _ -> removeDismissed(child) }
            start()
        }
    }

    private fun removeDismissed(child: View) {
        val index = indexOfChild(child)
        dismissing = null
        if (index < 0) return
        removeViewAt(index)
        child.translationY = 0f
        child.alpha = 1f
        // the page that took its place slides in from where it was
        shiftFrom = index
        shift = stride
        shiftSpring.cancel()
        shiftSpring.setStartValue(stride)
        shiftSpring.animateToFinalPosition(0f)
        if (position > childCount - 1) jumpTo(childCount - 1)
        layoutPages()
        listener?.onPageDismissed(index)
    }

    // page under a point using the current transforms
    private fun pageAt(x: Float, y: Float): Int {
        val centerX = width / 2f
        val top = (height - pageHeight) / 2f
        if (y < top || y > top + pageHeight) return -1
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val half = pageWidth * child.scaleX / 2f
            val center = centerX + child.translationX
            if (x >= center - half && x <= center + half) return i
        }
        return -1
    }

    // tuned by response time and bounce like uikit springs instead of raw stiffness numbers
    private companion object {
        // pages settle in about a third of a second with a hint of overshoot
        val STIFFNESS_PAGE = FluidMotion.stiffness(0.34f)
        const val DAMPING_PAGE = 0.86f
        // a card let go before the dismiss point snaps home with a small bounce
        val STIFFNESS_RETURN = FluidMotion.stiffness(0.32f)
        const val DAMPING_RETURN = 0.72f
        // a dismissed card leaves fast without bouncing
        val STIFFNESS_FLING = FluidMotion.stiffness(0.28f)
        const val MAX_PAGES_PER_FLING = 4
    }
}
