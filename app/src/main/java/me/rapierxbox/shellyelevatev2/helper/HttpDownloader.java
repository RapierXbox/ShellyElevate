package me.rapierxbox.shellyelevatev2.helper;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class HttpDownloader {
    private static final String TAG = "HttpDownloader";

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    // Build the SSL context once and share it across all the file fetchers
    // (wake-word models, WebView OTA, ...). Not free to construct.
    private static volatile OkHttpClient sharedClient;

    private HttpDownloader() {}

    // Android 7's CA store is missing modern roots (Sectigo, Let's Encrypt
    // cross-signs) and rejects fine hosts like github.com and repo.shelly.cloud.
    // The payloads here aren't authenticated beyond the byte stream anyway, so
    // trust-all is the pragmatic choice.
    public static OkHttpClient defaultClient() {
        OkHttpClient c = sharedClient;
        if (c != null) return c;
        synchronized (HttpDownloader.class) {
            if (sharedClient == null) sharedClient = buildTrustAllClient();
            return sharedClient;
        }
    }

    private static OkHttpClient buildTrustAllClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{ new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("SSL");
            ctx.init(null, trustAll, new SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(ctx.getSocketFactory(), (X509TrustManager) trustAll[0])
                    .hostnameVerifier((h, s) -> true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build trust-all client", e);
            return new OkHttpClient();
        }
    }

    public static void download(OkHttpClient client, String url, File dest, ProgressCallback progress) throws IOException {
        Request req = new Request.Builder().url(url).header("User-Agent", "ShellyElevateV2").build();
        try (Response res = client.newCall(req).execute()) {
            if (!res.isSuccessful()) throw new IOException("HTTP " + res.code() + " for " + url);
            ResponseBody body = res.body();
            if (body == null) throw new IOException("Empty body for " + url);

            long total = body.contentLength();
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();

            long read = 0;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest));
                 InputStream in = body.byteStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    read += n;
                    if (total > 0 && progress != null) progress.onProgress((int) (read * 100 / total));
                }
            }
        }
        if (progress != null) progress.onProgress(100);
    }
}
