package me.rapierxbox.shellyelevatev2.helper.touch

import kotlin.math.abs
import kotlin.math.hypot

// taps, long presses and edge swipes: everything a finished touch can be besides a swipe across the panel
// positions are screen pixels like in SwipeClassifier. no android types so it is unit tested
object GestureClassifier {

    // a finger that moved less than this share of the smaller screen side did not move
    const val SLOP_FRACTION = 0.02f
    const val MIN_SLOP_PX = 12f

    const val DEFAULT_LONG_PRESS_MS = 600L

    // an edge swipe starts in this outer band of a side and moves inwards
    const val EDGE_BAND_FRACTION = 0.05f
    const val MIN_EDGE_BAND_PX = 24f

    const val MAX_FINGERS = 5

    enum class Kind { TAP, DOUBLE_TAP, LONG_PRESS, EDGE }

    enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

    data class Gesture(val kind: Kind, val fingers: Int = 1, val edge: Edge? = null) {
        // stable id used by the gesture settings and the gesture event
        val id: String
            get() = when (kind) {
                Kind.TAP -> "tap_$fingers"
                Kind.DOUBLE_TAP -> "double_tap_$fingers"
                Kind.LONG_PRESS -> "long_press_$fingers"
                Kind.EDGE -> "edge_${(edge ?: Edge.LEFT).name.lowercase()}"
            }
    }

    @JvmStatic
    fun slopPx(screenPx: Float) = maxOf(MIN_SLOP_PX, screenPx * SLOP_FRACTION)

    // true when no finger left its spot. screenPx is the smaller side of the screen
    @JvmStatic
    fun isStill(tracks: List<SwipeClassifier.Track>, screenPx: Float): Boolean {
        val slop = slopPx(screenPx)
        for (t in tracks) {
            if (hypot(t.endX - t.startX, t.endY - t.startY) > slop) return false
        }
        return true
    }

    // a finished touch that stayed in place: a tap below longPressMs and a long press from there on
    // durationMs runs from the first finger down to the last finger up
    @JvmStatic
    @JvmOverloads
    fun classifyPress(
        tracks: List<SwipeClassifier.Track>,
        maxPointers: Int,
        durationMs: Long,
        screenPx: Float,
        longPressMs: Long = DEFAULT_LONG_PRESS_MS
    ): Gesture? {
        if (tracks.isEmpty() || maxPointers <= 0 || screenPx <= 0f) return null
        if (!isStill(tracks, screenPx)) return null
        val fingers = maxPointers.coerceIn(1, MAX_FINGERS)
        return Gesture(if (durationMs >= longPressMs) Kind.LONG_PRESS else Kind.TAP, fingers)
    }

    // one finger that starts in the outer band of a side and travels inwards
    // it uses the relaxed multi finger thresholds since the start position already tells it from a scroll
    @JvmStatic
    fun classifyEdge(
        tracks: List<SwipeClassifier.Track>,
        maxPointers: Int,
        durationMs: Long,
        width: Float,
        height: Float
    ): Gesture? {
        if (maxPointers != 1 || tracks.size != 1 || width <= 0f || height <= 0f) return null
        val t = tracks[0]
        val dx = t.endX - t.startX
        val dy = t.endY - t.startY
        val horizontal = abs(dx) > abs(dy)
        val travel = if (horizontal) abs(dx) else abs(dy)
        val screenPx = minOf(width, height)
        val velocity = travel / durationMs.coerceAtLeast(1L).toFloat()
        val far = travel >= screenPx * SwipeClassifier.MULTI_DISTANCE_FRACTION &&
            velocity >= SwipeClassifier.MULTI_MIN_VELOCITY_PX_MS
        val flick = travel >= screenPx * SwipeClassifier.FLICK_DISTANCE_FRACTION &&
            velocity >= SwipeClassifier.FLICK_MIN_VELOCITY_PX_MS
        if (!far && !flick) return null

        val bandX = maxOf(MIN_EDGE_BAND_PX, width * EDGE_BAND_FRACTION)
        val bandY = maxOf(MIN_EDGE_BAND_PX, height * EDGE_BAND_FRACTION)
        val edge = if (horizontal) {
            when {
                dx > 0 && t.startX <= bandX -> Edge.LEFT
                dx < 0 && t.startX >= width - bandX -> Edge.RIGHT
                else -> null
            }
        } else {
            when {
                dy > 0 && t.startY <= bandY -> Edge.TOP
                dy < 0 && t.startY >= height - bandY -> Edge.BOTTOM
                else -> null
            }
        }
        return edge?.let { Gesture(Kind.EDGE, 1, it) }
    }

    // every id this classifier and the tap sequencer can report
    @JvmStatic
    fun allIds(): List<String> {
        val ids = ArrayList<String>()
        for (n in 1..MAX_FINGERS) {
            ids += Gesture(Kind.TAP, n).id
            if (n == 1) ids += Gesture(Kind.DOUBLE_TAP, 1).id
            ids += Gesture(Kind.LONG_PRESS, n).id
        }
        for (edge in Edge.entries) ids += Gesture(Kind.EDGE, 1, edge).id
        return ids
    }
}
