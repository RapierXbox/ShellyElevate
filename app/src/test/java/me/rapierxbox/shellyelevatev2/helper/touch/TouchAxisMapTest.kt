package me.rapierxbox.shellyelevatev2.helper.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchAxisMapTest {

    @Test
    fun identityStaysWhenAxesAgree() {
        val map = TouchAxisMap()
        repeat(3) { assertFalse(map.learn(0f, -0.4f, 0f, -0.4f)) }
        assertEquals(0, map.encode())
    }

    @Test
    fun swappedPanelIsLearnedAfterTwoGestures() {
        val map = TouchAxisMap()
        // finger moved up the screen while the raw x axis grew
        assertFalse(map.learn(0.4f, 0f, 0f, -0.4f))
        assertTrue(map.learn(0.4f, 0f, 0f, -0.4f))
        assertTrue(map.swap)
        assertTrue(map.invertY)
        val out = FloatArray(2)
        map.mapDelta(0.4f, 0f, out)
        assertEquals(0f, out[0], 0.001f)
        assertEquals(-0.4f, out[1], 0.001f)
    }

    @Test
    fun smallOrDiagonalMovesAreIgnored() {
        val map = TouchAxisMap()
        repeat(5) { map.learn(0.05f, 0f, 0f, -0.05f) }
        repeat(5) { map.learn(0.3f, 0.3f, -0.3f, 0.3f) }
        assertEquals(0, map.encode())
    }

    @Test
    fun guessSwapsWhenLongSidesDisagree() {
        assertTrue(TouchAxisMap.guess(800, 480, 480, 800).swap)
        assertFalse(TouchAxisMap.guess(720, 720, 720, 720).swap)
        assertFalse(TouchAxisMap.guess(1280, 800, 1280, 800).swap)
    }
}
