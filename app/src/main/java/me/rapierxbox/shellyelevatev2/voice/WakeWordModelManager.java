package me.rapierxbox.shellyelevatev2.voice;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.rapierxbox.shellyelevatev2.helper.HttpDownloader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

// lists installs and downloads microwakeword models from github
public final class WakeWordModelManager {
    private static final String TAG = "WakeWordModelManager";

    private static final String OFFICIAL_REPO = "esphome/micro-wake-word-models";
    private static final String EXPERIMENTAL_REPO = "TaterTotterson/microWakeWords";
    // one trees call returns every blob and dodges the 60 per hour contents api limit
    private static final String TREES_URL = "https://api.github.com/repos/%s/git/trees/HEAD?recursive=1";
    // raw downloads are unauthenticated and do not count against the api limit
    private static final String RAW_URL = "https://raw.githubusercontent.com/%s/HEAD/%s";

    // official esphome vad model that gates wake detections on voice activity
    // stored next to the wake word models as wakewords/vad.tflite and vad.json
    private static final String VAD_TFLITE_URL = "https://raw.githubusercontent.com/esphome/micro-wake-word-models/main/models/v2/vad.tflite";
    private static final String VAD_JSON_URL = "https://raw.githubusercontent.com/esphome/micro-wake-word-models/main/models/v2/vad.json";
    private static final String VAD_STEM = WakeWordDetector.VAD_MODEL_NAME;

    private static final String TFLITE_EXT = ".tflite";
    private static final String JSON_EXT = ".json";

    private WakeWordModelManager() {}

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    public enum VadResult { ALREADY_PRESENT, DOWNLOADED, FAILED }

    private interface RemoteFactory<T extends WakeWordModel.Remote> {
        T create(String name, String stem, String folderPath, String tfliteUrl, String jsonUrl);
    }

    // where installed wake word and vad models live
    public static File getModelDirectory(Context context) {
        return StreamingModel.modelDir(context);
    }

    public static boolean isVadPresent(File wakewordsDir) {
        return new File(wakewordsDir, VAD_STEM + TFLITE_EXT).exists()
                && new File(wakewordsDir, VAD_STEM + JSON_EXT).exists();
    }

    public static VadResult ensureVadDownloaded(OkHttpClient client, File wakewordsDir) {
        File tflite = new File(wakewordsDir, VAD_STEM + TFLITE_EXT);
        File json = new File(wakewordsDir, VAD_STEM + JSON_EXT);
        if (tflite.exists() && json.exists()) return VadResult.ALREADY_PRESENT;

        try {
            if (!tflite.exists()) downloadFile(client, VAD_TFLITE_URL, tflite, p -> {});
            if (!json.exists()) downloadFile(client, VAD_JSON_URL, json, p -> {});
            return VadResult.DOWNLOADED;
        } catch (Exception e) {
            Log.w(TAG, "VAD download failed: " + e.getMessage());
            // keep the pair all or nothing
            if (tflite.exists() && !json.exists()) tflite.delete();
            if (json.exists() && !tflite.exists()) json.delete();
            return VadResult.FAILED;
        }
    }

    public static List<WakeWordModel.Installed> getInstalledModels(File wakewordsDir) {
        File[] files = wakewordsDir.listFiles(f -> f.getName().endsWith(TFLITE_EXT));
        if (files == null) return Collections.emptyList();
        List<WakeWordModel.Installed> result = new ArrayList<>();
        for (File f : files) {
            String stem = stripExtension(f.getName(), TFLITE_EXT);
            if (!VAD_STEM.equals(stem)) result.add(new WakeWordModel.Installed(stem));
        }
        result.sort(Comparator.comparing(WakeWordModel.Installed::getName));
        return result;
    }

    public static List<WakeWordModel.Downloadable> fetchOfficialModels(OkHttpClient client) throws IOException {
        return fetchModelsFromRepo(client, OFFICIAL_REPO, WakeWordModel.Downloadable::new);
    }

    public static List<WakeWordModel.Experimental> fetchExperimentalModels(OkHttpClient client) throws IOException {
        return fetchModelsFromRepo(client, EXPERIMENTAL_REPO, WakeWordModel.Experimental::new);
    }

    private static <T extends WakeWordModel.Remote> List<T> fetchModelsFromRepo(
            OkHttpClient client, String repo, RemoteFactory<T> factory) throws IOException {
        JSONObject response = fetchJsonObject(client, String.format(TREES_URL, repo));
        JSONArray tree = response.optJSONArray("tree");
        if (tree == null) return Collections.emptyList();

        if (response.optBoolean("truncated"))
            Log.w(TAG, repo + " tree was truncated, some models may be missing");

        Set<String> allPaths = new HashSet<>();
        List<String> tflitePaths = new ArrayList<>();
        for (int i = 0; i < tree.length(); i++) {
            JSONObject entry = tree.optJSONObject(i);
            if (entry == null || !"blob".equals(entry.optString("type"))) continue;
            String path = entry.optString("path");
            allPaths.add(path);
            if (path.endsWith(TFLITE_EXT)) tflitePaths.add(path);
        }

        List<T> results = new ArrayList<>();
        for (String tflitePath : tflitePaths) {
            int lastSlash = tflitePath.lastIndexOf('/');
            String folder = lastSlash >= 0 ? tflitePath.substring(0, lastSlash) : "";
            String stem = stripExtension(tflitePath.substring(lastSlash + 1), TFLITE_EXT);

            // the vad is fetched on its own and is not offered as a wake word
            if (VAD_STEM.equals(stem)) continue;

            String jsonPath = folder.isEmpty() ? stem + JSON_EXT : folder + "/" + stem + JSON_EXT;
            String jsonUrl = allPaths.contains(jsonPath) ? String.format(RAW_URL, repo, jsonPath) : "";
            String tfliteUrl = String.format(RAW_URL, repo, tflitePath);

            results.add(factory.create(buildSaveName(stem, folder), stem, folder, tfliteUrl, jsonUrl));
        }

        results.sort(Comparator.comparing(WakeWordModel.Remote::getName));
        return results;
    }

    // two repos can ship the same stem so the unique folder parts are appended
    // to keep the files apart on disk
    //   okay_nabu/v2 with stem okay_nabu -> okay_nabu_v2
    //   the repo root with stem okay_nabu -> okay_nabu
    private static String buildSaveName(String stem, String folderPath) {
        if (folderPath.isEmpty()) return stem;
        StringBuilder qualifier = new StringBuilder();
        for (String part : folderPath.split("/")) {
            if (!part.isEmpty() && !part.equals(stem)) {
                if (qualifier.length() > 0) qualifier.append("_");
                qualifier.append(part);
            }
        }
        return qualifier.length() > 0 ? stem + "_" + qualifier : stem;
    }

    private static String stripExtension(String name, String ext) {
        return name.endsWith(ext) ? name.substring(0, name.length() - ext.length()) : name;
    }

    // throws so callers can tell a failed fetch apart from an empty repo
    private static JSONObject fetchJsonObject(OkHttpClient client, String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ShellyElevateV2")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful())
                throw new IOException("GitHub API returned " + response.code() + " for " + url);
            ResponseBody body = response.body();
            if (body == null) throw new IOException("empty response for " + url);
            return new JSONObject(body.string());
        } catch (JSONException e) {
            throw new IOException("invalid JSON from " + url, e);
        }
    }

    public static void downloadFile(OkHttpClient client, String url, File destFile, ProgressCallback onProgress) throws IOException {
        // download to a temp file so an interrupted transfer never leaves a corrupt model
        File tmp = new File(destFile.getParentFile(), destFile.getName() + ".part");
        try {
            HttpDownloader.download(client, url, tmp, onProgress::onProgress);
            if (!tmp.renameTo(destFile))
                throw new IOException("could not rename " + tmp + " to " + destFile);
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            throw e;
        }
    }
}
