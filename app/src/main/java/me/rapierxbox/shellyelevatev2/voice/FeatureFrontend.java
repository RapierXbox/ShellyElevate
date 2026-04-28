package me.rapierxbox.shellyelevatev2.voice;

// Mel-feature frontend contract used by the wake-word and VAD detectors.
// Implementations emit one row of 40 mel bins per 10 ms hop, in the
// [0, NativeMelExtractor.OUT_MAX] float range.
public interface FeatureFrontend {
    interface FrameCallback { void onFrame(float[] mel); }

    void feed(byte[] pcm, int length, FrameCallback cb);
    void reset();
    void close();
}
