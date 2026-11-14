package me.rapierxbox.shellyelevatev2.helper.touch

import me.rapierxbox.shellyelevatev2.helper.touch.GestureClassifier.Gesture
import me.rapierxbox.shellyelevatev2.helper.touch.GestureClassifier.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapSequencerTest {

    private val tap1 = Gesture(Kind.TAP, 1)
    private val tap3 = Gesture(Kind.TAP, 3)
    private val double1 = Gesture(Kind.DOUBLE_TAP, 1)
    private val long2 = Gesture(Kind.LONG_PRESS, 2)

    @Test
    fun aSingleTapIsHeldBackForTheDoubleTapWindow() {
        val s = TapSequencer()
        assertEquals(emptyList<Gesture>(), s.onTouchEnd(tap1, 1000, 1080))
        assertEquals(1380L, s.flushAtMs)
        assertNull(s.flush(1379))
        assertEquals(tap1, s.flush(1380))
        assertEquals(-1L, s.flushAtMs)
        assertNull(s.flush(2000))
    }

    @Test
    fun twoTapsInTheWindowAreADoubleTap() {
        val s = TapSequencer()
        s.onTouchEnd(tap1, 1000, 1080)
        // the window runs from the first finger up to the second finger down
        assertEquals(listOf(double1), s.onTouchEnd(tap1, 1380, 1460))
        assertNull(s.flush(5000))
    }

    @Test
    fun aSecondTapAfterTheWindowIsTwoTaps() {
        val s = TapSequencer()
        s.onTouchEnd(tap1, 1000, 1080)
        // the timer did not run yet so the first tap comes out with the end of the second touch
        assertEquals(listOf(tap1), s.onTouchEnd(tap1, 1381, 1460))
        assertEquals(tap1, s.flush(1760))
    }

    @Test
    fun aRunningSecondTouchKeepsTheTapBack() {
        val s = TapSequencer()
        s.onTouchEnd(tap1, 1000, 1080)
        // second finger went down in the window and is still down when the timer fires
        assertNull(s.flush(1380, touchDownMs = 1300))
        assertEquals(listOf(double1), s.onTouchEnd(tap1, 1300, 1500))
    }

    @Test
    fun somethingElseAfterATapReportsTheTapFirst() {
        val s = TapSequencer()
        s.onTouchEnd(tap1, 1000, 1080)
        // a swipe
        assertEquals(listOf(tap1), s.onTouchEnd(null, 1200, 1500))
        s.onTouchEnd(tap1, 2000, 2080)
        assertEquals(listOf(tap1, long2), s.onTouchEnd(long2, 2200, 2900))
        assertEquals(-1L, s.flushAtMs)
    }

    @Test
    fun multiFingerTapsAreReportedAtOnce() {
        val s = TapSequencer()
        assertEquals(listOf(tap3), s.onTouchEnd(tap3, 1000, 1080))
        assertEquals(-1L, s.flushAtMs)
    }

    @Test
    fun theBounceAfterAMultiFingerTapIsDropped() {
        val s = TapSequencer()
        s.onTouchEnd(tap3, 1000, 1080)
        assertEquals(emptyList<Gesture>(), s.onTouchEnd(tap1, 1240, 1290))
        assertEquals(-1L, s.flushAtMs)
        // past the bounce time taps count again
        assertEquals(emptyList<Gesture>(), s.onTouchEnd(tap1, 1330, 1400))
        assertEquals(tap1, s.flush(1700))
    }

    @Test
    fun aMultiFingerTapAfterATapDoesNotDouble() {
        val s = TapSequencer()
        s.onTouchEnd(tap1, 1000, 1080)
        assertEquals(listOf(tap1, tap3), s.onTouchEnd(tap3, 1200, 1280))
    }

    @Test
    fun longPressesPassThrough() {
        val s = TapSequencer()
        assertEquals(listOf(long2), s.onTouchEnd(long2, 1000, 1700))
    }

    @Test
    fun resetForgetsTheTapHeldBack() {
        val s = TapSequencer()
        s.onTouchEnd(tap1, 1000, 1080)
        s.reset()
        assertNull(s.flush(5000))
    }
}
