package me.rapierxbox.shellyelevatev2.voice;

// Wraps the TFLM microfrontend JNI (NativeMelExtractor) and dequantises each
// emitted int8 row into a float buffer in the range expected by downstream
// quantisation code (WakeWordDetector.quantizeMel).
public final class NativeFeatureFrontend implements FeatureFrontend {
    // int8 -> float: ((int8 + 128) / 255) * OUT_MAX, precomputed.
    private static final float DEQUANT = NativeMelExtractor.OUT_MAX / 255f;
    // 8 KiB ~= 256 ms at 16 kHz mono int16, well above the 100 ms chunks fed in.
    private static final int MAX_CHUNK_BYTES = 8192;

    private final NativeMelExtractor native_;
    private final float[] row = new float[NativeMelExtractor.N_MELS];

    public NativeFeatureFrontend() {
        this.native_ = new NativeMelExtractor(MAX_CHUNK_BYTES);
    }

    @Override public void feed(byte[] pcm, int length, FrameCallback cb) {
        native_.feedInt8(pcm, length, (buf, off, len) -> {
            for (int i = 0; i < len; i++) {
                int v = buf[off + i] + 128;
                row[i] = v * DEQUANT;
            }
            cb.onFrame(row);
        });
    }

    @Override public void reset() { native_.reset(); }

    @Override public void close() { native_.close(); }
}
