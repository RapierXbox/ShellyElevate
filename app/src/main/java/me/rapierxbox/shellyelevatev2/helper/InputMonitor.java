package me.rapierxbox.shellyelevatev2.helper;

import android.util.Log;

import java.util.List;

// jni wrapper that reads /dev/input directly so key edges keep arriving even
// when nothing of ours holds focus (see ButtonHandler and SwInputHandler)
public class InputMonitor {

    private static final String TAG = "InputMonitor";
    private static boolean sLibraryLoaded = false;

    public interface KeyCallback {
        // action: 0=up 1=down 2=repeat (matches linux input event values)
        void onHardwareKey(int keyCode, int action, int repeatCount);
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

    public static boolean isAvailable() {
        return sLibraryLoaded;
    }

    // false when no input node could be opened or the reader thread failed to start
    private native boolean nativeStart(KeyCallback callback, String[] paths);

    public native void stop();

    public boolean start(KeyCallback callback, List<String> paths) {
        if (!sLibraryLoaded) return false;
        return nativeStart(callback, paths.toArray(new String[0]));
    }
}
