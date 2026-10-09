package me.rapierxbox.shellyelevatev2.voice;

// hands the int8 rows of the jni microfrontend straight to the models
public final class NativeFeatureFrontend implements FeatureFrontend {
    // 8 kib is about 256 ms of 16 khz mono pcm16 and well above the 100 ms chunks fed in
    private static final int MAX_CHUNK_BYTES = 8192;

    private final NativeMelExtractor extractor;
    private final byte[] row = new byte[NativeMelExtractor.N_MELS];

    public NativeFeatureFrontend() {
        this.extractor = new NativeMelExtractor(MAX_CHUNK_BYTES);
    }

    @Override public void feed(byte[] pcm, int length, FrameCallback cb) {
        extractor.feedInt8(pcm, length, (buf, off, len) -> {
            System.arraycopy(buf, off, row, 0, len);
            cb.onFrame(row);
        });
    }

    @Override public void reset() { extractor.reset(); }

    @Override public void close() { extractor.close(); }
}
