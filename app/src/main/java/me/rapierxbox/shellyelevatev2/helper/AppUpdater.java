package me.rapierxbox.shellyelevatev2.helper;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import me.rapierxbox.shellyelevatev2.BuildConfig;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

// checks github releases and installs a newer apk by replacing the priv-app copy then rebooting
public final class AppUpdater {
    private static final String TAG = "AppUpdater";

    private static final String RELEASES_API =
            "https://api.github.com/repos/RapierXbox/ShellyElevate/releases/latest";

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean IN_PROGRESS = new AtomicBoolean(false);

    public static final class ReleaseInfo {
        public final String versionName; // tag without a leading v
        public final String apkUrl;

        ReleaseInfo(String versionName, String apkUrl) {
            this.versionName = versionName;
            this.apkUrl = apkUrl;
        }
    }

    public interface CheckListener {
        void onUpdateAvailable(ReleaseInfo info);
        void onUpToDate(String current);
        void onFailed(String reason);
    }

    public interface InstallListener {
        void onProgress(int percent);
        void onCompleted();
        void onFailed(String reason);
    }

    private AppUpdater() {}

    public static boolean isInProgress() {
        return IN_PROGRESS.get();
    }

    // callbacks fire on the main thread
    public static void checkForUpdate(CheckListener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        POOL.execute(() -> {
            try {
                ReleaseInfo info = fetchLatest();
                if (info == null) {
                    main.post(() -> listener.onFailed("No apk asset in latest release"));
                } else if (isNewer(BuildConfig.VERSION_NAME, info.versionName)) {
                    main.post(() -> listener.onUpdateAvailable(info));
                } else {
                    main.post(() -> listener.onUpToDate(BuildConfig.VERSION_NAME));
                }
            } catch (Exception e) {
                Log.e(TAG, "check failed", e);
                main.post(() -> listener.onFailed(msg(e)));
            }
        });
    }

    // callbacks fire on the main thread
    public static void downloadAndInstall(Context ctx, ReleaseInfo info, InstallListener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        if (!PrivAppInstaller.isPrivApp(ctx)) {
            main.post(() -> listener.onFailed("Not a system app. Run install-privapp first"));
            return;
        }
        if (!IN_PROGRESS.compareAndSet(false, true)) {
            main.post(() -> listener.onFailed("Update already in progress"));
            return;
        }
        File staging = new File(ctx.getCacheDir(), "app-update.apk");
        POOL.execute(() -> {
            try {
                HttpDownloader.download(HttpDownloader.defaultClient(), info.apkUrl, staging,
                        pct -> main.post(() -> listener.onProgress(pct)));
                if (staging.length() <= 0) throw new IOException("Downloaded file is empty");
                if (!signaturesMatch(ctx, staging)) {
                    Log.e(TAG, "apk signature mismatch, refusing to install");
                    throw new IOException("APK signature mismatch");
                }
                if (!PrivAppInstaller.hasSystemSpaceFor(staging.length()))
                    throw new IOException("Not enough space on /system");
                if (!PrivAppInstaller.installApk(staging))
                    throw new IOException("Install into /system failed");
                main.post(listener::onCompleted);
            } catch (Exception e) {
                Log.e(TAG, "install failed", e);
                main.post(() -> listener.onFailed(msg(e)));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                staging.delete();
                IN_PROGRESS.set(false);
            }
        });
    }

    public static void rebootToInstall() {
        try {
            int code = Runtime.getRuntime().exec("reboot").waitFor();
            if (code != 0) Log.e(TAG, "reboot command exited with " + code);
        } catch (IOException e) {
            Log.e(TAG, "reboot failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "reboot interrupted", e);
        }
    }

    // block installs signed with a different key than the running app
    @SuppressWarnings("deprecation")
    private static boolean signaturesMatch(Context ctx, File apk) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo remote = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            PackageInfo local = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            if (remote == null || remote.signatures == null || remote.signatures.length == 0) return false;
            if (local == null || local.signatures == null) return false;
            if (remote.signatures.length != local.signatures.length) return false;
            for (int i = 0; i < remote.signatures.length; i++) {
                if (!Arrays.equals(remote.signatures[i].toByteArray(), local.signatures[i].toByteArray())) return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "signature check failed", e);
            return false;
        }
    }

    private static ReleaseInfo fetchLatest() throws IOException {
        OkHttpClient client = HttpDownloader.defaultClient();
        Request req = new Request.Builder()
                .url(RELEASES_API)
                .header("User-Agent", "ShellyElevateV2")
                .header("Accept", "application/vnd.github+json")
                .build();
        try (Response res = client.newCall(req).execute()) {
            if (!res.isSuccessful()) throw new IOException("HTTP " + res.code());
            ResponseBody body = res.body();
            if (body == null) throw new IOException("Empty body");
            JSONObject root = new JSONObject(body.string());
            String tag = root.optString("tag_name", "");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            String apkUrl = pickApkUrl(root.optJSONArray("assets"));
            if (version.isEmpty() || apkUrl == null) return null;
            return new ReleaseInfo(version, apkUrl);
        } catch (JSONException e) {
            throw new IOException("Bad release json");
        }
    }

    // prefer the named asset and fall back to any other apk
    private static String pickApkUrl(JSONArray assets) {
        if (assets == null) return null;
        String fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            String url = a.optString("browser_download_url", "");
            if (!name.endsWith(".apk") || url.isEmpty()) continue;
            if (name.startsWith("ShellyElevateV2")) return url;
            fallback = url;
        }
        return fallback;
    }

    // compares 3.YYDDD.HHMM components. a bad parse returns false so we never overwrite blindly
    static boolean isNewer(String local, String remote) {
        int[] l = parse(local);
        int[] r = parse(remote);
        if (l == null || r == null) return false;
        for (int i = 0; i < 3; i++) {
            if (r[i] != l[i]) return r[i] > l[i];
        }
        return false;
    }

    private static int[] parse(String version) {
        if (version == null) return null;
        String v = version.startsWith("v") ? version.substring(1) : version;
        String[] parts = v.split("\\.");
        if (parts.length != 3) return null;
        try {
            return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String msg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : "Update failed";
    }
}
