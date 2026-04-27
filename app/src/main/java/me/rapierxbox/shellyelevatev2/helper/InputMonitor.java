package me.rapierxbox.shellyelevatev2.helper;

import android.util.Log;

import java.util.List;

public class InputMonitor {

    private static final String TAG = "InputMonitor";
    private static boolean sLibraryLoaded = false;

    public interface KeyCallback {
        // action: 0=UP, 1=DOWN, 2=REPEAT
        void onHardwareKey(int keyCode, int action, int repeatCount);
    }

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

    private native void nativeStart(KeyCallback callback, String[] paths);

    public native void stop();

    public void start(KeyCallback callback, List<String> paths) {
        if (!sLibraryLoaded) return;
        nativeStart(callback, paths.toArray(new String[0]));
    }
}
