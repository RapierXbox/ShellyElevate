package me.rapierxbox.shellyelevatev2.helper;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import me.rapierxbox.shellyelevatev2.BuildConfig;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

// checks github releases and installs a newer apk through the package installer
// the system copy stays in /system/priv-app and the update lands in /data/app on top of it
public final class AppUpdater {
    private static final String TAG = "AppUpdater";

    // the newest releases first. pre releases share the version scheme and only carry the github flag
    private static final String RELEASES_API =
            "https://api.github.com/repos/RapierXbox/ShellyElevate/releases?per_page=20";

    private static final String ACTION_INSTALL_STATUS = BuildConfig.APPLICATION_ID + ".APP_UPDATE_STATUS";

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean IN_PROGRESS = new AtomicBoolean(false);
    // set by cancelDownload and checked between download chunks
    private static final AtomicBoolean CANCEL = new AtomicBoolean(false);

    public static final class ReleaseInfo {
        public final String versionName; // tag without a leading v
        public final String apkUrl;
        public final boolean prerelease;

        ReleaseInfo(String versionName, String apkUrl, boolean prerelease) {
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.prerelease = prerelease;
        }
    }

    public interface CheckListener {
        void onUpdateAvailable(ReleaseInfo info);
        void onUpToDate(String current);
        void onFailed(String reason);
    }

    public interface InstallListener {
        void onProgress(int percent);
        // the package manager kills and restarts the app once the install succeeds
        void onInstalling();
        void onFailed(String reason);
        void onCancelled();
    }

    private AppUpdater() {}

    public static boolean isInProgress() {
        return IN_PROGRESS.get();
    }

    // stops a running download. once the apk is handed to the installer it cannot be taken back
    public static void cancelDownload() {
        CANCEL.set(true);
    }

    // callbacks fire on the main thread. pre releases are only offered when asked for
    public static void checkForUpdate(boolean includePrerelease, CheckListener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        POOL.execute(() -> {
            try {
                ReleaseInfo info = fetchLatest(includePrerelease);
                if (info == null) {
                    main.post(() -> listener.onFailed("No release with an apk found"));
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
        install(ctx, info.apkUrl, null, listener);
    }

    // an apk named by a paired controller. the signature check still applies and sha256 is optional
    public static void installFromUrl(Context ctx, String apkUrl, String version, String sha256, InstallListener listener) {
        Log.i(TAG, "install of " + version + " requested");
        install(ctx, apkUrl, sha256, listener);
    }

    private static void install(Context ctx, String apkUrl, String expectedSha256, InstallListener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        Context app = ctx.getApplicationContext();
        if (!PrivAppInstaller.isPrivApp(app)) {
            main.post(() -> listener.onFailed("Not a system app. Run install-privapp first"));
            return;
        }
        if (!PrivAppInstaller.canInstallPackages(app)) {
            main.post(() -> listener.onFailed("Install permission missing. Run install-privapp once with this version"));
            return;
        }
        if (!IN_PROGRESS.compareAndSet(false, true)) {
            main.post(() -> listener.onFailed("Update already in progress"));
            return;
        }
        File staging = new File(app.getCacheDir(), "app-update.apk");
        CANCEL.set(false);
        POOL.execute(() -> {
            boolean committed = false;
            BroadcastReceiver statusReceiver = null;
            try {
                HttpDownloader.download(HttpDownloader.defaultClient(), apkUrl, staging,
                        pct -> main.post(() -> listener.onProgress(pct)), CANCEL);
                if (CANCEL.get()) throw new java.io.InterruptedIOException("Cancelled");
                if (staging.length() <= 0) throw new IOException("Downloaded file is empty");
                if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(sha256(staging))) {
                    throw new IOException("APK checksum mismatch");
                }
                if (!signaturesMatch(app, staging)) {
                    Log.e(TAG, "apk signature mismatch, refusing to install");
                    throw new IOException("APK signature mismatch");
                }
                statusReceiver = registerStatusReceiver(app, main, listener);
                commitSession(app, staging);
                committed = true;
                main.post(listener::onInstalling);
            } catch (Exception e) {
                // timeouts are interrupted io too so only the flag tells a real cancel apart
                if (CANCEL.get()) {
                    Log.i(TAG, "update download cancelled");
                    main.post(listener::onCancelled);
                    return;
                }
                Log.e(TAG, "install failed", e);
                main.post(() -> listener.onFailed(msg(e)));
            } finally {
                // the session holds its own copy once written
                //noinspection ResultOfMethodCallIgnored
                staging.delete();
                // after a commit the status receiver clears the flag
                // without one no status ever arrives so the receiver goes now
                if (!committed) {
                    if (statusReceiver != null) finish(app, statusReceiver);
                    IN_PROGRESS.set(false);
                }
            }
        });
    }

    private static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void commitSession(Context ctx, File apk) throws IOException {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        params.setSize(apk.length());
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            // the installer fills in the status extras so the intent must stay mutable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, sessionId, status, flags);
            session.commit(pi.getIntentSender());
        } catch (IOException | RuntimeException e) {
            try {
                installer.abandonSession(sessionId);
            } catch (RuntimeException ignored) {}
            throw e;
        }
    }

    // only failures arrive in practice since a successful self update kills this process
    private static BroadcastReceiver registerStatusReceiver(Context ctx, Handler main, InstallListener listener) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // a privileged installer never gets here so the grant is missing
                    finish(context, this);
                    main.post(() -> listener.onFailed("System asked for confirmation. Run install-privapp once with this version"));
                    return;
                }
                finish(context, this);
                if (status == PackageInstaller.STATUS_SUCCESS) return;
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.e(TAG, "install failed with status " + status + ": " + message);
                String reason = message != null ? message : "Install failed (" + status + ")";
                main.post(() -> listener.onFailed(reason));
            }
        };
        ContextCompat.registerReceiver(ctx, receiver, new IntentFilter(ACTION_INSTALL_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        return receiver;
    }

    private static void finish(Context ctx, BroadcastReceiver receiver) {
        try {
            ctx.getApplicationContext().unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {}
        IN_PROGRESS.set(false);
    }

    // block installs signed with a different key than the running app
    @SuppressWarnings("deprecation")
    private static boolean signaturesMatch(Context ctx, File apk) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo remote = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            PackageInfo local = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            // reject an apk for a different package before trusting its signatures
            if (remote == null || !ctx.getPackageName().equals(remote.packageName)) return false;
            if (remote.signatures == null || remote.signatures.length == 0) return false;
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

    // newest release with an apk. stable devices skip pre releases so a test build never reaches them
    private static ReleaseInfo fetchLatest(boolean includePrerelease) throws IOException {
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
            JSONArray releases = new JSONArray(body.string());
            // github lists the newest release first so the first of each kind is its newest
            ReleaseInfo stable = null;
            ReleaseInfo pre = null;
            for (int i = 0; i < releases.length() && (stable == null || pre == null); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null || release.optBoolean("draft", false)) continue;
                boolean prerelease = release.optBoolean("prerelease", false);
                String tag = release.optString("tag_name", "");
                String version = tag.startsWith("v") ? tag.substring(1) : tag;
                String apkUrl = pickApkUrl(release.optJSONArray("assets"));
                // old tags with a broken version format would otherwise look newer than everything
                if (apkUrl == null || !isCurrentScheme(version)) continue;
                if (prerelease && pre == null) pre = new ReleaseInfo(version, apkUrl, true);
                if (!prerelease && stable == null) stable = new ReleaseInfo(version, apkUrl, false);
            }
            return choose(stable, includePrerelease ? pre : null);
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

    // the stable release unless a pre release is strictly newer. so a device on the pre release
    // channel still moves to a main release as soon as that one is the newest
    static ReleaseInfo choose(ReleaseInfo stable, ReleaseInfo pre) {
        if (pre == null) return stable;
        if (stable == null) return pre;
        return isNewer(stable.versionName, pre.versionName) ? pre : stable;
    }

    // 3.YYDDD.HHMM exactly. early tags like 3.2026111.1918 used other widths
    static boolean isCurrentScheme(String version) {
        return version != null && version.matches("3\\.\\d{5}\\.\\d{4}");
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
