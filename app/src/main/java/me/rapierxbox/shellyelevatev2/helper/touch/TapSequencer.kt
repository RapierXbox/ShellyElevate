package me.rapierxbox.shellyelevatev2.helper.touch

import me.rapierxbox.shellyelevatev2.helper.touch.GestureClassifier.Gesture
import me.rapierxbox.shellyelevatev2.helper.touch.GestureClassifier.Kind

// turns single presses into the gestures that are reported. it holds a tap back for the double tap
// window and drops the bounce of the hand after a multi finger tap
// times are passed in so it runs without a clock and is unit tested. not thread safe
class TapSequencer(
    private val doubleTapMs: Long = DEFAULT_DOUBLE_TAP_MS,
    private val bounceMs: Long = DEFAULT_BOUNCE_MS
) {
    private var pending = false
    private var pendingFingers = 1
    private var pendingUpMs = 0L
    private var bounceUntilMs = 0L

    // when the tap held back turns into a plain tap or -1 when none is held back
    val flushAtMs: Long get() = if (pending) pendingUpMs + doubleTapMs else -1L

    // a touch ended. press is what GestureClassifier.classifyPress made of it or null for anything else
    // returns what to report right now which may be nothing
    fun onTouchEnd(press: Gesture?, downMs: Long, upMs: Long): List<Gesture> {
        val out = ArrayList<Gesture>(2)
        val tap = press?.kind == Kind.TAP
        // after a multi finger double tap the hand touches the glass again 140 to 180 ms later
        if (tap && downMs < bounceUntilMs) return out
        if (pending) {
            // the second touch must go down within the window after the first went up
            if (tap && downMs - pendingUpMs <= doubleTapMs) {
                if (press?.fingers == pendingFingers) {
                    pending = false
                    if (pendingFingers > 1) bounceUntilMs = upMs + bounceMs
                    out += Gesture(Kind.DOUBLE_TAP, pendingFingers)
                    return out
                }
                // another finger count right after a multi finger tap is the hand bouncing
                // so the tap stays held back
                if (pendingFingers > 1 && downMs - pendingUpMs <= bounceMs) return out
            }
            // the second touch was something else so the first stays a tap
            pending = false
            out += Gesture(Kind.TAP, pendingFingers)
        }
        if (press == null) return out
        if (!tap) {
            out += press
            return out
        }
        pending = true
        pendingFingers = press.fingers
        pendingUpMs = upMs
        return out
    }

    // the tap held back once its window has passed. touchDownMs is when the touch running right now
    // went down or -1 with no finger on the panel. a running second touch decides when it ends
    @JvmOverloads
    fun flush(nowMs: Long, touchDownMs: Long = -1L): Gesture? {
        if (!pending || nowMs < pendingUpMs + doubleTapMs) return null
        if (touchDownMs >= pendingUpMs) return null
        pending = false
        return Gesture(Kind.TAP, pendingFingers)
    }

    fun reset() {
        pending = false
        bounceUntilMs = 0L
    }

    companion object {
        const val DEFAULT_DOUBLE_TAP_MS = 300L
        // measured on the x2i
        const val DEFAULT_BOUNCE_MS = 250L
    }
}
