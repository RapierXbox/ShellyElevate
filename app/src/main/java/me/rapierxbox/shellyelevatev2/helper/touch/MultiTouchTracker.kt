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
    private var pendingId = NO_ID
    private var frameCount = 0
    private val frameX = IntArray(MAX_CONTACTS)
    private val frameY = IntArray(MAX_CONTACTS)
    private val frameId = IntArray(MAX_CONTACTS)

    // protocol a matching scratch. which index each contact continues and which indices are taken
    private val contactIndex = IntArray(MAX_CONTACTS)
    private val contactNew = BooleanArray(MAX_CONTACTS)
    private val indexTaken = BooleanArray(MAX_CONTACTS)
    private var nextSyntheticId = 0

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
                        frameId[frameCount] = pendingId
                        frameCount++
                    }
                    pendingX = UNKNOWN
                    pendingY = UNKNOWN
                    pendingId = NO_ID
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
        pendingId = NO_ID
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
            ABS_MT_TRACKING_ID -> {
                val id = if (value < 0) NO_ID else value
                pendingId = id
                if (!protocolA && currentSlot in 0 until MAX_CONTACTS) slotId[currentSlot] = id
            }
            // both views are kept since protocol a only reveals itself at the first SYN_MT_REPORT
            // which comes after the position of the first contact
            ABS_MT_POSITION_X -> {
                pendingX = value
                if (!protocolA && currentSlot in 0 until MAX_CONTACTS) slotX[currentSlot] = value
            }
            ABS_MT_POSITION_Y -> {
                pendingY = value
                if (!protocolA && currentSlot in 0 until MAX_CONTACTS) slotY[currentSlot] = value
            }
        }
    }

    private fun commitFrame(nowMs: Long) {
        if (protocolA) matchProtocolA()
        var count = 0
        for (i in 0 until MAX_CONTACTS) {
            val id = slotId[i]
            val x = slotX[i]
            val y = slotY[i]
            val down = id != NO_ID && x != UNKNOWN && y != UNKNOWN

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

    // protocol a contacts come in no fixed order and the order shifts when a finger lifts
    // so each contact is matched to the finger it continues and written to that slot
    // by tracking id when the panel sends them and otherwise by the nearest last position
    private fun matchProtocolA() {
        indexTaken.fill(false)
        var withIds = frameCount > 0
        for (c in 0 until frameCount) {
            contactIndex[c] = -1
            contactNew[c] = false
            if (frameId[c] == NO_ID) withIds = false
        }
        if (withIds) {
            for (c in 0 until frameCount) {
                for (i in 0 until MAX_CONTACTS) {
                    if (!indexTaken[i] && live[i] != null && liveId[i] == frameId[c]) {
                        contactIndex[c] = i
                        indexTaken[i] = true
                        break
                    }
                }
            }
        } else {
            // greedy closest pairs first which is exact for the usual one or two fingers
            while (true) {
                var best = Long.MAX_VALUE
                var bestContact = -1
                var bestIndex = -1
                for (c in 0 until frameCount) {
                    if (contactIndex[c] >= 0) continue
                    for (i in 0 until MAX_CONTACTS) {
                        val track = live[i] ?: continue
                        if (indexTaken[i]) continue
                        val dx = (frameX[c] - track.endX).toLong()
                        val dy = (frameY[c] - track.endY).toLong()
                        val d = dx * dx + dy * dy
                        if (d < best) {
                            best = d
                            bestContact = c
                            bestIndex = i
                        }
                    }
                }
                if (bestContact < 0) break
                contactIndex[bestContact] = bestIndex
                indexTaken[bestIndex] = true
            }
        }
        // new fingers take an empty index and only if none is left one whose finger lifted
        for (c in 0 until frameCount) {
            if (contactIndex[c] >= 0) continue
            var free = -1
            for (i in 0 until MAX_CONTACTS) {
                if (indexTaken[i]) continue
                if (live[i] == null) {
                    free = i
                    break
                }
                if (free < 0) free = i
            }
            if (free < 0) continue
            contactIndex[c] = free
            contactNew[c] = true
            indexTaken[free] = true
        }

        slotId.fill(NO_ID)
        for (c in 0 until frameCount) {
            val i = contactIndex[c]
            if (i < 0) continue
            slotX[i] = frameX[c]
            slotY[i] = frameY[c]
            slotId[i] = when {
                withIds -> frameId[c]
                !contactNew[c] -> liveId[i]
                else -> newSyntheticId()
            }
        }
    }

    // stands in for a tracking id so a new finger in a lifted fingers index starts a new track
    private fun newSyntheticId(): Int {
        nextSyntheticId = (nextSyntheticId + 1) and Int.MAX_VALUE
        return nextSyntheticId
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
