package me.rapierxbox.shellyelevatev2.voice;

// mel feature frontend used by the wake word and vad models
// emits one row of 40 mel bins per 10 ms hop in the 0 to out_max float range
// the row passed to the callback is reused so consumers must copy it
public interface FeatureFrontend {
    interface FrameCallback { void onFrame(float[] mel); }

    void feed(byte[] pcm, int length, FrameCallback cb);
    void reset();
    void close();
}
