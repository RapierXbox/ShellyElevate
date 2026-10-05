package me.rapierxbox.shellyelevatev2.helper.touch

import me.rapierxbox.shellyelevatev2.helper.touch.SwipeClassifier.Direction
import me.rapierxbox.shellyelevatev2.helper.touch.SwipeClassifier.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeClassifierTest {

    private val minDistance = 240f

    private fun classify(tracks: List<Track>, pointers: Int = tracks.size, durationMs: Long = 200) =
        SwipeClassifier.classify(tracks, pointers, durationMs, minDistance)

    @Test
    fun twoFingersUp() {
        val swipe = classify(listOf(Track(300f, 600f, 300f, 200f), Track(400f, 610f, 400f, 205f)))
        assertEquals(SwipeClassifier.Swipe(2, Direction.UP), swipe)
        assertEquals("swipe_2_up", swipe?.id)
    }

    @Test
    fun threeFingersRight() {
        val swipe = classify(listOf(
            Track(100f, 300f, 500f, 310f),
            Track(100f, 400f, 520f, 395f),
            Track(100f, 500f, 480f, 505f)
        ))
        assertEquals(SwipeClassifier.Swipe(3, Direction.RIGHT), swipe)
    }

    @Test
    fun fingerCountIsClampedToFive() {
        val tracks = (0 until 6).map { Track(100f + it * 50, 700f, 100f + it * 50, 200f) }
        assertEquals(SwipeClassifier.Swipe(5, Direction.UP), classify(tracks))
    }

    @Test
    fun singleFingerIsVerticalOnly() {
        assertEquals(SwipeClassifier.Swipe(1, Direction.DOWN), classify(listOf(Track(300f, 100f, 300f, 600f))))
        assertNull(classify(listOf(Track(100f, 300f, 600f, 300f))))
    }

    @Test
    fun slowOrShortGesturesAreNotSwipes() {
        assertNull(classify(listOf(Track(300f, 600f, 300f, 200f), Track(400f, 600f, 400f, 200f)), durationMs = 2000))
        assertNull(classify(listOf(Track(300f, 600f, 300f, 500f), Track(400f, 600f, 400f, 500f))))
    }

    @Test
    fun pinchIsNotASwipe() {
        // both move up but they also spread far apart
        val swipe = classify(listOf(Track(300f, 600f, 50f, 300f), Track(400f, 600f, 700f, 280f)))
        assertNull(swipe)
    }

    @Test
    fun fingersDisagreeingOnDirectionAreNotASwipe() {
        // the mean is a clear upward swipe but one finger went the other way
        assertNull(classify(listOf(
            Track(300f, 800f, 300f, 200f),
            Track(400f, 800f, 400f, 200f),
            Track(500f, 300f, 500f, 400f)
        )))
    }
}
