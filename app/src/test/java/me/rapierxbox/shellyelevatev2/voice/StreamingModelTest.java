package me.rapierxbox.shellyelevatev2.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.tensorflow.lite.DataType;

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
    public void melSpansTheFeatureRange() {
        assertEquals(0f, FeatureFrontend.mel((byte) -128), EPS);
        assertEquals(NativeMelExtractor.OUT_MAX, FeatureFrontend.mel((byte) 127), EPS);
    }

    @Test
    public void inputLutMatchesQuantizeMel() {
        int[][] params = {{-128, 0}, {0, 0}, {-3, 0}, {0, 1}};
        for (int[] p : params) {
            byte[] lut = StreamingModel.inputLut(p[0], p[1] == 1);
            for (int b = -128; b < 128; b++) {
                assertEquals(StreamingModel.quantizeMel(FeatureFrontend.mel((byte) b), p[0], p[1] == 1), lut[b + 128]);
            }
        }
    }

    @Test
    public void inputLutIsIdentityForMicroWakeWordModels() {
        // the mww int8 input with zero point -128 gets the frontend bytes back unchanged
        byte[] lut = StreamingModel.inputLut(-128, false);
        for (int b = -128; b < 128; b++) assertEquals((byte) b, lut[b + 128]);
        assertTrue(StreamingModel.isIdentity(lut));
        assertFalse(StreamingModel.isIdentity(StreamingModel.inputLut(0, false)));
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

    @Test
    public void checkTensorsAcceptsMicroWakeWordLayouts() {
        // hey jarvis okay nabu and the vad all look like this
        assertNull(StreamingModel.checkTensors(new int[]{1, 3, 40}, DataType.INT8, new int[]{1, 1}, DataType.UINT8));
        assertNull(StreamingModel.checkTensors(new int[]{1, 1, 40, 1}, DataType.FLOAT32, new int[]{1, 2}, DataType.FLOAT32));
    }

    @Test
    public void checkTensorsRejectsWhatTheBuffersCannotFeed() {
        assertNotNull(StreamingModel.checkTensors(new int[]{1, 40}, DataType.INT8, new int[]{1, 1}, DataType.UINT8));
        assertNotNull(StreamingModel.checkTensors(new int[]{2, 3, 40}, DataType.INT8, new int[]{1, 1}, DataType.UINT8));
        assertNotNull(StreamingModel.checkTensors(new int[]{1, 0, 40}, DataType.INT8, new int[]{1, 1}, DataType.UINT8));
        assertNotNull(StreamingModel.checkTensors(new int[]{1, 3, 32}, DataType.INT8, new int[]{1, 1}, DataType.UINT8));
        assertNotNull(StreamingModel.checkTensors(new int[]{1, 3, 40, 2}, DataType.FLOAT32, new int[]{1, 1}, DataType.FLOAT32));
        assertNotNull(StreamingModel.checkTensors(new int[]{1, 3, 40}, DataType.INT32, new int[]{1, 1}, DataType.UINT8));
        assertNotNull(StreamingModel.checkTensors(new int[]{1, 3, 40}, DataType.INT8, new int[]{2, 1}, DataType.UINT8));
        assertNotNull(StreamingModel.checkTensors(new int[]{1, 3, 40}, DataType.INT8, new int[]{1, 0}, DataType.UINT8));
        assertNotNull(StreamingModel.checkTensors(new int[]{1, 3, 40}, DataType.INT8, new int[]{}, DataType.UINT8));
        assertNotNull(StreamingModel.checkTensors(new int[]{1, 3, 40}, DataType.INT8, new int[]{1, 1}, DataType.INT64));
    }
}
