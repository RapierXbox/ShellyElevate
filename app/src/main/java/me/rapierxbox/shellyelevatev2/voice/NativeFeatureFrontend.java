package me.rapierxbox.shellyelevatev2.voice;

// dequantizes the int8 rows of the jni microfrontend back into the float
// range the model input quantizer expects
public final class NativeFeatureFrontend implements FeatureFrontend {
    // int8 to float as ((int8 + 128) / 255) * out_max
    private static final float DEQUANT = NativeMelExtractor.OUT_MAX / 255f;
    // 8 kib is about 256 ms of 16 khz mono pcm16 and well above the 100 ms chunks fed in
    private static final int MAX_CHUNK_BYTES = 8192;

    private final NativeMelExtractor extractor;
    private final float[] row = new float[NativeMelExtractor.N_MELS];

    public NativeFeatureFrontend() {
        this.extractor = new NativeMelExtractor(MAX_CHUNK_BYTES);
    }

    @Override public void feed(byte[] pcm, int length, FrameCallback cb) {
        extractor.feedInt8(pcm, length, (buf, off, len) -> {
            for (int i = 0; i < len; i++) row[i] = (buf[off + i] + 128) * DEQUANT;
            cb.onFrame(row);
        });
    }

    @Override public void reset() { extractor.reset(); }

    @Override public void close() { extractor.close(); }
}
