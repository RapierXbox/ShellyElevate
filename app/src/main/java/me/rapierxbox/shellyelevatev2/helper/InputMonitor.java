package me.rapierxbox.shellyelevatev2.helper;

import android.util.Log;

import java.util.Collections;
import java.util.List;

// jni wrapper that reads /dev/input directly so key edges and touches keep arriving even
// when nothing of ours holds focus (see ButtonHandler SwInputHandler and TouchGestureMonitor)
public class InputMonitor {

    private static final String TAG = "InputMonitor";
    private static boolean sLibraryLoaded = false;

    public interface KeyCallback {
        // action: 0=up 1=down 2=repeat (matches linux input event values)
        void onHardwareKey(int keyCode, int action, int repeatCount);
    }

    public interface TouchCallback {
        // type code value triples straight from the kernel. the array is reused so copy what you keep
        void onTouchEvents(int[] events, int count);
    }

    // a multitouch node and the raw range of its position axes
    public static final class Touchscreen {
        public final String path;
        public final int minX, maxX, minY, maxY;

        public Touchscreen(String path, int minX, int maxX, int minY, int maxY) {
            this.path = path;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }

        @Override
        public String toString() {
            return path + " x " + minX + ".." + maxX + " y " + minY + ".." + maxY;
        }
    }

    // libshellyinput.so is missing on devices without the native monitor built
    // for them; callers fall back to a getevent based reader when unavailable
    static {
        try {
            System.loadLibrary("shellyinput");
            sLibraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "libshellyinput.so not available: " + e.getMessage());
        }
    }

    // opaque pointer to the native reader or 0 when stopped
    private long handle;

    public static boolean isAvailable() {
        return sLibraryLoaded;
    }

    // 0 when no input node could be opened or the reader thread failed to start
    private native long nativeStart(KeyCallback callback, String[] paths);

    private native long nativeStartTouch(TouchCallback callback, String[] paths);

    private native void nativeStop(long handle);

    private static native int[] nativeProbeTouch(String path);

    public synchronized boolean start(KeyCallback callback, List<String> paths) {
        if (!sLibraryLoaded) return false;
        stop();
        handle = nativeStart(callback, paths.toArray(new String[0]));
        return handle != 0;
    }

    public synchronized boolean startTouch(TouchCallback callback, String path) {
        if (!sLibraryLoaded) return false;
        stop();
        handle = nativeStartTouch(callback, Collections.singletonList(path).toArray(new String[0]));
        return handle != 0;
    }

    public synchronized void stop() {
        if (handle == 0) return;
        long h = handle;
        handle = 0;
        nativeStop(h);
    }

    // result of opening one input node
    public static final class Probe {
        public static final int TOUCHSCREEN = 0;
        public static final int OTHER = 1;
        // negative values are the errno of a failed open
        public final int status;
        public final Touchscreen touchscreen;

        Probe(int status, Touchscreen touchscreen) {
            this.status = status;
            this.touchscreen = touchscreen;
        }

        public boolean permissionDenied() {
            // EACCES and EPERM
            return status == -13 || status == -1;
        }
    }

    // null when the native library is missing
    public static Probe probe(String path) {
        if (!sLibraryLoaded) return null;
        int[] r = nativeProbeTouch(path);
        if (r == null || r.length != 5) return null;
        Touchscreen ts = r[0] == Probe.TOUCHSCREEN ? new Touchscreen(path, r[1], r[2], r[3], r[4]) : null;
        return new Probe(r[0], ts);
    }
}
