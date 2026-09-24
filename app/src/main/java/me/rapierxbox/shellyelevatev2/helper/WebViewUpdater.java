package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_UPDATE_PENDING_FROM;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RecoverySystem;
import android.os.StatFs;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.R;

// mirrors the oem cloud.shelly.stargate webview ota flow: drop the device
// WebViewUpdate.zip at /cache/update.zip and reboot into recovery which
// applies it. writing /cache needs the cache gid which the priv-app gets
// through ACCESS_CACHE_FILESYSTEM
public final class WebViewUpdater {
    private static final String TAG = "WebViewUpdater";

    // recovery is pointed at this exact path. dont rename it
    public static final File STAGED_ZIP = new File("/cache/update.zip");
    // download here first so a power cut cannot stage a truncated ota
    private static final File TEMP_ZIP = new File("/cache/update.zip.part");
    private static final File CACHE_DIR = new File("/cache");
    private static final File RECOVERY_DIR = new File("/cache/recovery");
    private static final File RECOVERY_COMMAND = new File(RECOVERY_DIR, "command");
    private static final File LAST_INSTALL = new File(RECOVERY_DIR, "last_install");

    // headroom on top of the zip size
    private static final long SPACE_MARGIN_BYTES = 8L * 1024 * 1024;

    // only stargate ships a system webview old enough to need this ota. shelly
    // hasnt published a webviewupdate for the newer wall displays... those
    // already come with a modern chromium (or so i think)
    private static final Map<String, String> UPDATE_URLS;
    static {
        Map<String, String> m = new HashMap<>();
        m.put(DeviceModel.STARGATE.sku,
                "https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1-WebViewUpdate.zip");
        UPDATE_URLS = Collections.unmodifiableMap(m);
    }

    // same cutoff the oem uses to decide webview is ready for ha. anything
    // above this major renders the modern ha dashboard cleanly (also a guess)
    private static final int MIN_READY_MAJOR = 100;

    private static final ExecutorService DOWNLOAD_POOL = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean DOWNLOAD_IN_PROGRESS = new AtomicBoolean(false);

    public interface Listener {
        void onProgress(int percent);
        void onCompleted(File staged);
        void onFailed(String reason);
    }

    public interface RebootListener {
        void onFailed(String reason);
    }

    private WebViewUpdater() {}

    public static String getUpdateUrl() {
        String url = UPDATE_URLS.get(DeviceModel.getReportedDevice().sku);
        return url == null ? "" : url;
    }

    public static boolean hasUpdateUrl() {
        return !getUpdateUrl().isEmpty();
    }

    // stargate images sometimes carry com.android.webview rather than the
    // google variant... probe both
    public static String getInstalledWebViewVersion(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : new String[]{"com.google.android.webview", "com.android.webview"}) {
            try {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                if (info.versionName != null && !info.versionName.isEmpty()) return info.versionName;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return "";
    }

    public static boolean isUpdateNeeded(Context ctx) {
        if (!hasUpdateUrl()) return false;
        String version = getInstalledWebViewVersion(ctx);
        if (version.isEmpty()) return true;
        int dot = version.indexOf('.');
        String head = dot < 0 ? version : version.substring(0, dot);
        try {
            return Integer.parseInt(head) <= MIN_READY_MAJOR;
        } catch (NumberFormatException nfe) {
            // unparseable so push the update anyway
            return true;
        }
    }

    public static boolean isDownloadInProgress() {
        return DOWNLOAD_IN_PROGRESS.get();
    }

    // listener callbacks always fire on the main thread
    public static void downloadAndStage(Context ctx, Listener listener) {
        final String url = getUpdateUrl();
        if (url.isEmpty()) {
            postFailed(listener, "No update URL for this device");
            return;
        }
        if (!DOWNLOAD_IN_PROGRESS.compareAndSet(false, true)) {
            postFailed(listener, "Download already in progress");
            return;
        }
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        DOWNLOAD_POOL.execute(() -> {
            try {
                String notWritable = checkCacheWritable(app);
                if (notWritable != null) {
                    main.post(() -> listener.onFailed(notWritable));
                    return;
                }
                //noinspection ResultOfMethodCallIgnored
                TEMP_ZIP.delete();

                long expected = HttpDownloader.contentLength(HttpDownloader.defaultClient(), url);
                // an earlier run staged the same zip but never got to recovery
                if (expected > 0 && STAGED_ZIP.length() == expected) {
                    makeReadable(STAGED_ZIP);
                    main.post(() -> listener.onCompleted(STAGED_ZIP));
                    return;
                }
                //noinspection ResultOfMethodCallIgnored
                STAGED_ZIP.delete();

                String noSpace = checkCacheSpace(expected);
                if (noSpace != null) {
                    main.post(() -> listener.onFailed(noSpace));
                    return;
                }

                HttpDownloader.download(HttpDownloader.defaultClient(), url, TEMP_ZIP,
                        pct -> main.post(() -> listener.onProgress(pct)));
                String failure = stageDownloadedZip(expected);
                if (failure != null) {
                    main.post(() -> listener.onFailed(failure));
                } else {
                    main.post(() -> listener.onCompleted(STAGED_ZIP));
                }
            } catch (IOException e) {
                Log.e(TAG, "WebView update download failed", e);
                //noinspection ResultOfMethodCallIgnored
                TEMP_ZIP.delete();
                postFailed(listener, e.getMessage() != null ? e.getMessage() : "Download failed");
            } finally {
                DOWNLOAD_IN_PROGRESS.set(false);
            }
        });
    }

    // verifies the finished TEMP_ZIP and renames it into place. returns null on
    // success or a failure message otherwise and always deletes TEMP_ZIP on failure
    private static String stageDownloadedZip(long expected) {
        if (TEMP_ZIP.length() <= 0) {
            //noinspection ResultOfMethodCallIgnored
            TEMP_ZIP.delete();
            return "Downloaded file is empty";
        }
        if (expected > 0 && TEMP_ZIP.length() != expected) {
            long got = TEMP_ZIP.length();
            //noinspection ResultOfMethodCallIgnored
            TEMP_ZIP.delete();
            return "Download incomplete (" + got + " of " + expected + " bytes)";
        }
        if (!TEMP_ZIP.renameTo(STAGED_ZIP)) {
            //noinspection ResultOfMethodCallIgnored
            TEMP_ZIP.delete();
            return "Could not rename update.zip.part to update.zip";
        }
        makeReadable(STAGED_ZIP);
        return null;
    }

    // null when /cache is usable. a probe file is the only reliable test
    // since the cache gid only shows up after a boot as priv-app
    private static String checkCacheWritable(Context ctx) {
        if (!CACHE_DIR.isDirectory()) return "/cache does not exist on this device";
        File probe = new File(CACHE_DIR, ".shellyelevate_probe");
        try (FileOutputStream ignored = new FileOutputStream(probe)) {
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return null;
        } catch (IOException e) {
            String who = "uid " + android.os.Process.myUid();
            if (!PrivAppInstaller.isPrivApp(ctx)) {
                return "Cannot write /cache (" + who + "). ShellyElevate must be installed as a priv-app, see the wiki";
            }
            return "Cannot write /cache (" + who + " lacks the cache group). Reboot once so the priv-app permissions apply, or use the manual steps in the wiki";
        }
    }

    private static String checkCacheSpace(long expected) {
        if (expected <= 0) return null;
        long free;
        try {
            free = new StatFs(CACHE_DIR.getAbsolutePath()).getAvailableBytes();
        } catch (Exception e) {
            // if we cant tell let the download decide
            return null;
        }
        if (free >= expected + SPACE_MARGIN_BYTES) return null;
        return "Not enough space in /cache: need " + mb(expected + SPACE_MARGIN_BYTES) + " MB, " + mb(free) + " MB free";
    }

    private static long mb(long bytes) {
        return (bytes + (1 << 20) - 1) >> 20;
    }

    // recovery runs as root but keep it world readable for debugging over adb
    private static void makeReadable(File f) {
        //noinspection ResultOfMethodCallIgnored
        f.setReadable(true, false);
    }

    private static void postFailed(Listener listener, String reason) {
        new Handler(Looper.getMainLooper()).post(() -> listener.onFailed(reason));
    }

    // only returns through the listener when every reboot path failed
    public static void rebootToInstall(Context ctx, RebootListener listener) {
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        DOWNLOAD_POOL.execute(() -> {
            String reason = tryRebootToRecovery(app);
            // still alive so recovery was never reached
            mSharedPreferences.edit().remove(SP_WEBVIEW_UPDATE_PENDING_FROM).commit();
            main.post(() -> listener.onFailed(reason));
        });
    }

    private static String tryRebootToRecovery(Context ctx) {
        if (!STAGED_ZIP.isFile()) return "Staged update.zip is missing, download it again";
        makeReadable(STAGED_ZIP);

        // command file first. recovery falls back to it when the bcb is empty
        boolean commandWritten = writeRecoveryCommand();
        // commit so the marker survives the reboot
        mSharedPreferences.edit().putString(SP_WEBVIEW_UPDATE_PENDING_FROM, getInstalledWebViewVersion(ctx)).commit();

        // sets the bcb through system_server and reboots
        try {
            RecoverySystem.installPackage(ctx, STAGED_ZIP);
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "installPackage failed: " + e.getMessage());
        }
        if (!commandWritten) {
            return "Could not write /cache/recovery/command and the system refused to set up recovery";
        }

        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) pm.reboot("recovery");
        } catch (SecurityException e) {
            Log.w(TAG, "PowerManager reboot denied: " + e.getMessage());
        }

        // same thing adb reboot recovery does
        PrivilegedShell.Result r = PrivilegedShell.run("reboot", "recovery");
        Log.e(TAG, "reboot recovery exited with " + r.exitCode + " " + r.stderr.trim());
        return "Reboot into recovery failed";
    }

    // init resets /cache/recovery on every boot so never assume it exists
    private static boolean writeRecoveryCommand() {
        //noinspection ResultOfMethodCallIgnored
        RECOVERY_DIR.mkdirs();
        String cmd = "--update_package=" + STAGED_ZIP.getAbsolutePath() + "\n";
        try (OutputStream out = new FileOutputStream(RECOVERY_COMMAND)) {
            out.write(cmd.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "could not write recovery command: " + e.getMessage());
            return false;
        }
    }

    // call once on start. returns a message for the user when an update
    // attempt just finished and cleans up the staged files
    public static String consumeUpdateOutcome(Context ctx) {
        if (mSharedPreferences == null) return null;
        String from = mSharedPreferences.getString(SP_WEBVIEW_UPDATE_PENDING_FROM, null);
        if (from == null) {
            // leftover zip from an older attempt eats most of /cache
            if (STAGED_ZIP.exists() && !isUpdateNeeded(ctx)) deleteStagedFiles();
            return null;
        }
        mSharedPreferences.edit().remove(SP_WEBVIEW_UPDATE_PENDING_FROM).apply();

        String now = getInstalledWebViewVersion(ctx);
        boolean updated = !now.equals(from) && !isUpdateNeeded(ctx);
        String lastInstall = readLastInstallResult();
        deleteStagedFiles();

        if (updated) return ctx.getString(R.string.webview_update_result_ok, now);
        String shown = now.isEmpty() ? ctx.getString(R.string.webview_update_version_unknown) : now;
        int detail = "0".equals(lastInstall)
                ? R.string.webview_update_result_rejected
                : R.string.webview_update_result_not_run;
        return ctx.getString(R.string.webview_update_result_failed, shown, ctx.getString(detail));
    }

    // second line of last_install is 1 on success and 0 on failure
    private static String readLastInstallResult() {
        try (BufferedReader r = new BufferedReader(new FileReader(LAST_INSTALL))) {
            r.readLine();
            String result = r.readLine();
            return result == null ? null : result.trim();
        } catch (IOException e) {
            return null;
        }
    }

    // a stale command file would retry the ota on the next recovery boot
    private static void deleteStagedFiles() {
        //noinspection ResultOfMethodCallIgnored
        STAGED_ZIP.delete();
        //noinspection ResultOfMethodCallIgnored
        TEMP_ZIP.delete();
        //noinspection ResultOfMethodCallIgnored
        RECOVERY_COMMAND.delete();
    }
}
