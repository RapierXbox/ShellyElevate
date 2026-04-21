package me.rapierxbox.shellyelevatev2.voice;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class WakeWordModelManager {
    private static final String TAG = "WakeWordModelManager";

    // One API call returns the full tree — avoids the 60 req/hour rate limit of the contents API
    private static final String OFFICIAL_REPO     = "esphome/micro-wake-word-models";
    private static final String EXPERIMENTAL_REPO = "TaterTotterson/microWakeWords";
    private static final String TREES_URL = "https://api.github.com/repos/%s/git/trees/HEAD?recursive=1";
    // Raw downloads don't count against the API rate limit and don't need auth
    private static final String RAW_URL   = "https://raw.githubusercontent.com/%s/HEAD/%s";

    private WakeWordModelManager() {}

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    // ─────────────── Local models ───────────────

    public static List<WakeWordModel.Installed> getInstalledModels(File wakewordsDir) {
        if (!wakewordsDir.exists()) return Collections.emptyList();
        File[] files = wakewordsDir.listFiles(f -> f.getName().endsWith(".tflite"));
        if (files == null) return Collections.emptyList();
        List<WakeWordModel.Installed> result = new ArrayList<>();
        for (File f : files) {
            String stem = f.getName().substring(0, f.getName().length() - 7);
            result.add(new WakeWordModel.Installed(stem));
        }
        result.sort(Comparator.comparing(WakeWordModel.Installed::getName));
        return result;
    }

    // ─────────────── GitHub fetch ───────────────

    public static List<WakeWordModel.Downloadable> fetchOfficialModels(OkHttpClient client) throws IOException {
        List<RawModel> raw = fetchModelsFromRepo(client, OFFICIAL_REPO);
        List<WakeWordModel.Downloadable> result = new ArrayList<>(raw.size());
        for (RawModel m : raw)
            result.add(new WakeWordModel.Downloadable(m.saveName, m.stem, m.folderPath, m.tfliteUrl, m.jsonUrl));
        return result;
    }

    public static List<WakeWordModel.Experimental> fetchExperimentalModels(OkHttpClient client) throws IOException {
        List<RawModel> raw = fetchModelsFromRepo(client, EXPERIMENTAL_REPO);
        List<WakeWordModel.Experimental> result = new ArrayList<>(raw.size());
        for (RawModel m : raw)
            result.add(new WakeWordModel.Experimental(m.saveName, m.stem, m.folderPath, m.tfliteUrl, m.jsonUrl));
        return result;
    }

    private static class RawModel {
        final String stem;       // original filename stem, e.g. "okay_nabu"
        final String saveName;   // unique filename for saving, e.g. "okay_nabu_v2"
        final String folderPath; // path to parent dir in repo, e.g. "okay_nabu/v2"
        final String tfliteUrl;
        final String jsonUrl;
        RawModel(String stem, String saveName, String folderPath, String tfliteUrl, String jsonUrl) {
            this.stem = stem; this.saveName = saveName; this.folderPath = folderPath;
            this.tfliteUrl = tfliteUrl; this.jsonUrl = jsonUrl;
        }
    }

    // Single API request gets the full recursive file tree — no BFS, no rate limit issues
    private static List<RawModel> fetchModelsFromRepo(OkHttpClient client, String repo) throws IOException {
        String treeUrl = String.format(TREES_URL, repo);
        JSONObject response = fetchJsonObject(client, treeUrl);
        if (response == null) return Collections.emptyList();

        JSONArray tree = response.optJSONArray("tree");
        if (tree == null) return Collections.emptyList();

        if (response.optBoolean("truncated"))
            Log.w(TAG, repo + " tree was truncated — some models may be missing");

        // Collect all file paths for fast json lookup
        Set<String> allPaths = new HashSet<>();
        List<String> tflitePaths = new ArrayList<>();
        for (int i = 0; i < tree.length(); i++) {
            JSONObject entry = tree.optJSONObject(i);
            if (entry == null || !"blob".equals(entry.optString("type"))) continue;
            String path = entry.optString("path");
            allPaths.add(path);
            if (path.endsWith(".tflite")) tflitePaths.add(path);
        }

        List<RawModel> results = new ArrayList<>();
        for (String tflitePath : tflitePaths) {
            int lastSlash  = tflitePath.lastIndexOf('/');
            String folder  = lastSlash >= 0 ? tflitePath.substring(0, lastSlash) : "";
            String filename = lastSlash >= 0 ? tflitePath.substring(lastSlash + 1) : tflitePath;
            String stem    = filename.substring(0, filename.length() - 7); // strip .tflite

            String tfliteUrl = String.format(RAW_URL, repo, tflitePath);

            String jsonPath = folder.isEmpty() ? stem + ".json" : folder + "/" + stem + ".json";
            String jsonUrl  = allPaths.contains(jsonPath)
                    ? String.format(RAW_URL, repo, jsonPath) : "";

            results.add(new RawModel(stem, buildSaveName(stem, folder), folder, tfliteUrl, jsonUrl));
        }

        results.sort(Comparator.comparing(m -> m.saveName));
        return results;
    }

    // Builds a unique save name by appending path parts that differ from the stem.
    // "okay_nabu/v2" + stem "okay_nabu" → "okay_nabu_v2"
    // ""            + stem "okay_nabu" → "okay_nabu"
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

    private static JSONObject fetchJsonObject(OkHttpClient client, String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ShellyElevateV2")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "GitHub API returned " + response.code() + " for " + url);
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) return null;
            return new JSONObject(body.string());
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch " + url + ": " + e.getMessage());
            return null;
        }
    }

    // ─────────────── Download ───────────────

    public static void downloadFile(OkHttpClient client, String url, File destFile, ProgressCallback onProgress) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "ShellyElevateV2")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " for " + url);
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty body for " + url);

            long total = body.contentLength();
            File parent = destFile.getParentFile();
            if (parent != null) parent.mkdirs();

            long bytesRead = 0;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(destFile));
                 InputStream input = body.byteStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = input.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    bytesRead += n;
                    if (total > 0) onProgress.onProgress((int) (bytesRead * 100 / total));
                }
            }
        }
        onProgress.onProgress(100);
    }

    // ─────────────── Custom import ───────────────

    public static String importCustomModel(Context context, Uri tfliteUri, File wakewordsDir) throws IOException {
        String stem = resolveStem(context, tfliteUri);
        if (stem == null) throw new IOException("Cannot resolve filename from URI");

        wakewordsDir.mkdirs();
        File destTflite = new File(wakewordsDir, stem + ".tflite");

        boolean success = false;
        try (InputStream input  = context.getContentResolver().openInputStream(tfliteUri);
             OutputStream output = new FileOutputStream(destTflite)) {
            if (input == null) throw new IOException("Cannot open input stream for " + tfliteUri);
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) != -1) output.write(buf, 0, n);
            success = true;
        } finally {
            if (!success) destTflite.delete();
        }

        tryImportSiblingJson(context, tfliteUri, stem, wakewordsDir);
        return stem;
    }

    private static String resolveStem(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null)
                        return name.endsWith(".tflite") ? name.substring(0, name.length() - 7) : name;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveStem failed: " + e.getMessage());
        }
        return null;
    }

    // Best-effort: detector handles missing json gracefully so failure here is fine
    private static void tryImportSiblingJson(Context context, Uri tfliteUri, String stem, File wakewordsDir) {
        try {
            String docId     = DocumentsContract.getDocumentId(tfliteUri);
            int colonIdx     = docId.indexOf(':');
            if (colonIdx < 0) return;
            String authority = docId.substring(0, colonIdx);
            String path      = docId.substring(colonIdx + 1);
            int lastSlash    = path.lastIndexOf('/');
            String dir       = lastSlash >= 0 ? path.substring(0, lastSlash) : "";
            String jsonPath  = dir.isEmpty() ? stem + ".json" : dir + "/" + stem + ".json";
            String jsonId    = authority + ":" + jsonPath;

            String uriPath    = tfliteUri.getPath();
            String parentPath = uriPath != null ? uriPath.substring(0, uriPath.lastIndexOf('/')) : "";
            Uri treeBase = tfliteUri.buildUpon().path(parentPath).build();
            Uri jsonUri  = DocumentsContract.buildDocumentUriUsingTree(treeBase, jsonId);

            try (InputStream input  = context.getContentResolver().openInputStream(jsonUri);
                 OutputStream output = new FileOutputStream(new File(wakewordsDir, stem + ".json"))) {
                if (input != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = input.read(buf)) != -1) output.write(buf, 0, n);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "No sibling .json found for " + stem + " — proceeding without it");
        }
    }
}
