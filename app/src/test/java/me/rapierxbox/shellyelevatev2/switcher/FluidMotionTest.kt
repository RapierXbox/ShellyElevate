package me.rapierxbox.shellyelevatev2.switcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidMotionTest {

    @Test
    fun projectionMatchesScrollViewDeceleration() {
        // uikit normal deceleration throws about half a second worth of the release velocity
        assertEquals(499f, FluidMotion.project(1000f), 1f)
        assertEquals(-998f, FluidMotion.project(-2000f), 1f)
    }

    @Test
    fun rubberBandFollowsSmallPullsAndLevelsOff() {
        val width = 720f
        assertEquals(0f, FluidMotion.rubberBand(0f, width), 0.001f)
        assertTrue(FluidMotion.rubberBand(10f, width) > 5f)
        assertTrue(FluidMotion.rubberBand(10000f, width) < width)
        assertEquals(-FluidMotion.rubberBand(200f, width), FluidMotion.rubberBand(-200f, width), 0.001f)
    }

    @Test
    fun stiffnessFromResponse() {
        // a one second response is one full oscillation per second
        assertEquals(39.48f, FluidMotion.stiffness(1f), 0.01f)
        assertTrue(FluidMotion.stiffness(0.3f) > FluidMotion.stiffness(0.5f))
    }
}
