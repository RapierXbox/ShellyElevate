package me.rapierxbox.shellyelevatev2.voice;

// mel feature frontend used by the wake word and vad models
// emits one row of 40 int8 mel bins per 10 ms hop as the tflm microfrontend
// quantizes them and mel maps a bin back to the 0 to out_max float range
// the row passed to the callback is reused so consumers must copy it
public interface FeatureFrontend {
    interface FrameCallback { void onFrame(byte[] row); }

    // int8 to float as ((int8 + 128) / 255) * out_max
    float DEQUANT = NativeMelExtractor.OUT_MAX / 255f;

    static float mel(byte bin) {
        return (bin + 128) * DEQUANT;
    }

    void feed(byte[] pcm, int length, FrameCallback cb);
    void reset();
    void close();
}
