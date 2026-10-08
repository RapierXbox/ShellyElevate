package me.rapierxbox.shellyelevatev2.helper;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

// checks for the priv-app install that tools/install-privapp sets up and grants the manual adb perms
public final class PrivAppInstaller {
    private static final String TAG = "PrivAppInstaller";

    private PrivAppInstaller() {}

    // the system flag survives a self update into /data/app while the apk path does not
    public static boolean isPrivApp(Context ctx) {
        ApplicationInfo ai = ctx.getApplicationInfo();
        return ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    // granted only when the /system copy also requests it
    public static boolean canInstallPackages(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES) == PackageManager.PERMISSION_GRANTED;
    }

    // grant write settings and usage stats and add to the doze whitelist so the manual adb steps are gone
    // usage stats lets the app display module see which app is in front
    public static void autoGrantPermissions(Context ctx) {
        String pkg = ctx.getPackageName();
        PrivilegedShell.Result a = PrivilegedShell.allowAppOp(pkg, "WRITE_SETTINGS");
        PrivilegedShell.Result b = PrivilegedShell.runShell("dumpsys deviceidle whitelist +" + pkg);
        PrivilegedShell.Result c = PrivilegedShell.allowAppOp(pkg, "GET_USAGE_STATS");
        // overlay rights let the switcher open over other apps on android 10 and up and back the edge strip
        PrivilegedShell.Result d = PrivilegedShell.allowAppOp(pkg, "SYSTEM_ALERT_WINDOW");
        Log.i(TAG, "autoGrant writeSettings=" + a.exitCode + " deviceidle=" + b.exitCode
                + " usageStats=" + c.exitCode + " overlay=" + d.exitCode);
        logMissingRuntimePermissions(ctx);
    }

    // pm grant needs GRANT_RUNTIME_PERMISSIONS which this app never holds so runtime perms come over adb
    // they survive self updates since a same package install keeps them
    private static void logMissingRuntimePermissions(Context ctx) {
        StringBuilder missing = new StringBuilder();
        for (String perm : runtimePermissions()) {
            if (ctx.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                missing.append(' ').append(perm.substring(perm.lastIndexOf('.') + 1));
            }
        }
        if (missing.length() > 0) {
            Log.w(TAG, "runtime permissions missing:" + missing
                    + ". install-privapp or the home assistant integration grant them over adb");
        }
    }

    // same list as install-privapp and the integration post install steps
    private static String[] runtimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION};
    }
}
