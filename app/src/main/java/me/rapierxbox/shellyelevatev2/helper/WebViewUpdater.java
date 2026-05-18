package me.rapierxbox.shellyelevatev2.helper;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import me.rapierxbox.shellyelevatev2.DeviceModel;

// Mirrors the OEM cloud.shelly.stargate WebView OTA flow: drop the device's
// WebViewUpdate.zip at /cache/update.zip and let the bootloader pick it up on
// the next reboot.
public final class WebViewUpdater {
    private static final String TAG = "WebViewUpdater";

    // The bootloader scans this exact path on boot. Don't rename it.
    public static final File STAGED_ZIP = new File("/cache/update.zip");

    // Only Stargate ships a system WebView old enough to need this OTA. Shelly
    // hasn't published a WebViewUpdate for the newer Wall Displays... those
    // already come with a modern Chromium (or so I think).
    private static final Map<String, String> UPDATE_URLS;
    static {
        Map<String, String> m = new HashMap<>();
        m.put(DeviceModel.STARGATE.sku,
                "https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1-WebViewUpdate.zip");
        UPDATE_URLS = Collections.unmodifiableMap(m);
    }

    // Same cutoff the OEM uses to decide "WebView is ready for HA". Anything
    // above this major renders the modern HA dashboard cleanly (also a guess).
    private static final int MIN_READY_MAJOR = 100;

    private static final ExecutorService DOWNLOAD_POOL = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean DOWNLOAD_IN_PROGRESS = new AtomicBoolean(false);

    public interface Listener {
        void onProgress(int percent);
        void onCompleted(File staged);
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

    // Stargate images sometimes carry com.android.webview rather than the
    // Google variant... probe both
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
            // Unparseable — push the update anyway.
            return true;
        }
    }

    public static boolean isDownloadInProgress() {
        return DOWNLOAD_IN_PROGRESS.get();
    }

    // Listener callbacks always fire on the main thread.
    public static void downloadAndStage(Listener listener) {
        final String url = getUpdateUrl();
        if (url.isEmpty()) {
            postFailed(listener, "No update URL for this device");
            return;
        }
        if (!DOWNLOAD_IN_PROGRESS.compareAndSet(false, true)) {
            postFailed(listener, "Download already in progress");
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        DOWNLOAD_POOL.execute(() -> {
            try {
                HttpDownloader.download(HttpDownloader.defaultClient(), url, STAGED_ZIP,
                        pct -> main.post(() -> listener.onProgress(pct)));
                if (STAGED_ZIP.length() <= 0) {
                    //noinspection ResultOfMethodCallIgnored
                    STAGED_ZIP.delete();
                    main.post(() -> listener.onFailed("Downloaded file is empty"));
                } else {
                    main.post(() -> listener.onCompleted(STAGED_ZIP));
                }
            } catch (IOException e) {
                Log.e(TAG, "WebView update download failed", e);
                //noinspection ResultOfMethodCallIgnored
                STAGED_ZIP.delete();
                postFailed(listener, e.getMessage() != null ? e.getMessage() : "Download failed");
            } finally {
                DOWNLOAD_IN_PROGRESS.set(false);
            }
        });
    }

    private static void postFailed(Listener listener, String reason) {
        new Handler(Looper.getMainLooper()).post(() -> listener.onFailed(reason));
    }

    public static void rebootToInstall() {
        try {
            Runtime.getRuntime().exec("reboot");
        } catch (IOException e) {
            Log.e(TAG, "Reboot failed", e);
        }
    }
}
