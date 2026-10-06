package me.rapierxbox.shellyelevatev2.helper.touch

import me.rapierxbox.shellyelevatev2.helper.touch.SwipeClassifier.Direction
import me.rapierxbox.shellyelevatev2.helper.touch.SwipeClassifier.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// screen is the 720x720 wall display panel
class SwipeClassifierTest {

    private val screen = 720f

    private fun classify(tracks: List<Track>, pointers: Int = tracks.size, durationMs: Long = 200) =
        SwipeClassifier.classify(tracks, pointers, durationMs, screen)

    private val twoUp = SwipeClassifier.Swipe(2, Direction.UP)

    @Test
    fun twoFingersUp() {
        val swipe = classify(listOf(Track(300f, 600f, 300f, 200f), Track(400f, 610f, 400f, 205f)))
        assertEquals(twoUp, swipe)
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
    fun singleFingerStaysStrict() {
        // a quick dashboard scroll shorter than a third of the screen
        assertNull(classify(listOf(Track(300f, 500f, 300f, 300f)), durationMs = 100))
        // a long but slow drag
        assertNull(classify(listOf(Track(300f, 600f, 300f, 200f)), durationMs = 1000))
        // a fast diagonal drag
        assertNull(classify(listOf(Track(100f, 600f, 600f, 250f)), durationMs = 200))
    }

    @Test
    fun twoFingerTapIsNotASwipe() {
        assertNull(classify(listOf(Track(300f, 400f, 304f, 395f), Track(400f, 400f, 398f, 396f)), durationMs = 90))
    }

    @Test
    fun shortSlowGestureIsNotASwipe() {
        assertNull(classify(listOf(Track(300f, 500f, 300f, 400f), Track(400f, 500f, 400f, 400f)), durationMs = 1000))
    }

    @Test
    fun slowLongSwipeCounts() {
        assertEquals(twoUp, classify(listOf(Track(300f, 600f, 300f, 300f), Track(400f, 600f, 400f, 290f)), durationMs = 1500))
        // far too slow to be meant as a swipe
        assertNull(classify(listOf(Track(300f, 600f, 300f, 300f), Track(400f, 600f, 400f, 290f)), durationMs = 5000))
    }

    @Test
    fun shortFastFlickCounts() {
        assertEquals(twoUp, classify(listOf(Track(300f, 500f, 300f, 410f), Track(400f, 505f, 405f, 420f)), durationMs = 120))
    }

    @Test
    fun unevenFingerSpeedsCount() {
        // the middle finger outruns the index finger and drifts sideways a little
        assertEquals(twoUp, classify(listOf(Track(300f, 600f, 290f, 300f), Track(400f, 610f, 410f, 490f)), durationMs = 300))
    }

    @Test
    fun lateSecondFingerCounts() {
        // the second finger lands when the first is already on its way so its track is short
        // and the duration counts from its landing
        assertEquals(twoUp, classify(listOf(Track(300f, 620f, 305f, 250f), Track(400f, 560f, 398f, 400f)), durationMs = 150))
    }

    @Test
    fun fingerLiftingEarlyCounts() {
        assertEquals(twoUp, classify(listOf(Track(300f, 620f, 300f, 200f), Track(400f, 610f, 395f, 480f)), durationMs = 350))
    }

    @Test
    fun barelyMovingFingerDoesNotVeto() {
        // the third finger rests and even creeps backwards a few pixels
        val swipe = classify(listOf(
            Track(250f, 600f, 250f, 300f),
            Track(350f, 600f, 350f, 290f),
            Track(450f, 600f, 452f, 608f)
        ))
        assertEquals(SwipeClassifier.Swipe(3, Direction.UP), swipe)
    }

    @Test
    fun oneMovingFingerNextToARestingOneIsNotASwipe() {
        assertNull(classify(listOf(Track(300f, 600f, 300f, 300f), Track(400f, 600f, 403f, 595f))))
    }

    @Test
    fun pinchIsNotASwipe() {
        // both move up but they also spread far apart
        assertNull(classify(listOf(Track(300f, 600f, 50f, 300f), Track(400f, 600f, 700f, 280f))))
        // plain zoom out
        assertNull(classify(listOf(Track(300f, 400f, 150f, 400f), Track(420f, 400f, 600f, 400f))))
        // zoom in while the hand moves up
        assertNull(classify(listOf(Track(150f, 600f, 330f, 420f), Track(600f, 600f, 390f, 430f))))
    }

    @Test
    fun rotationIsNotASwipe() {
        assertNull(classify(listOf(Track(300f, 400f, 300f, 200f), Track(450f, 400f, 450f, 600f))))
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

    @Test
    fun analyzeReportsTravelOfTheFastestFinger() {
        val motion = SwipeClassifier.Motion()
        val ok = SwipeClassifier.analyze(
            2,
            floatArrayOf(300f, 400f), floatArrayOf(600f, 600f),
            floatArrayOf(300f, 400f), floatArrayOf(300f, 500f),
            20f, motion
        )
        assertEquals(true, ok)
        assertEquals(Direction.UP, motion.direction)
        assertEquals(300f, motion.travel, 0.01f)
        assertEquals(200f, motion.meanTravel, 0.01f)
    }
}
