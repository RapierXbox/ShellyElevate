package me.rapierxbox.shellyelevatev2.helper.touch

import kotlin.math.abs
import kotlin.math.hypot

// decides whether a finished touch gesture was a swipe. shared by the in app SwipeHelper, the edge
// strip and the global touchscreen reader so all of them judge swipes the same way
// thresholds scale with the smaller screen side so they mean the same share of the panel everywhere
object SwipeClassifier {

    // single finger swipes toggle a relay so they stay strict enough to ignore dashboard scrolling
    const val SINGLE_DISTANCE_FRACTION = 1f / 3f
    const val SINGLE_MIN_VELOCITY_PX_MS = 1.0f

    // a multi finger swipe either travels this far at a relaxed pace
    const val MULTI_DISTANCE_FRACTION = 0.2f
    const val MULTI_MIN_VELOCITY_PX_MS = 0.12f

    // or is a quick flick over a shorter way
    const val FLICK_DISTANCE_FRACTION = 0.1f
    const val FLICK_MIN_VELOCITY_PX_MS = 0.5f

    // fingers that moved less than this are resting and neither vote on direction nor veto it
    const val DEAD_ZONE_FRACTION = 0.03f

    // a moving finger may stray up to 60 degrees from the mean direction
    private const val AGREE_COS = 0.5f

    // fingers spreading or closing by more than this share of the travel are a pinch
    private const val PINCH_RATIO = 0.4f

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    // positions in screen pixels
    class Track(val startX: Float, val startY: Float, val endX: Float, val endY: Float)

    data class Swipe(val fingers: Int, val direction: Direction) {
        // stable id used by the app switcher gesture setting
        val id: String get() = gestureId(fingers, direction)
    }

    // result of analyze. callers keep one around so the live check allocates nothing
    class Motion {
        var direction = Direction.UP
        // longest travel of a moving finger along the swipe direction
        var travel = 0f
        // mean travel of the moving fingers along the swipe direction
        var meanTravel = 0f
    }

    @JvmStatic
    fun gestureId(fingers: Int, direction: Direction) = "swipe_${fingers}_${direction.name.lowercase()}"

    // single finger swipes are vertical only like the original switch on swipe
    // multi finger swipes report the finger count clamped to 2..5
    // screenPx is the smaller side of the screen
    @JvmStatic
    fun classify(tracks: List<Track>, maxPointers: Int, durationMs: Long, screenPx: Float): Swipe? {
        if (tracks.isEmpty() || maxPointers <= 0 || screenPx <= 0f) return null
        val duration = durationMs.coerceAtLeast(1L).toFloat()

        if (maxPointers == 1) {
            val p = tracks.first()
            val dx = p.endX - p.startX
            val dy = p.endY - p.startY
            if (abs(dy) <= abs(dx)) return null
            if (abs(dy) <= screenPx * SINGLE_DISTANCE_FRACTION || abs(dy) / duration <= SINGLE_MIN_VELOCITY_PX_MS) return null
            return Swipe(1, if (dy < 0) Direction.UP else Direction.DOWN)
        }

        val n = tracks.size
        val sx = FloatArray(n)
        val sy = FloatArray(n)
        val ex = FloatArray(n)
        val ey = FloatArray(n)
        for (i in 0 until n) {
            val t = tracks[i]
            sx[i] = t.startX
            sy[i] = t.startY
            ex[i] = t.endX
            ey[i] = t.endY
        }
        val motion = Motion()
        if (!analyze(n, sx, sy, ex, ey, screenPx * DEAD_ZONE_FRACTION, motion)) return null

        val velocity = motion.travel / duration
        val far = motion.travel >= screenPx * MULTI_DISTANCE_FRACTION && velocity >= MULTI_MIN_VELOCITY_PX_MS
        val flick = motion.travel >= screenPx * FLICK_DISTANCE_FRACTION && velocity >= FLICK_MIN_VELOCITY_PX_MS
        if (!far && !flick) return null
        return Swipe(maxPointers.coerceIn(2, 5), motion.direction)
    }

    // true when n fingers moved together like a swipe and not like a pinch rotate or tap
    // positions are parallel arrays. fills out with the direction and travel. allocates nothing
    @JvmStatic
    fun analyze(
        n: Int,
        sx: FloatArray, sy: FloatArray,
        ex: FloatArray, ey: FloatArray,
        deadZonePx: Float,
        out: Motion
    ): Boolean {
        if (n < 2) return false
        var moving = 0
        var sumDx = 0f
        var sumDy = 0f
        for (i in 0 until n) {
            val dx = ex[i] - sx[i]
            val dy = ey[i] - sy[i]
            if (hypot(dx, dy) <= deadZonePx) continue
            moving++
            sumDx += dx
            sumDy += dy
        }
        // a resting finger next to a single moving one is a pinch or rotate anchor
        if (moving < 2 || moving * 2 < n) return false

        val meanDx = sumDx / moving
        val meanDy = sumDy / moving
        val meanLen = hypot(meanDx, meanDy)
        if (meanLen <= 0f) return false
        val ux = meanDx / meanLen
        val uy = meanDy / meanLen

        val vertical = abs(meanDy) >= abs(meanDx)
        val axisSign = if (vertical) (if (meanDy < 0) -1f else 1f) else (if (meanDx < 0) -1f else 1f)
        var travel = 0f
        for (i in 0 until n) {
            val dx = ex[i] - sx[i]
            val dy = ey[i] - sy[i]
            val len = hypot(dx, dy)
            if (len <= deadZonePx) continue
            if (dx * ux + dy * uy < AGREE_COS * len) return false
            val along = (if (vertical) dy else dx) * axisSign
            if (along > travel) travel = along
        }
        val meanTravel = abs(if (vertical) meanDy else meanDx)

        if (isPinch(n, sx, sy, ex, ey, meanTravel, deadZonePx)) return false

        out.direction = if (vertical) {
            if (meanDy < 0) Direction.UP else Direction.DOWN
        } else {
            if (meanDx < 0) Direction.LEFT else Direction.RIGHT
        }
        out.travel = travel
        out.meanTravel = meanTravel
        return true
    }

    // mean movement of every finger away from the start centroid weighted by its distance to it
    // a shared translation cancels out exactly so only spreading or closing is left
    // for two fingers it is half the change of their distance along the line between them
    private fun isPinch(
        n: Int,
        sx: FloatArray, sy: FloatArray,
        ex: FloatArray, ey: FloatArray,
        travel: Float,
        deadZonePx: Float
    ): Boolean {
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            cx += sx[i]
            cy += sy[i]
        }
        cx /= n
        cy /= n
        var radial = 0f
        var spread = 0f
        for (i in 0 until n) {
            val rx = sx[i] - cx
            val ry = sy[i] - cy
            radial += (ex[i] - sx[i]) * rx + (ey[i] - sy[i]) * ry
            spread += hypot(rx, ry)
        }
        if (spread <= 0f) return false
        return abs(radial / spread) > maxOf(PINCH_RATIO * travel, deadZonePx)
    }
}
