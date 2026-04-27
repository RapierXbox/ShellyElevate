package me.rapierxbox.shellyelevatev2.voice;

// mel feature frontend contract. implemented by nativefeaturefrontend which wraps the jni tflm microfrontend. emits 40 mel bin rows in the [0, NativeMelExtractor.OUTMAX] float range
public interface FeatureFrontend {
    interface FrameCallback { void onFrame(float[] mel); }

    void feed(byte[] pcm, int length, FrameCallback cb);
    void reset();
    void close();
}
