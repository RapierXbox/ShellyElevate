package me.rapierxbox.shellyelevatev2.voice;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class PcmRingTest {
    private static byte[] seq(int from, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (from + i);
        return b;
    }

    @Test
    public void partialFillReturnsWhatWasWritten() {
        PcmRing ring = new PcmRing(8);
        ring.write(seq(0, 3), 3);
        assertArrayEquals(seq(0, 3), ring.snapshot());
    }

    @Test
    public void wrapKeepsTheNewestOldestFirst() {
        PcmRing ring = new PcmRing(8);
        ring.write(seq(0, 5), 5);
        ring.write(seq(5, 5), 5);
        assertArrayEquals(seq(2, 8), ring.snapshot());
    }

    @Test
    public void chunkLargerThanTheRingKeepsItsTail() {
        PcmRing ring = new PcmRing(4);
        ring.write(seq(0, 10), 10);
        assertArrayEquals(seq(6, 4), ring.snapshot());
    }

    @Test
    public void onlyLenBytesCount() {
        PcmRing ring = new PcmRing(4);
        ring.write(seq(0, 10), 2);
        assertArrayEquals(seq(0, 2), ring.snapshot());
    }

    @Test
    public void clearEmpties() {
        PcmRing ring = new PcmRing(4);
        ring.write(seq(0, 6), 6);
        ring.clear();
        assertArrayEquals(new byte[0], ring.snapshot());
    }
}
