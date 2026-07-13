package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.rapierxbox.shellyelevatev2.Constants;

// toggles adb over wifi by setting the adb tcp port and restarting adbd
public final class AdbHelper {
    private static final String TAG = "AdbHelper";
    private static final String ADB_PORT_PROP = "service.adb.tcp.port";
    public static final int ADB_WIFI_PORT = 5555;
    private static final String PORT_ON = String.valueOf(ADB_WIFI_PORT);
    private static final String PORT_OFF = "-1";

    // shell work must stay off the main thread
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private AdbHelper() {}

    // called from the settings toggle
    public static void setAdbWifiEnabled(boolean enabled) {
        EXEC.execute(() -> apply(enabled));
    }

    // the tcp port property resets on reboot so re-apply the saved state on boot
    public static void applyFromPrefs() {
        if (mSharedPreferences.getBoolean(Constants.SP_ADB_WIFI_ENABLED, false)) {
            EXEC.execute(() -> apply(true));
        }
    }

    // idempotent so re-applying the same state does not needlessly bounce adbd
    private static void apply(boolean enabled) {
        String desired = enabled ? PORT_ON : PORT_OFF;
        String current = PrivilegedShell.runShell("getprop " + ADB_PORT_PROP).stdout.trim();
        if (desired.equals(current)) return;

        // semicolons so start always runs even if stop returns nonzero
        // otherwise a failed stop would leave adbd down and drop adb entirely
        PrivilegedShell.Result r = PrivilegedShell.runShell(
                "setprop " + ADB_PORT_PROP + " " + desired + "; stop adbd; start adbd");
        if (r.ok()) {
            Log.i(TAG, "adb over wifi " + (enabled ? "enabled on port " + PORT_ON : "disabled"));
        } else {
            Log.e(TAG, "failed to toggle adb over wifi exit=" + r.exitCode + " err=" + r.stderr);
        }
    }
}
