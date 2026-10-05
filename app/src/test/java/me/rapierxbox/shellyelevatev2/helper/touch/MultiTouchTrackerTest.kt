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

    @Test
    fun protocolAContactsByOrder() {
        fun contact(x: Int, y: Int) {
            abs(ABS_MT_POSITION_X, x)
            abs(ABS_MT_POSITION_Y, y)
            tracker.onEvent(EV_SYN, SYN_MT_REPORT, 0, now)
        }
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
