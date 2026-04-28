package me.rapierxbox.shellyelevatev2.voice;

import android.util.Log;

// Java-side bridge to the JNI TFLM microfrontend. Not thread-safe; one instance
// per audio capture thread.
public final class NativeMelExtractor {
    private static final String TAG = "NativeMelExtractor";

    public static final int N_MELS = 40;
    public static final int SAMPLE_RATE = 16000;
    public static final int HOP_SAMPLES = 160; // 10 ms step at 16 kHz
    /** Upper bound of the float feature range emitted by the frontend. */
    public static final float OUT_MAX = 26.0f;

    private static boolean sLibraryLoaded;
    private static boolean sLibraryAvailable;

    static {
        try {
            System.loadLibrary("melfrontend");
            sLibraryLoaded = true;
            sLibraryAvailable = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "melfrontend native library unavailable: " + e.getMessage());
            sLibraryLoaded = false;
            sLibraryAvailable = false;
        }
    }

    public static boolean isAvailable() { return sLibraryAvailable; }

    private long handle;
    private final byte[] outScratch;
    private final int maxFeatureRows;

    public NativeMelExtractor(int maxChunkBytes) {
        if (!sLibraryAvailable) throw new IllegalStateException("native mel frontend not loaded");
        handle = nativeCreate();
        if (handle == 0L) throw new IllegalStateException("nativeCreate failed");
        // Upper bound on feature rows per feed(): ceil(samples / HOP_SAMPLES) + 1
        // for partial windows still in the ringbuffer.
        int maxSamples = maxChunkBytes / 2;
        maxFeatureRows = (maxSamples / HOP_SAMPLES) + 2;
        outScratch = new byte[maxFeatureRows * N_MELS];
    }

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
    private static native int  nativeFeedInt8(long handle, byte[] pcm, int pcmByteLen,
                                              byte[] outInt8Buffer, int outCapacityBytes);
    private static native int  nativeFeedUint16(long handle, byte[] pcm, int pcmByteLen,
                                                short[] outU16Buffer, int outCapacityShorts);
}
