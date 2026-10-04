package me.rapierxbox.shellyelevatev2.helper;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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

    // grant write settings and add to the doze whitelist so the manual adb steps are gone
    public static void autoGrantPermissions(Context ctx) {
        String pkg = ctx.getPackageName();
        PrivilegedShell.Result a = PrivilegedShell.runShell("appops set " + pkg + " WRITE_SETTINGS allow");
        PrivilegedShell.Result b = PrivilegedShell.runShell("dumpsys deviceidle whitelist +" + pkg);
        Log.i(TAG, "autoGrant writeSettings=" + a.exitCode + " deviceidle=" + b.exitCode);
    }
}
