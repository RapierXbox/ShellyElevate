package me.rapierxbox.shellyelevatev2.helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import me.rapierxbox.shellyelevatev2.ShellyElevateApplication;

// every reboot trigger goes through here
public final class RebootHelper {
    private static final String TAG = "RebootHelper";
    // a retained mqtt command or a retrying http client would otherwise boot loop the device
    private static final long GRACE_SECONDS = 20;

    private RebootHelper() {}

    public static void reboot() {
        new Thread(() -> {
            PrivilegedShell.Result r = PrivilegedShell.run("reboot");
            if (!r.ok()) Log.e(TAG, "reboot failed: " + r.stderr);
        }, "reboot").start();
    }

    // for remote triggers. refuses right after start and toasts how long to wait
    public static boolean rebootUnlessJustStarted(Context context) {
        long uptimeSec = (System.currentTimeMillis() - ShellyElevateApplication.getApplicationStartTime()) / 1000;
        if (uptimeSec > GRACE_SECONDS) {
            reboot();
            return true;
        }
        String msg = "Please wait " + (GRACE_SECONDS - uptimeSec) + " seconds before rebooting";
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
        return false;
    }
}
