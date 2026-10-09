package me.rapierxbox.shellyelevatev2.voice;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import me.rapierxbox.shellyelevatev2.helper.HttpDownloader;
import okhttp3.OkHttpClient;

// downloads wake word models without any ui so the settings screen and the voice engine share it
public final class WakeWordModelDownloader {
    public static final String DEFAULT_MODEL = "hey_jarvis";
    // the automatic downloads are pinned to a commit and checked against these hashes
    // so a changed branch or a swapped file never lands on the display unasked
    static final String MODELS_BASE =
            "https://raw.githubusercontent.com/esphome/micro-wake-word-models/40ff33f57f8fc6ad71a75ef085abbf742495b225/models/v2/";
    // the official hey jarvis next to the vad in the esphome repo
    private static final String DEFAULT_TFLITE_URL = MODELS_BASE + "hey_jarvis.tflite";
    private static final String DEFAULT_TFLITE_SHA256 = "21a7976add39ee24ec96c63d96b7aaa18e24d1d9824b963e451da8feb4b78b77";
    private static final String DEFAULT_JSON_URL = MODELS_BASE + "hey_jarvis.json";
    private static final String DEFAULT_JSON_SHA256 = "b153867d818675d8abcc9dace474afe7f83551ae0d5a9b1d71a98681320185af";

    // older than any running download could be
    private static final long STALE_PART_MS = 60 * 60 * 1000L;

    private WakeWordModelDownloader() {}

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    public static void download(OkHttpClient client, File dir, String name, String tfliteUrl, String jsonUrl,
                                ProgressCallback onProgress) throws IOException {
        download(client, dir, name, tfliteUrl, jsonUrl, onProgress, null);
    }

    // the tflite and the optional json as one install. both land in part files first so a
    // failure never touches an installed model and a model never sits next to half a config
    // the part names are unique so the settings screen and the voice engine never share one
    // blocks so call it off the main thread. cancelled stops it with an InterruptedIOException
    public static void download(OkHttpClient client, File dir, String name, String tfliteUrl, String jsonUrl,
                                ProgressCallback onProgress, BooleanSupplier cancelled) throws IOException {
        download(client, dir, name, tfliteUrl, null, jsonUrl, null, onProgress, cancelled);
    }

    // a null hash skips the check for models the user picks from the live repo listing
    static void download(OkHttpClient client, File dir, String name, String tfliteUrl, String tfliteSha256,
                         String jsonUrl, String jsonSha256, ProgressCallback onProgress,
                         BooleanSupplier cancelled) throws IOException {
        File tflite = new File(dir, name + ".tflite");
        File json = new File(dir, name + ".json");
        File tflitePart = partFile(dir, name + ".tflite");
        File jsonPart = partFile(dir, name + ".json");
        boolean hasJson = jsonUrl != null && !jsonUrl.isEmpty();
        try {
            HttpDownloader.download(client, tfliteUrl, tflitePart,
                    pct -> onProgress.onProgress(hasJson ? pct / 2 : pct), cancelled);
            HttpDownloader.requireSha256(tflitePart, tfliteSha256);
            if (hasJson) {
                HttpDownloader.download(client, jsonUrl, jsonPart,
                        pct -> onProgress.onProgress(50 + pct / 2), cancelled);
                HttpDownloader.requireSha256(jsonPart, jsonSha256);
                if (cancelled != null && cancelled.getAsBoolean()) throw new InterruptedIOException("Cancelled");
                rename(jsonPart, json);
            }
            // the tflite last since its presence is what makes the model installed
            rename(tflitePart, tflite);
        } catch (IOException | RuntimeException e) {
            tflitePart.delete();
            jsonPart.delete();
            throw e;
        }
    }

    // a temp name no other download of the same file uses
    // also drops part files a killed process left behind
    static File partFile(File dir, String fileName) {
        File[] stale = dir.listFiles((d, n) -> n.endsWith(".part"));
        long cutoff = System.currentTimeMillis() - STALE_PART_MS;
        if (stale != null) {
            for (File f : stale) if (f.lastModified() < cutoff) f.delete();
        }
        return new File(dir, fileName + "." + UUID.randomUUID().toString().substring(0, 8) + ".part");
    }

    private static void rename(File from, File to) throws IOException {
        if (from.renameTo(to)) return;
        // not every filesystem lets a rename replace an existing file
        if (to.delete() && from.renameTo(to)) return;
        throw new IOException("could not rename " + from + " to " + to);
    }

    // the default model plus the vad that gates it
    public static void downloadDefault(OkHttpClient client, File dir) throws IOException {
        download(client, dir, DEFAULT_MODEL, DEFAULT_TFLITE_URL, DEFAULT_TFLITE_SHA256,
                DEFAULT_JSON_URL, DEFAULT_JSON_SHA256, pct -> {}, null);
        WakeWordModelManager.ensureVadDownloaded(client, dir);
    }
}
