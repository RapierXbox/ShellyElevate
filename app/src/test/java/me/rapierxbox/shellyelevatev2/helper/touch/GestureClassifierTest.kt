package me.rapierxbox.shellyelevatev2.helper.touch

import me.rapierxbox.shellyelevatev2.helper.touch.GestureClassifier.Edge
import me.rapierxbox.shellyelevatev2.helper.touch.GestureClassifier.Gesture
import me.rapierxbox.shellyelevatev2.helper.touch.GestureClassifier.Kind
import me.rapierxbox.shellyelevatev2.helper.touch.SwipeClassifier.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// screen is the 720x720 wall display panel
class GestureClassifierTest {

    private val screen = 720f

    private fun press(tracks: List<Track>, pointers: Int = tracks.size, durationMs: Long = 80) =
        GestureClassifier.classifyPress(tracks, pointers, durationMs, screen)

    private fun edge(track: Track, durationMs: Long = 200, pointers: Int = 1) =
        GestureClassifier.classifyEdge(listOf(track), pointers, durationMs, screen, screen)

    private fun still(x: Float, y: Float) = Track(x, y, x + 3f, y - 2f)

    @Test
    fun oneFingerTap() {
        val tap = press(listOf(still(300f, 300f)))
        assertEquals(Gesture(Kind.TAP, 1), tap)
        assertEquals("tap_1", tap?.id)
    }

    @Test
    fun threeFingerTap() {
        assertEquals(Gesture(Kind.TAP, 3), press(listOf(still(200f, 300f), still(300f, 280f), still(400f, 300f))))
    }

    @Test
    fun fingerCountIsClampedToFive() {
        val tracks = (0 until 6).map { still(100f + it * 80, 400f) }
        assertEquals(Gesture(Kind.TAP, 5), press(tracks))
    }

    @Test
    fun heldTouchIsALongPress() {
        val long = press(listOf(still(300f, 300f), still(400f, 300f)), durationMs = 600)
        assertEquals(Gesture(Kind.LONG_PRESS, 2), long)
        assertEquals("long_press_2", long?.id)
        assertEquals(Gesture(Kind.TAP, 2), press(listOf(still(300f, 300f), still(400f, 300f)), durationMs = 599))
    }

    @Test
    fun aFingerThatMovedIsNoPress() {
        assertNull(press(listOf(Track(300f, 300f, 300f, 330f))))
        assertNull(press(listOf(still(300f, 300f), Track(400f, 300f, 430f, 300f))))
    }

    @Test
    fun slopScalesWithTheScreenButNeverBelowTheMinimum() {
        assertEquals(14.4f, GestureClassifier.slopPx(720f), 0.001f)
        assertEquals(12f, GestureClassifier.slopPx(320f), 0.001f)
    }

    @Test
    fun edgesMovingInwards() {
        assertEquals(Gesture(Kind.EDGE, 1, Edge.LEFT), edge(Track(10f, 360f, 250f, 370f)))
        assertEquals(Gesture(Kind.EDGE, 1, Edge.RIGHT), edge(Track(710f, 360f, 480f, 350f)))
        assertEquals(Gesture(Kind.EDGE, 1, Edge.TOP), edge(Track(360f, 8f, 365f, 260f)))
        assertEquals(Gesture(Kind.EDGE, 1, Edge.BOTTOM), edge(Track(360f, 712f, 350f, 470f)))
        assertEquals("edge_bottom", edge(Track(360f, 712f, 350f, 470f))?.id)
    }

    @Test
    fun aSwipeThatStartsInsideThePanelIsNoEdge() {
        assertNull(edge(Track(60f, 360f, 300f, 360f)))
        assertNull(edge(Track(360f, 600f, 360f, 300f)))
    }

    @Test
    fun aSwipeAlongOrOutOfTheEdgeIsNoEdge() {
        // along the left edge
        assertNull(edge(Track(10f, 200f, 12f, 500f)))
        // starts at the right edge but moves further right
        assertNull(edge(Track(600f, 360f, 715f, 360f)))
    }

    @Test
    fun edgeNeedsTravelAndOneFinger() {
        // too short even for a flick
        assertNull(edge(Track(10f, 360f, 60f, 360f)))
        // far enough but crawling
        assertNull(edge(Track(10f, 360f, 250f, 360f), durationMs = 5000))
        // a quick flick over a tenth of the panel
        assertEquals(Gesture(Kind.EDGE, 1, Edge.LEFT), edge(Track(10f, 360f, 90f, 360f), durationMs = 100))
        assertNull(edge(Track(10f, 360f, 250f, 360f), pointers = 2))
    }

    @Test
    fun allIdsAreUnique() {
        val ids = GestureClassifier.allIds()
        assertEquals(15, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.containsAll(listOf("tap_1", "double_tap_1", "long_press_5", "edge_top")))
    }
}
