package me.rapierxbox.shellyelevatev2.helper;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.ByteString;

public final class HttpDownloader {
    private static final String TAG = "HttpDownloader";

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    // build the ssl context once and share it across the file fetchers such as
    // wake word models and webview ota since it is not free to construct
    private static volatile OkHttpClient sharedClient;

    private HttpDownloader() {}

    // validates certificates and host names. some android 7 firmwares miss the roots
    // github and repo.shelly.cloud chain to so those are bundled next to the system store
    // models updates and the webview ota all come through here so nothing may trust all
    public static OkHttpClient defaultClient() {
        OkHttpClient c = sharedClient;
        if (c != null) return c;
        synchronized (HttpDownloader.class) {
            if (sharedClient == null) sharedClient = buildClient();
            return sharedClient;
        }
    }

    private static OkHttpClient buildClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS);
        try {
            BundledTrustManager trust = BundledTrustManager.create();
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{trust}, null);
            builder.sslSocketFactory(ctx.getSocketFactory(), trust);
        } catch (Exception e) {
            // still validating with the system store only
            Log.e(TAG, "Bundled roots unavailable", e);
        }
        return builder.build();
    }

    // throws when the file does not have the expected sha256. a null hash checks nothing
    public static void requireSha256(File file, String expectedHex) throws IOException {
        if (expectedHex == null) return;
        String actual;
        try (InputStream in = new FileInputStream(file)) {
            actual = ByteString.read(in, (int) file.length()).sha256().hex();
        }
        if (!actual.equalsIgnoreCase(expectedHex)) {
            throw new IOException("sha256 mismatch for " + file.getName() + ": " + actual);
        }
    }

    // -1 when the server does not report a length
    public static long contentLength(OkHttpClient client, String url) {
        Request req = new Request.Builder().url(url).head().header("User-Agent", "ShellyElevateV2").build();
        try (Response res = client.newCall(req).execute()) {
            if (!res.isSuccessful()) return -1;
            String len = res.header("Content-Length");
            return len == null ? -1 : Long.parseLong(len.trim());
        } catch (IOException | NumberFormatException e) {
            Log.w(TAG, "HEAD failed for " + url + ": " + e.getMessage());
            return -1;
        }
    }

    public static void download(OkHttpClient client, String url, File dest, ProgressCallback progress) throws IOException {
        download(client, url, dest, progress, (BooleanSupplier) null);
    }

    // a set cancel flag stops the transfer between chunks with an InterruptedIOException
    public static void download(OkHttpClient client, String url, File dest, ProgressCallback progress,
                                AtomicBoolean cancel) throws IOException {
        download(client, url, dest, progress, cancel != null ? (BooleanSupplier) cancel::get : null);
    }

    public static void download(OkHttpClient client, String url, File dest, ProgressCallback progress,
                                BooleanSupplier cancelled) throws IOException {
        Request req = new Request.Builder().url(url).header("User-Agent", "ShellyElevateV2").build();
        try (Response res = client.newCall(req).execute()) {
            if (!res.isSuccessful()) throw new IOException("HTTP " + res.code() + " for " + url);
            ResponseBody body = res.body();
            if (body == null) throw new IOException("Empty body for " + url);

            long total = body.contentLength();
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();

            long read = 0;
            int lastPercent = -1;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest));
                 InputStream in = body.byteStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled != null && cancelled.getAsBoolean()) throw new InterruptedIOException("Cancelled");
                    out.write(buf, 0, n);
                    read += n;
                    // only whole percent steps so the ui thread is not flooded with posts
                    int percent = total > 0 ? (int) (read * 100 / total) : -1;
                    if (percent != lastPercent && percent >= 0 && progress != null) {
                        lastPercent = percent;
                        progress.onProgress(percent);
                    }
                }
            }
        }
        if (progress != null) progress.onProgress(100);
    }
}
