package me.rapierxbox.shellyelevatev2.helper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.StatFs;
import android.util.Log;

import java.io.File;

// promotes the app into /system/priv-app and grants the perms that are manual adb steps today
public final class PrivAppInstaller {
    private static final String TAG = "PrivAppInstaller";

    private static final String PRIV_DIR = "/system/priv-app/ShellyElevateV2";
    private static final String TARGET_APK = PRIV_DIR + "/ShellyElevateV2.apk";
    private static final String TEMP_APK = PRIV_DIR + "/.ShellyElevateV2.apk.new";

    // headroom on top of the apk size
    private static final long SPACE_MARGIN_BYTES = 4L * 1024 * 1024;

    public enum Result { PROMOTED, FAILED, FAILED_NO_SPACE }

    private PrivAppInstaller() {}

    // true once the running apk lives anywhere under /system
    public static boolean isPrivApp(Context ctx) {
        String dir = sourceDir(ctx);
        return dir != null && dir.startsWith("/system/");
    }

    // stricter check used only for status text
    public static boolean isInPrivApp(Context ctx) {
        String dir = sourceDir(ctx);
        return dir != null && dir.startsWith("/system/priv-app");
    }

    public static Result promoteToPrivApp(Context ctx) {
        String src = sourceDir(ctx);
        if (src == null) return Result.FAILED;
        File srcFile = new File(src);
        if (!srcFile.exists() || srcFile.length() <= 0) return Result.FAILED;
        if (!hasSystemSpaceFor(srcFile.length())) return Result.FAILED_NO_SPACE;
        return installApk(srcFile) ? Result.PROMOTED : Result.FAILED;
    }

    // copies an apk over the priv-app target with a temp swap then remounts ro
    public static boolean installApk(File apk) {
        if (apk == null || !apk.exists() || apk.length() <= 0) return false;
        if (!remountRw()) return false;

        // finally so /system is dropped back to read-only on every path
        // including an early return or a timeout that kills a shell mid-copy
        try {
            String src = apk.getAbsolutePath();
            String copy = "mkdir -p " + PRIV_DIR
                    + " && cp -f '" + src + "' " + TEMP_APK
                    + " && chmod 644 " + TEMP_APK;
            if (!PrivilegedShell.runShell(copy).ok() || !sizeMatches(TEMP_APK, apk.length())) {
                PrivilegedShell.runShell("rm -f " + TEMP_APK);
                return false;
            }

            boolean ok = PrivilegedShell.runShell("mv -f " + TEMP_APK + " " + TARGET_APK
                    + " && chmod 644 " + TARGET_APK).ok();
            if (!ok) {
                // dont leave the temp apk behind on a failed swap
                PrivilegedShell.runShell("rm -f " + TEMP_APK);
                return false;
            }
            // best effort selinux label so the new apk loads
            PrivilegedShell.runShell("chcon u:object_r:system_file:s0 " + TARGET_APK);
            // flush to disk so the copy survives the reboot the caller triggers
            PrivilegedShell.runShell("sync");
            return true;
        } finally {
            remountRo();
        }
    }

    public static boolean hasSystemSpaceFor(long need) {
        try {
            return new StatFs("/system").getAvailableBytes() >= need + SPACE_MARGIN_BYTES;
        } catch (Exception e) {
            // if we cant tell let the copy attempt decide
            return true;
        }
    }

    // grant write settings and add to the doze whitelist so the manual adb steps are gone
    public static void autoGrantPermissions(Context ctx) {
        String pkg = ctx.getPackageName();
        PrivilegedShell.Result a = PrivilegedShell.runShell("appops set " + pkg + " WRITE_SETTINGS allow");
        PrivilegedShell.Result b = PrivilegedShell.runShell("dumpsys deviceidle whitelist +" + pkg);
        Log.i(TAG, "autoGrant writeSettings=" + a.exitCode + " deviceidle=" + b.exitCode);
    }

    private static String sourceDir(Context ctx) {
        ApplicationInfo ai = ctx.getApplicationInfo();
        return ai == null ? null : ai.sourceDir;
    }

    private static boolean sizeMatches(String path, long expected) {
        PrivilegedShell.Result r = PrivilegedShell.runShell("wc -c < " + path);
        try {
            return Long.parseLong(r.stdout.trim()) == expected;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // android 7 may mount system separately or as part of root
    private static boolean remountRw() {
        return PrivilegedShell.runShell("mount -o rw,remount /system 2>/dev/null || mount -o rw,remount /").ok();
    }

    private static void remountRo() {
        PrivilegedShell.runShell("mount -o ro,remount /system 2>/dev/null || mount -o ro,remount /");
    }
}
