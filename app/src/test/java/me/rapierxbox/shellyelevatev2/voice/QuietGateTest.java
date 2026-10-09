package me.rapierxbox.shellyelevatev2.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QuietGateTest {
    private static final float ROOM = 0.002f;

    private static QuietGate settled() {
        QuietGate gate = new QuietGate();
        for (int i = 0; i <= QuietGate.HANGOVER_CHUNKS; i++) gate.update(ROOM);
        return gate;
    }

    @Test
    public void startsActive() {
        assertTrue(new QuietGate().update(ROOM));
    }

    @Test
    public void quietRoomPauses() {
        QuietGate gate = settled();
        for (int i = 0; i < 50; i++) assertFalse(gate.update(ROOM));
    }

    @Test
    public void soundOpensRightAway() {
        QuietGate gate = settled();
        assertTrue(gate.update(ROOM * 3));
    }

    @Test
    public void hangoverKeepsTheTail() {
        QuietGate gate = settled();
        gate.update(ROOM * 10);
        for (int i = 0; i < QuietGate.HANGOVER_CHUNKS; i++) assertTrue(gate.update(ROOM));
        assertFalse(gate.update(ROOM));
    }

    @Test
    public void steadyNoiseClosesAgain() {
        QuietGate gate = settled();
        boolean closed = false;
        for (int i = 0; i < 600 && !closed; i++) closed = !gate.update(ROOM * 4);
        assertTrue(closed);
        assertTrue(gate.update(ROOM * 12));
    }

    @Test
    public void silenceBelowMinimumNeverOpens() {
        QuietGate gate = new QuietGate();
        for (int i = 0; i <= QuietGate.HANGOVER_CHUNKS; i++) gate.update(0f);
        assertFalse(gate.update(QuietGate.MIN_RMS * 0.9f));
    }

    @Test
    public void ringHoldsThePreRoll() {
        assertEquals(10, StreamingModel.ringWindows(3));
        assertEquals(2, StreamingModel.ringWindows(30));
        assertEquals(2, StreamingModel.ringWindows(40));
        assertTrue(StreamingModel.ringWindows(1) * 1 >= StreamingModel.PRE_ROLL_FRAMES);
    }

    @Test
    public void vadRingCoversItsLookBack() {
        // the esphome vad sees about 24 windows back and its mean spans 5 more
        assertEquals(40, StreamingModel.ringWindows(3, WakeWordDetector.VAD_PRE_ROLL_FRAMES));
        assertTrue(StreamingModel.ringWindows(3, WakeWordDetector.VAD_PRE_ROLL_FRAMES) >= 24 + 5);
    }
}
