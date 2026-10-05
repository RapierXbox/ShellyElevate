package me.rapierxbox.shellyelevatev2.helper.touch

import kotlin.math.abs

// maps raw touchscreen coordinates onto the screen. some panels report axes swapped or mirrored
// against what the display shows so the mapping is learned from touches our own screens see
// with screen coordinates. no android types so it is unit tested
class TouchAxisMap(
    var swap: Boolean = false,
    var invertX: Boolean = false,
    var invertY: Boolean = false
) {
    // votes so one odd gesture cannot flip the mapping. positive means yes
    private var swapScore = 0
    private var invertXScore = 0
    private var invertYScore = 0

    // raw normalized 0..1 to screen normalized 0..1 written to out as x y
    fun map(u: Float, v: Float, out: FloatArray) {
        var x = if (swap) v else u
        var y = if (swap) u else v
        if (invertX) x = 1f - x
        if (invertY) y = 1f - y
        out[0] = x
        out[1] = y
    }

    // raw normalized movement to screen normalized movement written to out as dx dy
    fun mapDelta(du: Float, dv: Float, out: FloatArray) {
        var dx = if (swap) dv else du
        var dy = if (swap) du else dv
        if (invertX) dx = -dx
        if (invertY) dy = -dy
        out[0] = dx
        out[1] = dy
    }

    // one finger moved du dv on the raw axes while the screen saw dx dy. all normalized to 0..1
    // returns true when the mapping changed
    fun learn(du: Float, dv: Float, dx: Float, dy: Float): Boolean {
        if (maxOf(abs(dx), abs(dy)) < MIN_TRAVEL || maxOf(abs(du), abs(dv)) < MIN_TRAVEL) return false
        if (!dominant(du, dv) || !dominant(dx, dy)) return false
        val rawIsX = abs(du) > abs(dv)
        val screenIsX = abs(dx) > abs(dy)
        swapScore = vote(swapScore, rawIsX != screenIsX)
        val rawDelta = if (rawIsX) du else dv
        val screenDelta = if (screenIsX) dx else dy
        val inverted = (rawDelta > 0) != (screenDelta > 0)
        if (screenIsX) invertXScore = vote(invertXScore, inverted) else invertYScore = vote(invertYScore, inverted)

        val before = encode()
        if (abs(swapScore) >= DECIDE) swap = swapScore > 0
        if (abs(invertXScore) >= DECIDE) invertX = invertXScore > 0
        if (abs(invertYScore) >= DECIDE) invertY = invertYScore > 0
        return encode() != before
    }

    fun encode(): Int = (if (swap) 1 else 0) or (if (invertX) 2 else 0) or (if (invertY) 4 else 0)

    private fun vote(score: Int, yes: Boolean) = (score + if (yes) 1 else -1).coerceIn(-MAX_SCORE, MAX_SCORE)

    // one axis clearly moved more than the other
    private fun dominant(a: Float, b: Float) = maxOf(abs(a), abs(b)) > 2f * minOf(abs(a), abs(b))

    companion object {
        private const val MIN_TRAVEL = 0.12f
        private const val DECIDE = 2
        private const val MAX_SCORE = 3

        fun decode(bits: Int) = TouchAxisMap(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)

        // before anything is learned a panel whose long side disagrees with the screen is swapped
        fun guess(spanX: Int, spanY: Int, screenW: Int, screenH: Int): TouchAxisMap {
            val rawWide = spanX > spanY * 1.1f
            val rawTall = spanY > spanX * 1.1f
            val screenWide = screenW > screenH * 1.1f
            val screenTall = screenH > screenW * 1.1f
            return TouchAxisMap(swap = (rawWide && screenTall) || (rawTall && screenWide))
        }
    }
}
