package me.rapierxbox.shellyelevatev2.voice;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StreamingModelTest {
    private static final float EPS = 1e-6f;

    @Test
    public void quantizeMelSignedFullRange() {
        assertEquals(-128, StreamingModel.quantizeMel(0f, -128, false));
        assertEquals(0, StreamingModel.quantizeMel(NativeMelExtractor.OUT_MAX / 2f, -128, false));
        assertEquals(127, StreamingModel.quantizeMel(NativeMelExtractor.OUT_MAX, -128, false));
    }

    @Test
    public void quantizeMelSignedZeroCentered() {
        assertEquals(0, StreamingModel.quantizeMel(0f, 0, false));
        assertEquals(127, StreamingModel.quantizeMel(NativeMelExtractor.OUT_MAX, 0, false));
    }

    @Test
    public void quantizeMelUnsigned() {
        assertEquals(0, StreamingModel.quantizeMel(0f, 0, true));
        assertEquals((byte) 255, StreamingModel.quantizeMel(NativeMelExtractor.OUT_MAX, 0, true));
    }

    @Test
    public void quantizeMelClamps() {
        assertEquals(127, StreamingModel.quantizeMel(100f, -128, false));
        assertEquals(-128, StreamingModel.quantizeMel(-100f, -128, false));
        assertEquals((byte) 255, StreamingModel.quantizeMel(100f, 0, true));
        assertEquals(0, StreamingModel.quantizeMel(-100f, 0, true));
    }

    @Test
    public void scoreWindowAveragesAndResets() {
        StreamingModel.ScoreWindow window = new StreamingModel.ScoreWindow(2);
        assertEquals(0.5f, window.add(1f), EPS);
        assertEquals(1f, window.add(1f), EPS);
        assertEquals(0.5f, window.add(0f), EPS);
        window.reset();
        assertEquals(0.2f, window.add(0.4f), EPS);
    }

    @Test
    public void scoreWindowNeverEmpty() {
        StreamingModel.ScoreWindow window = new StreamingModel.ScoreWindow(0);
        assertEquals(0.3f, window.add(0.3f), EPS);
    }
}
