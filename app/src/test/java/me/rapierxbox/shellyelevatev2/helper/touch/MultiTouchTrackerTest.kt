package me.rapierxbox.shellyelevatev2.helper.touch

import me.rapierxbox.shellyelevatev2.helper.touch.MultiTouchTracker.Companion.ABS_MT_POSITION_X
import me.rapierxbox.shellyelevatev2.helper.touch.MultiTouchTracker.Companion.ABS_MT_POSITION_Y
import me.rapierxbox.shellyelevatev2.helper.touch.MultiTouchTracker.Companion.ABS_MT_SLOT
import me.rapierxbox.shellyelevatev2.helper.touch.MultiTouchTracker.Companion.ABS_MT_TRACKING_ID
import me.rapierxbox.shellyelevatev2.helper.touch.MultiTouchTracker.Companion.EV_ABS
import me.rapierxbox.shellyelevatev2.helper.touch.MultiTouchTracker.Companion.EV_SYN
import me.rapierxbox.shellyelevatev2.helper.touch.MultiTouchTracker.Companion.SYN_MT_REPORT
import me.rapierxbox.shellyelevatev2.helper.touch.MultiTouchTracker.Companion.SYN_REPORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MultiTouchTrackerTest {

    private val ended = mutableListOf<MultiTouchTracker.Gesture>()
    private val started = mutableListOf<Int>()
    private lateinit var tracker: MultiTouchTracker
    private var now = 0L

    @Before
    fun setUp() {
        tracker = MultiTouchTracker(object : MultiTouchTracker.Listener {
            override fun onGestureStart(pointers: Int) {
                started += pointers
            }

            override fun onGestureEnd(gesture: MultiTouchTracker.Gesture) {
                ended += gesture
            }
        })
    }

    private fun abs(code: Int, value: Int) = tracker.onEvent(EV_ABS, code, value, now)
    private fun syn() = tracker.onEvent(EV_SYN, SYN_REPORT, 0, now)

    // protocol b frame for one slot
    private fun slot(slot: Int, id: Int?, x: Int? = null, y: Int? = null) {
        abs(ABS_MT_SLOT, slot)
        if (id != null) abs(ABS_MT_TRACKING_ID, id)
        if (x != null) abs(ABS_MT_POSITION_X, x)
        if (y != null) abs(ABS_MT_POSITION_Y, y)
    }

    @Test
    fun protocolBTwoFingerSwipe() {
        slot(0, 10, 300, 600)
        syn()
        now = 20
        slot(1, 11, 400, 610)
        syn()
        assertEquals(listOf(2), started)

        for (step in 1..5) {
            now += 30
            slot(0, null, y = 600 - step * 80)
            slot(1, null, y = 610 - step * 80)
            syn()
        }
        now += 10
        slot(0, -1)
        syn()
        now += 10
        slot(1, -1)
        syn()

        assertEquals(1, ended.size)
        val g = ended.first()
        assertEquals(2, g.maxPointers)
        assertEquals(2, g.tracks.size)
        assertEquals(0L, g.startMs)
        assertEquals(20L, g.lastJoinMs)
        assertEquals(200, g.tracks[0].endY)
        assertEquals(210, g.tracks[1].endY)
    }

    @Test
    fun newTrackingIdInSameSlotIsANewFinger() {
        slot(0, 1, 100, 100)
        slot(1, 2, 200, 100)
        syn()
        // finger in slot 0 lifts and a new one lands in the same slot within one frame
        slot(0, 3, 500, 500)
        syn()
        slot(0, -1)
        slot(1, -1)
        syn()
        val g = ended.single()
        assertEquals(3, g.tracks.size)
        assertEquals(2, g.maxPointers)
    }

    @Test
    fun liveMeanDeltaFollowsTheFingersDown() {
        slot(0, 1, 100, 500)
        slot(1, 2, 300, 500)
        syn()
        slot(0, null, y = 400)
        slot(1, null, y = 300)
        syn()
        val out = FloatArray(2)
        tracker.liveMeanDelta(out)
        assertEquals(2, tracker.liveCount)
        assertEquals(0f, out[0], 0.001f)
        assertEquals(-150f, out[1], 0.001f)
    }

    // the mtk-tpd panel of the stargate wall display. protocol a with tracking ids and BTN_TOUCH
    // and the first SYN_MT_REPORT only arrives after the first contact position
    @Test
    fun mtkProtocolATwoFingerSwipeUpIsClassified() {
        val ys = listOf(620, 560, 480, 400, 320, 240, 170, 120)
        ys.forEachIndexed { i, y ->
            now = i * 45L
            if (i == 0) tracker.onEvent(0x01, 0x14a, 1, now)
            for ((id, x) in listOf(0 to 260, 1 to 460)) {
                abs(ABS_MT_TRACKING_ID, id)
                abs(ABS_MT_POSITION_X, x)
                abs(ABS_MT_POSITION_Y, y)
                tracker.onEvent(EV_SYN, SYN_MT_REPORT, 0, now)
            }
            syn()
        }
        now += 45
        tracker.onEvent(0x01, 0x14a, 0, now)
        tracker.onEvent(EV_SYN, SYN_MT_REPORT, 0, now)
        syn()

        val g = ended.single()
        assertEquals(2, g.maxPointers)
        assertEquals(2, g.tracks.size)
        // both fingers keep their own column from the very first frame
        assertEquals(260, g.tracks[0].startX)
        assertEquals(620, g.tracks[0].startY)
        val tracks = g.tracks.map { SwipeClassifier.Track(it.startX.toFloat(), it.startY.toFloat(), it.endX.toFloat(), it.endY.toFloat()) }
        val swipe = SwipeClassifier.classify(tracks, g.maxPointers, g.endMs - g.lastJoinMs, 720f)
        assertEquals("swipe_2_up", swipe?.id)
    }

    @Test
    fun protocolBSingleTapHasOneTrack() {
        slot(0, 5, 100, 100)
        syn()
        now = 80
        slot(0, -1)
        syn()
        assertEquals(1, ended.size)
        assertEquals(1, ended.first().maxPointers)
        assertTrue(started.isEmpty())
    }

    // protocol a contact with an optional tracking id
    private fun contact(x: Int, y: Int, id: Int? = null) {
        if (id != null) abs(ABS_MT_TRACKING_ID, id)
        abs(ABS_MT_POSITION_X, x)
        abs(ABS_MT_POSITION_Y, y)
        tracker.onEvent(EV_SYN, SYN_MT_REPORT, 0, now)
    }

    private fun liftAll() {
        tracker.onEvent(EV_SYN, SYN_MT_REPORT, 0, now)
        syn()
    }

    // the left finger lifts first so the right one becomes the first contact of the frame
    // with ids the right finger must keep its own track instead of taking over the left one
    @Test
    fun protocolATrackingIdsSurviveAFingerLifting() {
        contact(260, 620, id = 0)
        contact(460, 610, id = 1)
        syn()
        now = 40
        contact(262, 500, id = 0)
        contact(458, 490, id = 1)
        syn()
        now = 80
        contact(455, 380, id = 1)
        syn()
        now = 120
        contact(452, 260, id = 1)
        syn()
        now = 140
        liftAll()

        val g = ended.single()
        assertEquals(2, g.maxPointers)
        assertEquals(2, g.tracks.size)
        val left = g.tracks.single { it.startX == 260 }
        val right = g.tracks.single { it.startX == 460 }
        assertEquals(262, left.endX)
        assertEquals(500, left.endY)
        assertEquals(452, right.endX)
        assertEquals(260, right.endY)
    }

    @Test
    fun protocolAWithoutIdsMatchesTheNearestFinger() {
        contact(260, 620)
        contact(460, 610)
        syn()
        now = 40
        // reported in the other order this time
        contact(458, 500)
        contact(262, 510)
        syn()
        now = 80
        contact(455, 380)
        syn()
        now = 120
        liftAll()

        val g = ended.single()
        assertEquals(2, g.tracks.size)
        val left = g.tracks.single { it.startX == 260 }
        val right = g.tracks.single { it.startX == 460 }
        assertEquals(262, left.endX)
        assertEquals(510, left.endY)
        assertEquals(455, right.endX)
        assertEquals(380, right.endY)
    }

    @Test
    fun protocolANewTrackingIdIsANewFinger() {
        contact(100, 100, id = 0)
        syn()
        now = 30
        // the finger lifts and another one lands elsewhere in the same frame
        contact(500, 500, id = 1)
        syn()
        now = 60
        liftAll()

        val g = ended.single()
        assertEquals(2, g.tracks.size)
        assertEquals(1, g.maxPointers)
        assertEquals(100, g.tracks[0].endX)
        assertEquals(500, g.tracks[1].startX)
    }

    @Test
    fun protocolAEmptyFrameLiftsEveryFinger() {
        contact(300, 600)
        contact(400, 600)
        syn()
        now = 100
        contact(300, 200)
        contact(400, 200)
        syn()
        now = 120
        // an empty frame means every finger lifted
        tracker.onEvent(EV_SYN, SYN_MT_REPORT, 0, now)
        syn()

        assertEquals(1, ended.size)
        val g = ended.first()
        assertEquals(2, g.maxPointers)
        assertEquals(200, g.tracks[0].endY)
        assertEquals(600, g.tracks[0].startY)
    }
}
