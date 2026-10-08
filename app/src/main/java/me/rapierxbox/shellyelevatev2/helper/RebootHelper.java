package me.rapierxbox.shellyelevatev2.helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import me.rapierxbox.shellyelevatev2.ShellyElevateApplication;

// every reboot trigger goes through here
public final class RebootHelper {
    private static final String TAG = "RebootHelper";
    // a retained mqtt command or a retrying http client would otherwise boot loop the device
    private static final long GRACE_SECONDS = 20;
    // a reboot that cannot run fails within this time
    private static final long CONFIRM_WAIT_MS = 2000;

    private RebootHelper() {}

    public static void reboot() {
        new Thread(() -> {
            PrivilegedShell.Result r = PrivilegedShell.run("reboot");
            if (!r.ok()) Log.e(TAG, "reboot failed: " + r.stderr);
        }, "reboot").start();
    }

    // false when the reboot command failed. still running counts as started since the system goes down
    public static boolean rebootAndConfirm() {
        AtomicReference<PrivilegedShell.Result> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            PrivilegedShell.Result r = PrivilegedShell.run("reboot");
            if (!r.ok()) Log.e(TAG, "reboot failed: " + r.stderr);
            result.set(r);
            done.countDown();
        }, "reboot").start();
        try {
            done.await(CONFIRM_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        PrivilegedShell.Result r = result.get();
        return r == null || r.ok();
    }

    // for remote triggers. refuses right after start and toasts how long to wait
    public static boolean rebootUnlessJustStarted(Context context) {
        if (justStarted(context)) return false;
        reboot();
        return true;
    }

    // toasts how long to wait when it is too early
    public static boolean justStarted(Context context) {
        long uptimeSec = (System.currentTimeMillis() - ShellyElevateApplication.getApplicationStartTime()) / 1000;
        if (uptimeSec > GRACE_SECONDS) return false;
        String msg = "Please wait " + (GRACE_SECONDS - uptimeSec) + " seconds before rebooting";
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
        return true;
    }
}
