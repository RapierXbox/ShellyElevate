package me.rapierxbox.shellyelevatev2.voice;

import android.util.Log;

// java side of the jni tflm microfrontend in mel_jni.cpp
// not thread safe so one instance per audio capture thread
public final class NativeMelExtractor {
    private static final String TAG = "NativeMelExtractor";

    public static final int N_MELS = 40;
    public static final int SAMPLE_RATE = 16000;
    // 10 ms step at 16 khz
    public static final int HOP_SAMPLES = 160;
    // upper bound of the float feature range emitted by the frontend
    public static final float OUT_MAX = 26.0f;

    private static final boolean LIBRARY_AVAILABLE = loadLibrary();

    private static boolean loadLibrary() {
        try {
            System.loadLibrary("melfrontend");
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "melfrontend native library unavailable: " + e.getMessage());
            return false;
        }
    }

    public static boolean isAvailable() { return LIBRARY_AVAILABLE; }

    private long handle;
    private final byte[] outScratch;

    public NativeMelExtractor(int maxChunkBytes) {
        if (!LIBRARY_AVAILABLE) throw new IllegalStateException("native mel frontend not loaded");
        // one row per hop plus slack for a partial window still in the ringbuffer
        int maxFeatureRows = (maxChunkBytes / 2 / HOP_SAMPLES) + 2;
        outScratch = new byte[maxFeatureRows * N_MELS];
        handle = nativeCreate();
        if (handle == 0L) throw new IllegalStateException("nativeCreate failed");
    }

    // the row buffer handed to the callback is reused on the next call
    public int feedInt8(byte[] pcm, int length, Int8RowCallback cb) {
        if (handle == 0L) return 0;
        int rows = nativeFeedInt8(handle, pcm, length, outScratch, outScratch.length);
        if (rows < 0) {
            Log.w(TAG, "nativeFeedInt8 returned " + rows);
            return 0;
        }
        if (cb != null) {
            for (int r = 0; r < rows; r++) cb.onRow(outScratch, r * N_MELS, N_MELS);
        }
        return rows;
    }

    public void reset() {
        if (handle != 0L) nativeReset(handle);
    }

    public void close() {
        if (handle != 0L) {
            nativeDestroy(handle);
            handle = 0L;
        }
    }

    public interface Int8RowCallback {
        void onRow(byte[] buf, int offset, int length);
    }

    private static native long nativeCreate();
    private static native void nativeDestroy(long handle);
    private static native void nativeReset(long handle);
    private static native int nativeFeedInt8(long handle, byte[] pcm, int pcmByteLen,
                                             byte[] outInt8Buffer, int outCapacityBytes);
    // raw uint16 features for diagnostics and not used by the detector
    private static native int nativeFeedUint16(long handle, byte[] pcm, int pcmByteLen,
                                               short[] outU16Buffer, int outCapacityShorts);
}
