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

// horizontal pages that follow the finger and settle on springs. any closable page can be flung up to dismiss it
// pages are plain children of the same size moved only through translation and scale so nothing is
// laid out again while they move
class SpringPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onPageTap(index: Int)
        fun onPageDismissed(page: View)
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
    // an upward flick this fast dismisses from anywhere
    private val dismissVelocity = 400f * resources.displayMetrics.density
    // pulling back down this fast keeps a card even past the dismiss line
    private val keepVelocity = 300f * resources.displayMetrics.density
    // a dismissed card never leaves slower than this
    private val flyVelocity = 1400f * resources.displayMetrics.density
    // a sideways flick this fast turns the page even after a short drag
    private val pageFlingVelocity = 300f * resources.displayMetrics.density

    // fractional index of the page in the middle
    private var position = 0f
    private var target = 0
    private val positionHolder = FloatValueHolder()
    private val positionSpring = SpringAnimation(positionHolder).apply {
        spring = SpringForce().setStiffness(STIFFNESS_SETTLE).setDampingRatio(DAMPING_SETTLE)
        addUpdateListener { _, value, _ ->
            position = value
            layoutPages()
        }
        addEndListener { _, canceled, _, _ -> if (!canceled) listener?.onPageSettled(currentPage) }
    }

    // pages next to a dismissed one start where they were and spring into the gap
    private var shiftStart = 0
    private var shiftEnd = -1
    private var shift = 0f
    private val shiftSpring = SpringAnimation(FloatValueHolder()).apply {
        spring = SpringForce(0f).setStiffness(STIFFNESS_PAGE).setDampingRatio(DAMPING_PAGE)
        addUpdateListener { _, value, _ ->
            shift = value
            layoutPages()
        }
        addEndListener { _, _, _, _ ->
            shiftStart = 0
            shiftEnd = -1
            shift = 0f
        }
    }

    // vertical motion of one page. its scale and fade follow from its height so a single spring drives it
    private inner class Lift(val view: View) : Runnable {
        var leaving = false
        var posted = false
        var done = false
        val anim = SpringAnimation(view, DynamicAnimation.TRANSLATION_Y).apply {
            spring = SpringForce()
            addUpdateListener { _, value, _ -> onLiftUpdate(this@Lift, value) }
            addEndListener { _, canceled, _, _ -> onLiftEnd(this@Lift, canceled) }
        }

        override fun run() = finishLeaving(this)
    }

    private val lifts = ArrayList<Lift>()

    private enum class Drag { NONE, PAGES, DISMISS }

    private var drag = Drag.NONE
    private var downTime = -1L
    private var downX = 0f
    private var downY = 0f
    private var startPosition = 0f
    private var liftStart = 0f
    private var touchedIndex = -1
    // the touch stopped pages that were still moving
    private var caught = false
    private var dismissing: Lift? = null
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
        target = index
        layoutPages()
    }

    fun animateTo(index: Int, velocity: Float = 0f) {
        target = index.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        positionHolder.value = position
        positionSpring.setStartVelocity(velocity)
        positionSpring.animateToFinalPosition(target.toFloat())
    }

    // flies a page up and away like a swipe up would. false when the page cannot go
    fun dismissPage(index: Int): Boolean {
        if (index !in 0 until childCount || listener?.canDismiss(index) != true) return false
        val child = getChildAt(index)
        val lift = liftOf(child) ?: Lift(child).also { lifts += it }
        if (lift.leaving) return false
        if (dismissing === lift) dismissing = null
        flingAway(lift, 0f)
        settle()
        return true
    }

    // removes every page still flying away right now so the owner sees a final list
    fun finishDismissals() {
        while (true) {
            val lift = lifts.firstOrNull { it.leaving } ?: return
            finishLeaving(lift)
        }
    }

    private fun pageParams() = LayoutParams(pageWidth, pageHeight, Gravity.CENTER)

    private fun layoutPages() {
        if (pageWidth == 0) return
        for (i in 0 until childCount) layoutPage(getChildAt(i), i)
    }

    // translation scale and fade from the distance to the middle and the height of a lifted page
    private fun layoutPage(child: View, i: Int) {
        if (pageWidth == 0) return
        val step = stride
        var distance = i - position
        if (i in shiftStart..shiftEnd) distance += shift / step
        child.translationX = distance * step
        val falloff = min(abs(distance), 1f)
        val lift = if (child.translationY < 0f) min(-child.translationY / pageHeight, 1f) else 0f
        val scale = (1f - 0.08f * falloff) * (1f - LIFT_SCALE * lift)
        child.scaleX = scale
        child.scaleY = scale
        child.alpha = (1f - 0.3f * falloff) * (1f - LIFT_FADE * lift)
    }

    // a page leaving for any reason never takes its lift or transforms into the pool
    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        val lift = liftOf(child)
        if (lift != null) {
            lift.done = true
            lifts.remove(lift)
            removeCallbacks(lift)
            lift.anim.cancel()
            if (dismissing === lift) dismissing = null
        }
        child.translationX = 0f
        child.translationY = 0f
        child.scaleX = 1f
        child.scaleY = 1f
        child.alpha = 1f
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
            // a child took the tap. pages it stopped mid move still need to land
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                settle()
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) begin(ev) else track(ev)
        when (ev.actionMasked) {
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

    // runs once per gesture even though the down passes both intercept and touch
    private fun begin(ev: MotionEvent) {
        if (ev.downTime == downTime) return
        downTime = ev.downTime
        velocityTracker?.clear()
        track(ev)
        downX = ev.x
        downY = ev.y
        drag = Drag.NONE
        dismissing = null
        // catching a moving page stops it under the finger
        caught = positionSpring.isRunning && abs(position - position.roundToInt()) > CATCH_OFFSET
        if (positionSpring.isRunning) positionSpring.cancel()
        startPosition = position
        touchedIndex = pageAt(ev.x, ev.y)
    }

    private fun decide(ev: MotionEvent) {
        val dx = ev.x - downX
        val dy = ev.y - downY
        val adx = abs(dx)
        val ady = abs(dy)
        // a swipe up still counts when it leans sideways. pulling down only rubber bands so it must be straight
        if (ady > touchSlop && ((dy < 0f && ady * DISMISS_LEAN >= adx) || ady > adx) && startDismiss(ev)) return
        if (adx > touchSlop && adx > ady) {
            drag = Drag.PAGES
            startPosition = position
            downX = ev.x
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    private fun startDismiss(ev: MotionEvent): Boolean {
        val index = touchedIndex
        if (index !in 0 until childCount || listener?.canDismiss(index) != true) return false
        val child = getChildAt(index)
        var lift = liftOf(child)
        if (lift == null) {
            lift = Lift(child)
            lifts += lift
        } else if (lift.leaving) {
            return false
        } else {
            // a card still springing home is caught where it is
            lift.anim.cancel()
        }
        dismissing = lift
        liftStart = child.translationY
        downY = ev.y
        drag = Drag.DISMISS
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
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
                val lift = dismissing ?: return
                val raw = liftStart + ev.y - downY
                // up follows the finger one to one and down resists
                lift.view.translationY = if (raw < 0f) raw else FluidMotion.rubberBand(raw, pageHeight.toFloat())
                val index = indexOfChild(lift.view)
                if (index >= 0) layoutPage(lift.view, index)
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
                // paging deceleration keeps a throw short so one swipe moves one page and never skips an app
                val projected = position - FluidMotion.project(vx, FluidMotion.DECELERATION_FAST) / stride
                var target = projected.roundToInt()
                // a clear flick still moves a page even when the finger travelled only a little
                if (target == start && abs(vx) > pageFlingVelocity) target = if (vx < 0f) start + 1 else start - 1
                target = target.coerceIn(start - 1, start + 1)
                // the spring takes over the finger speed but capped so the landing stays soft
                val handoff = (-vx / stride).coerceIn(-MAX_HANDOFF_VELOCITY, MAX_HANDOFF_VELOCITY)
                animateTo(target, handoff)
            }
            Drag.DISMISS -> {
                val lift = dismissing
                dismissing = null
                if (lift != null) {
                    val past = lift.view.translationY < -pageHeight * DISMISS_FRACTION
                    // an upward flick or a card let go above the line unless it is being pulled back down
                    if (!canceled && (vy < -dismissVelocity || (past && vy < keepVelocity))) {
                        flingAway(lift, vy)
                    } else {
                        springBack(lift, vy)
                    }
                }
                settle()
            }
            Drag.NONE -> if (!canceled && abs(ev.x - downX) < touchSlop && abs(ev.y - downY) < touchSlop) {
                tap()
            } else {
                settle()
            }
        }
        drag = Drag.NONE
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun tap() {
        val index = touchedIndex
        when {
            index == NO_PAGE -> settle()
            // a page tapped next to the middle comes to the middle first
            index >= 0 && index != currentPage -> animateTo(index)
            // a tap that only stopped moving pages just lets them land
            caught -> animateTo(currentPage)
            index < 0 -> listener?.onOutsideTap()
            else -> listener?.onPageTap(index)
        }
    }

    // pages stopped between two positions spring to the nearest one
    private fun settle() {
        if (!positionSpring.isRunning && position != currentPage.toFloat()) animateTo(currentPage)
    }

    private fun springBack(lift: Lift, velocity: Float) {
        lift.leaving = false
        val anim = lift.anim
        anim.cancel()
        anim.spring.setStiffness(STIFFNESS_RETURN).setDampingRatio(DAMPING_RETURN).finalPosition = 0f
        anim.setStartVelocity(velocity)
        anim.start()
    }

    private fun flingAway(lift: Lift, velocity: Float) {
        lift.leaving = true
        val anim = lift.anim
        anim.cancel()
        // aims a bit past the top edge and the page is removed as soon as it is out of sight
        anim.spring.setStiffness(STIFFNESS_FLY).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
            .finalPosition = goneY() - pageHeight * 0.25f
        // the card keeps the speed of the finger and is never slower than a decent flick
        anim.setStartVelocity(min(velocity, -flyVelocity))
        anim.start()
    }

    // translation at which a lifted page is fully above the pager
    private fun goneY() = -(height + pageHeight) / 2f

    private fun onLiftUpdate(lift: Lift, value: Float) {
        val index = indexOfChild(lift.view)
        if (index >= 0) layoutPage(lift.view, index)
        // removal happens outside the animation frame so the spring is never torn down from within itself
        if (lift.leaving && !lift.posted && value <= goneY()) {
            lift.posted = true
            post(lift)
        }
    }

    private fun onLiftEnd(lift: Lift, canceled: Boolean) {
        if (canceled) return
        if (lift.leaving) finishLeaving(lift) else lifts.remove(lift)
    }

    private fun finishLeaving(lift: Lift) {
        if (lift.done) return
        lift.done = true
        lifts.remove(lift)
        removeCallbacks(lift)
        lift.anim.cancel()
        removeDismissed(lift.view)
    }

    private fun removeDismissed(child: View) {
        val index = indexOfChild(child)
        if (index < 0) return
        val current = currentPage
        removeViewAt(index)
        // cancel first since its end listener clears the shifted range
        shiftSpring.cancel()
        if (index < current) {
            // pages before it slide right into the gap and the page in the middle stays put
            val running = positionSpring.isRunning
            positionSpring.cancel()
            position -= 1f
            startPosition -= 1f
            positionHolder.value = position
            if (running) animateTo(target - 1)
            shiftStart = 0
            shiftEnd = index - 1
            shift = -stride
        } else {
            // pages after it slide left into the gap
            shiftStart = index
            shiftEnd = Int.MAX_VALUE
            shift = stride
        }
        shiftSpring.setStartValue(shift)
        shiftSpring.setStartVelocity(0f)
        shiftSpring.animateToFinalPosition(0f)
        if (touchedIndex == index) touchedIndex = NO_PAGE else if (touchedIndex > index) touchedIndex--
        val last = (childCount - 1).coerceAtLeast(0).toFloat()
        if (position > last) {
            position = last
            positionHolder.value = position
        }
        layoutPages()
        listener?.onPageDismissed(child)
    }

    private fun liftOf(view: View): Lift? {
        for (i in lifts.indices) if (lifts[i].view === view) return lifts[i]
        return null
    }

    // page under a point using the current transforms. a page flying away can not be grabbed again
    // and its empty slot is no outside tap either
    private fun pageAt(x: Float, y: Float): Int {
        val centerX = width / 2f
        val top = (height - pageHeight) / 2f
        if (y < top || y > top + pageHeight) return -1
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val half = pageWidth * child.scaleX / 2f
            val center = centerX + child.translationX
            if (x >= center - half && x <= center + half) {
                return if (liftOf(child)?.leaving == true) NO_PAGE else i
            }
        }
        return -1
    }

    // tuned by response time and bounce like uikit springs instead of raw stiffness numbers
    private companion object {
        // pages land in a bit under half a second and barely overshoot
        val STIFFNESS_SETTLE = FluidMotion.stiffness(0.45f)
        const val DAMPING_SETTLE = 0.92f
        // fastest hand off from the finger in pages per second
        const val MAX_HANDOFF_VELOCITY = 3f
        // pages next to a dismissed card close the gap in about a third of a second
        val STIFFNESS_PAGE = FluidMotion.stiffness(0.34f)
        const val DAMPING_PAGE = 0.86f
        // a card let go before the dismiss point snaps home with a small bounce
        val STIFFNESS_RETURN = FluidMotion.stiffness(0.32f)
        const val DAMPING_RETURN = 0.75f
        // a dismissed card leaves in about a fifth of a second without bouncing
        val STIFFNESS_FLY = FluidMotion.stiffness(0.4f)
        // share of the page height a card must be lifted to go when let go slowly
        const val DISMISS_FRACTION = 0.35f
        // how far sideways a swipe up may lean and still lift the card
        const val DISMISS_LEAN = 1.1f
        // a fully lifted card shrinks and fades this much
        const val LIFT_SCALE = 0.12f
        const val LIFT_FADE = 0.4f
        // pages further than this from resting count as moving when touched
        const val CATCH_OFFSET = 0.04f
        // the touched page is leaving or went away during the gesture
        const val NO_PAGE = -2
    }
}
