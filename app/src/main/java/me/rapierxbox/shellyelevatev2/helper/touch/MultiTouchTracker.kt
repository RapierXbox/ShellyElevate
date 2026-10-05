package me.rapierxbox.shellyelevatev2.helper.touch

// turns raw linux multitouch events into gestures. handles protocol b (slots and tracking ids)
// and the older protocol a (SYN_MT_REPORT separated contacts). no android types so it is unit tested
class MultiTouchTracker(private val listener: Listener) {

    interface Listener {
        // any frame with a finger down
        fun onTouchActivity() {}

        // once per gesture as soon as two or more fingers are down together
        fun onGestureStart(pointers: Int) {}

        // every finger lifted
        fun onGestureEnd(gesture: Gesture)
    }

    // raw device coordinates of one finger over the whole gesture
    class Track(val startX: Int, val startY: Int, var endX: Int, var endY: Int)

    class Gesture(
        val tracks: List<Track>,
        // most fingers down at the same time
        val maxPointers: Int,
        val startMs: Long,
        // when the last finger joined or startMs for a single finger
        val lastJoinMs: Long,
        val endMs: Long
    )

    private class Slot(var id: Int = NO_ID, var x: Int = UNKNOWN, var y: Int = UNKNOWN)

    // protocol b state
    private var currentSlot = 0
    private val slots = HashMap<Int, Slot>()

    // protocol a state
    private var protocolA = false
    private var pendingX = UNKNOWN
    private var pendingY = UNKNOWN
    private val frameA = ArrayList<IntArray>()

    // gesture state
    private val live = LinkedHashMap<Long, Track>()
    private val done = ArrayList<Track>()
    private var gestureActive = false
    private var startNotified = false
    private var maxPointers = 0
    private var startMs = 0L
    private var lastJoinMs = 0L

    fun onEvent(type: Int, code: Int, value: Int, nowMs: Long) {
        when (type) {
            EV_ABS -> onAbs(code, value)
            EV_SYN -> when (code) {
                SYN_REPORT -> commitFrame(nowMs)
                SYN_MT_REPORT -> {
                    protocolA = true
                    if (pendingX != UNKNOWN && pendingY != UNKNOWN) frameA += intArrayOf(pendingX, pendingY)
                    pendingX = UNKNOWN
                    pendingY = UNKNOWN
                }
                // the kernel dropped events so whatever we track is wrong now
                SYN_DROPPED -> reset()
            }
        }
    }

    fun reset() {
        slots.clear()
        currentSlot = 0
        frameA.clear()
        pendingX = UNKNOWN
        pendingY = UNKNOWN
        clearGesture()
    }

    private fun onAbs(code: Int, value: Int) {
        when (code) {
            ABS_MT_SLOT -> currentSlot = value
            ABS_MT_TRACKING_ID -> {
                val slot = slots.getOrPut(currentSlot) { Slot() }
                slot.id = if (value < 0) NO_ID else value
            }
            ABS_MT_POSITION_X -> if (protocolA) pendingX = value else slots.getOrPut(currentSlot) { Slot() }.x = value
            ABS_MT_POSITION_Y -> if (protocolA) pendingY = value else slots.getOrPut(currentSlot) { Slot() }.y = value
        }
    }

    private fun commitFrame(nowMs: Long) {
        val active = LinkedHashMap<Long, IntArray>()
        if (protocolA) {
            // contacts carry no identity so the order within a frame stands in for it
            frameA.forEachIndexed { index, xy -> active[index.toLong()] = xy }
            frameA.clear()
        } else {
            for ((index, slot) in slots) {
                if (slot.id == NO_ID || slot.x == UNKNOWN || slot.y == UNKNOWN) continue
                active[(index.toLong() shl 32) or (slot.id.toLong() and 0xffffffffL)] = intArrayOf(slot.x, slot.y)
            }
        }

        // fingers that lifted keep their last position as the end
        val lifted = live.keys.filter { it !in active }
        for (key in lifted) live.remove(key)?.let { done += it }

        for ((key, xy) in active) {
            val track = live[key]
            if (track == null) {
                live[key] = Track(xy[0], xy[1], xy[0], xy[1])
                if (!gestureActive) {
                    gestureActive = true
                    startMs = nowMs
                } else {
                    lastJoinMs = nowMs
                }
            } else {
                track.endX = xy[0]
                track.endY = xy[1]
            }
        }

        if (live.isNotEmpty()) {
            if (live.size > maxPointers) maxPointers = live.size
            if (maxPointers >= 2 && !startNotified) {
                startNotified = true
                listener.onGestureStart(maxPointers)
            }
            listener.onTouchActivity()
            return
        }

        if (gestureActive) {
            val gesture = Gesture(
                tracks = done.toList(),
                maxPointers = maxPointers,
                startMs = startMs,
                lastJoinMs = if (lastJoinMs > 0) lastJoinMs else startMs,
                endMs = nowMs
            )
            clearGesture()
            listener.onGestureEnd(gesture)
        }
    }

    private fun clearGesture() {
        live.clear()
        done.clear()
        gestureActive = false
        startNotified = false
        maxPointers = 0
        startMs = 0
        lastJoinMs = 0
    }

    companion object {
        const val EV_SYN = 0x00
        const val EV_ABS = 0x03
        const val SYN_REPORT = 0
        const val SYN_MT_REPORT = 2
        const val SYN_DROPPED = 3
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39

        private const val NO_ID = -1
        private const val UNKNOWN = Int.MIN_VALUE
    }
}
