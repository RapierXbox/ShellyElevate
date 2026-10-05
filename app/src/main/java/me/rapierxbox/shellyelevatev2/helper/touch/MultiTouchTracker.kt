package me.rapierxbox.shellyelevatev2.helper.touch

// turns raw linux multitouch events into gestures. handles protocol b (slots and tracking ids)
// and the older protocol a (SYN_MT_REPORT separated contacts). no android types so it is unit tested
// it sees every touch on the panel so a frame allocates nothing and only a new finger creates a Track
class MultiTouchTracker(private val listener: Listener) {

    interface Listener {
        // every frame with a finger down. keep it cheap
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

    // protocol b slot state indexed by slot
    private var currentSlot = 0
    private val slotId = IntArray(MAX_CONTACTS) { NO_ID }
    private val slotX = IntArray(MAX_CONTACTS) { UNKNOWN }
    private val slotY = IntArray(MAX_CONTACTS) { UNKNOWN }

    // protocol a contacts of the frame being read in report order
    private var protocolA = false
    private var pendingX = UNKNOWN
    private var pendingY = UNKNOWN
    private var frameCount = 0
    private val frameX = IntArray(MAX_CONTACTS)
    private val frameY = IntArray(MAX_CONTACTS)

    // the finger held by each slot or contact index and the tracking id it had when it went down
    private val live = arrayOfNulls<Track>(MAX_CONTACTS)
    private val liveId = IntArray(MAX_CONTACTS) { NO_ID }
    private val done = ArrayList<Track>(MAX_CONTACTS)

    private var gestureActive = false
    private var startNotified = false
    private var maxPointers = 0
    private var startMs = 0L
    private var lastJoinMs = 0L

    // fingers down right now
    var liveCount = 0
        private set

    fun onEvent(type: Int, code: Int, value: Int, nowMs: Long) {
        when (type) {
            EV_ABS -> onAbs(code, value)
            EV_SYN -> when (code) {
                SYN_REPORT -> commitFrame(nowMs)
                SYN_MT_REPORT -> {
                    protocolA = true
                    if (pendingX != UNKNOWN && pendingY != UNKNOWN && frameCount < MAX_CONTACTS) {
                        frameX[frameCount] = pendingX
                        frameY[frameCount] = pendingY
                        frameCount++
                    }
                    pendingX = UNKNOWN
                    pendingY = UNKNOWN
                }
                // the kernel dropped events so whatever we track is wrong now
                SYN_DROPPED -> reset()
            }
        }
    }

    fun reset() {
        slotId.fill(NO_ID)
        slotX.fill(UNKNOWN)
        slotY.fill(UNKNOWN)
        currentSlot = 0
        frameCount = 0
        pendingX = UNKNOWN
        pendingY = UNKNOWN
        clearGesture()
    }

    // mean movement of the fingers down right now in raw units written to out as dx dy
    fun liveMeanDelta(out: FloatArray) {
        var dx = 0f
        var dy = 0f
        var n = 0
        for (track in live) {
            if (track == null) continue
            dx += track.endX - track.startX
            dy += track.endY - track.startY
            n++
        }
        out[0] = if (n > 0) dx / n else 0f
        out[1] = if (n > 0) dy / n else 0f
    }

    private fun onAbs(code: Int, value: Int) {
        when (code) {
            ABS_MT_SLOT -> currentSlot = value
            ABS_MT_TRACKING_ID -> if (currentSlot in 0 until MAX_CONTACTS) {
                slotId[currentSlot] = if (value < 0) NO_ID else value
            }
            ABS_MT_POSITION_X -> when {
                protocolA -> pendingX = value
                currentSlot in 0 until MAX_CONTACTS -> slotX[currentSlot] = value
            }
            ABS_MT_POSITION_Y -> when {
                protocolA -> pendingY = value
                currentSlot in 0 until MAX_CONTACTS -> slotY[currentSlot] = value
            }
        }
    }

    private fun commitFrame(nowMs: Long) {
        var count = 0
        for (i in 0 until MAX_CONTACTS) {
            val down: Boolean
            val x: Int
            val y: Int
            val id: Int
            if (protocolA) {
                // contacts carry no identity so the order within a frame stands in for it
                down = i < frameCount
                x = frameX[i]
                y = frameY[i]
                id = 0
            } else {
                id = slotId[i]
                x = slotX[i]
                y = slotY[i]
                down = id != NO_ID && x != UNKNOWN && y != UNKNOWN
            }

            val track = live[i]
            // a new tracking id in the same slot is a new finger
            if (track != null && (!down || liveId[i] != id)) {
                done += track
                live[i] = null
            }
            if (!down) continue
            count++
            val current = live[i]
            if (current == null) {
                live[i] = Track(x, y, x, y)
                liveId[i] = id
                if (!gestureActive) {
                    gestureActive = true
                    startMs = nowMs
                } else {
                    lastJoinMs = nowMs
                }
            } else {
                current.endX = x
                current.endY = y
            }
        }
        frameCount = 0
        liveCount = count

        if (count > 0) {
            if (count > maxPointers) maxPointers = count
            if (maxPointers >= 2 && !startNotified) {
                startNotified = true
                listener.onGestureStart(maxPointers)
            }
            listener.onTouchActivity()
            return
        }

        if (gestureActive) {
            val gesture = Gesture(
                tracks = ArrayList(done),
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
        live.fill(null)
        liveId.fill(NO_ID)
        liveCount = 0
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

        // panels report at most ten fingers and more is never a gesture we care about
        private const val MAX_CONTACTS = 10
        private const val NO_ID = -1
        private const val UNKNOWN = Int.MIN_VALUE
    }
}
