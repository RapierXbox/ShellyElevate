package me.rapierxbox.shellyelevatev2.helper.touch

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

// decides whether a finished touch gesture was a swipe. shared by the in app SwipeHelper and the
// global touchscreen reader so both judge swipes the same way
object SwipeClassifier {

    // the inter pointer distance changing by more than this times the swipe distance is a pinch
    private const val PINCH_SPREAD_RATIO_THRESHOLD = 0.35f

    // 1000 px per second
    const val MIN_VELOCITY_PX_MS = 1.0f

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    // positions in screen pixels
    class Track(val startX: Float, val startY: Float, val endX: Float, val endY: Float)

    data class Swipe(val fingers: Int, val direction: Direction) {
        // stable id used by the app switcher gesture setting
        val id: String get() = gestureId(fingers, direction)
    }

    @JvmStatic
    fun gestureId(fingers: Int, direction: Direction) = "swipe_${fingers}_${direction.name.lowercase()}"

    // single finger swipes are vertical only like the original switch on swipe
    // multi finger swipes report the finger count clamped to 2..5
    @JvmStatic
    fun classify(
        tracks: List<Track>,
        maxPointers: Int,
        durationMs: Long,
        minDistancePx: Float,
        minVelocityPxMs: Float = MIN_VELOCITY_PX_MS
    ): Swipe? {
        if (tracks.isEmpty() || maxPointers <= 0) return null
        val duration = durationMs.coerceAtLeast(1L).toFloat()

        if (maxPointers == 1) {
            val p = tracks.first()
            val dy = p.endY - p.startY
            val velocity = abs(dy) / duration
            if (velocity <= minVelocityPxMs || abs(dy) <= minDistancePx) return null
            return Swipe(1, if (dy < 0) Direction.UP else Direction.DOWN)
        }

        val meanDx = tracks.sumOf { (it.endX - it.startX).toDouble() }.toFloat() / tracks.size
        val meanDy = tracks.sumOf { (it.endY - it.startY).toDouble() }.toFloat() / tracks.size
        val vertical = abs(meanDy) >= abs(meanDx)
        val meanDist = maxOf(abs(meanDx), abs(meanDy))
        val velocity = meanDist / duration

        if (velocity <= minVelocityPxMs || meanDist <= minDistancePx) return null
        if (isPinchOrSpread(tracks, meanDist)) return null
        if (fingersDisagree(tracks, vertical, if (vertical) meanDy else meanDx)) return null

        val direction = if (vertical) {
            if (meanDy < 0) Direction.UP else Direction.DOWN
        } else {
            if (meanDx < 0) Direction.LEFT else Direction.RIGHT
        }
        return Swipe(maxPointers.coerceIn(2, 5), direction)
    }

    // only checked for two fingers since more than that is not a native pinch gesture
    private fun isPinchOrSpread(tracks: List<Track>, meanDist: Float): Boolean {
        if (tracks.size != 2) return false
        val (p0, p1) = tracks
        val startSpread = hypot(p0.startX - p1.startX, p0.startY - p1.startY)
        val endSpread = hypot(p0.endX - p1.endX, p0.endY - p1.endY)
        return abs(endSpread - startSpread) > PINCH_SPREAD_RATIO_THRESHOLD * meanDist
    }

    // every finger must move the same way on the dominant axis
    private fun fingersDisagree(tracks: List<Track>, vertical: Boolean, mean: Float): Boolean =
        tracks.any {
            val delta = if (vertical) it.endY - it.startY else it.endX - it.startX
            delta.sign != mean.sign
        }
}
