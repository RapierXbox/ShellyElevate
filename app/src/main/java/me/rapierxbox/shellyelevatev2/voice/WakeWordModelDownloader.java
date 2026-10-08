package me.rapierxbox.shellyelevatev2.voice;

import java.io.File;
import java.io.IOException;

import me.rapierxbox.shellyelevatev2.helper.HttpDownloader;
import okhttp3.OkHttpClient;

// downloads wake word models without any ui so the settings screen and the voice engine share it
public final class WakeWordModelDownloader {
    public static final String DEFAULT_MODEL = "hey_jarvis";
    // the official hey jarvis next to the vad in the esphome repo
    private static final String DEFAULT_TFLITE_URL = "https://raw.githubusercontent.com/esphome/micro-wake-word-models/main/models/v2/hey_jarvis.tflite";
    private static final String DEFAULT_JSON_URL = "https://raw.githubusercontent.com/esphome/micro-wake-word-models/main/models/v2/hey_jarvis.json";

    private WakeWordModelDownloader() {}

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    // the tflite and the optional json as one install. both land in part files first so a
    // failure never touches an installed model and a model never sits next to half a config
    // blocks so call it off the main thread
    public static void download(OkHttpClient client, File dir, String name, String tfliteUrl, String jsonUrl,
                                ProgressCallback onProgress) throws IOException {
        File tflite = new File(dir, name + ".tflite");
        File json = new File(dir, name + ".json");
        File tflitePart = new File(dir, name + ".tflite.part");
        File jsonPart = new File(dir, name + ".json.part");
        boolean hasJson = jsonUrl != null && !jsonUrl.isEmpty();
        try {
            HttpDownloader.download(client, tfliteUrl, tflitePart,
                    pct -> onProgress.onProgress(hasJson ? pct / 2 : pct));
            if (hasJson) {
                HttpDownloader.download(client, jsonUrl, jsonPart,
                        pct -> onProgress.onProgress(50 + pct / 2));
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

    private static void rename(File from, File to) throws IOException {
        if (from.renameTo(to)) return;
        // not every filesystem lets a rename replace an existing file
        if (to.delete() && from.renameTo(to)) return;
        throw new IOException("could not rename " + from + " to " + to);
    }

    // the default model plus the vad that gates it
    public static void downloadDefault(OkHttpClient client, File dir) throws IOException {
        download(client, dir, DEFAULT_MODEL, DEFAULT_TFLITE_URL, DEFAULT_JSON_URL, pct -> {});
        WakeWordModelManager.ensureVadDownloaded(client, dir);
    }
}
