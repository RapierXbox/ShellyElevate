package me.rapierxbox.shellyelevatev2.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class MediaQueueTest {
    private static MediaQueue.Track t(String url) {
        return MediaQueue.Track.of(url);
    }

    @Test
    public void replaceClearsAndPlays() {
        MediaQueue q = new MediaQueue();
        q.enqueue(t("a"), MediaQueue.ENQUEUE_ADD, false);
        q.enqueue(t("b"), MediaQueue.ENQUEUE_ADD, true);
        MediaQueue.Track c = t("c");
        assertSame(c, q.enqueue(c, MediaQueue.ENQUEUE_REPLACE, true));
        assertEquals(1, q.size());
        assertSame(c, q.current());
    }

    @Test
    public void playInsertsAfterCurrentAndKeepsRest() {
        MediaQueue q = new MediaQueue();
        q.enqueue(t("a"), MediaQueue.ENQUEUE_ADD, false);
        q.enqueue(t("b"), MediaQueue.ENQUEUE_ADD, true);
        MediaQueue.Track x = t("x");
        assertSame(x, q.enqueue(x, MediaQueue.ENQUEUE_PLAY, true));
        assertSame(x, q.current());
        assertEquals("b", q.advance(true).url);
        assertEquals(3, q.size());
    }

    @Test
    public void addWhileActiveDoesNotInterrupt() {
        MediaQueue q = new MediaQueue();
        MediaQueue.Track a = t("a");
        assertSame(a, q.enqueue(a, MediaQueue.ENQUEUE_ADD, false));
        assertNull(q.enqueue(t("b"), MediaQueue.ENQUEUE_ADD, true));
        assertSame(a, q.current());
        assertEquals("b", q.advance(false).url);
    }

    @Test
    public void nextQueuesRightAfterCurrent() {
        MediaQueue q = new MediaQueue();
        q.enqueue(t("a"), MediaQueue.ENQUEUE_ADD, false);
        q.enqueue(t("c"), MediaQueue.ENQUEUE_ADD, true);
        assertNull(q.enqueue(t("b"), MediaQueue.ENQUEUE_NEXT, true));
        assertEquals("b", q.advance(false).url);
        assertEquals("c", q.advance(false).url);
    }

    @Test
    public void nextWhileIdlePlaysAtOnce() {
        MediaQueue q = new MediaQueue();
        MediaQueue.Track a = t("a");
        assertSame(a, q.enqueue(a, MediaQueue.ENQUEUE_NEXT, false));
        assertSame(a, q.current());
    }

    @Test
    public void addAfterQueueEndedStartsTheNewTrack() {
        MediaQueue q = new MediaQueue();
        q.enqueue(t("a"), MediaQueue.ENQUEUE_ADD, false);
        assertNull(q.advance(false));
        MediaQueue.Track b = t("b");
        assertSame(b, q.enqueue(b, MediaQueue.ENQUEUE_ADD, false));
    }

    @Test
    public void repeatOffEnds() {
        MediaQueue q = new MediaQueue();
        q.enqueue(t("a"), MediaQueue.ENQUEUE_ADD, false);
        q.enqueue(t("b"), MediaQueue.ENQUEUE_ADD, true);
        assertEquals("b", q.advance(false).url);
        assertNull(q.advance(false));
        assertNull(q.advance(true));
    }

    @Test
    public void repeatOneReplaysButSkipMoves() {
        MediaQueue q = new MediaQueue();
        q.setRepeat(MediaQueue.Repeat.ONE);
        q.enqueue(t("a"), MediaQueue.ENQUEUE_ADD, false);
        q.enqueue(t("b"), MediaQueue.ENQUEUE_ADD, true);
        assertEquals("a", q.advance(false).url);
        assertEquals("b", q.advance(true).url);
        assertEquals("b", q.advance(false).url);
        assertNull(q.advance(true));
    }

    @Test
    public void repeatAllWraps() {
        MediaQueue q = new MediaQueue();
        q.setRepeat(MediaQueue.Repeat.ALL);
        q.enqueue(t("a"), MediaQueue.ENQUEUE_ADD, false);
        q.enqueue(t("b"), MediaQueue.ENQUEUE_ADD, true);
        assertEquals("b", q.advance(false).url);
        assertEquals("a", q.advance(false).url);
        assertEquals("b", q.advance(true).url);
        assertEquals("a", q.advance(true).url);
    }

    @Test
    public void emptyQueueHasNothing() {
        MediaQueue q = new MediaQueue();
        assertNull(q.current());
        assertNull(q.advance(false));
        q.enqueue(t("a"), MediaQueue.ENQUEUE_ADD, false);
        q.clear();
        assertNull(q.current());
        assertEquals(0, q.size());
    }

    @Test
    public void parsesWireValues() {
        assertSame(MediaQueue.Repeat.ALL, MediaQueue.Repeat.parse("all"));
        assertSame(MediaQueue.Repeat.ONE, MediaQueue.Repeat.parse("ONE"));
        assertNull(MediaQueue.Repeat.parse("shuffle"));
        assertNull(MediaQueue.Repeat.parse(null));
        assertEquals(true, MediaQueue.isEnqueueMode("next"));
        assertEquals(false, MediaQueue.isEnqueueMode("later"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownMode() {
        new MediaQueue().enqueue(t("a"), "later", false);
    }
}
