package me.rapierxbox.shellyelevatev2.voice;

// native backed feature frontend uses the tflm microfontend c lib to extract int8 features, then dequantizes each row to float in the rage expectet by downstream model quant code
public final class NativeFeatureFrontend implements FeatureFrontend {
    // int8 -> float: (int8 + 128) / 255 x 26, precomputed coefficient
    private static final float DEQUANT = NativeMelExtractor.OUT_MAX / 255f;
    private static final int MAX_CHUNK_BYTES = 8192; // 256ms at 16kHz int16... some headroom

    private final NativeMelExtractor native_;
    private final float[] row = new float[NativeMelExtractor.N_MELS];

    public NativeFeatureFrontend() {
        this.native_ = new NativeMelExtractor(MAX_CHUNK_BYTES);
    }

    @Override public void feed(byte[] pcm, int length, FrameCallback cb) {
        native_.feedInt8(pcm, length, (buf, off, len) -> {
            for (int i = 0; i < len; i++) {
                int v = buf[off + i] + 128; // int8 back to [0, 255]
                row[i] = v * DEQUANT;
            }
            cb.onFrame(row);
        });
    }

    @Override public void reset() { native_.reset(); }

    @Override public void close() { native_.close(); }
}
