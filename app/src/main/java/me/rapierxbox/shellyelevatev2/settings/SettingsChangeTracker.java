package me.rapierxbox.shellyelevatev2.settings;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.Objects;

import me.rapierxbox.shellyelevatev2.SettingsParser;

// one place that sees every settings write from the ui the legacy api and the v1 api
// and reports the registry keys whose resolved value changed
public final class SettingsChangeTracker {
    private static final String TAG = "SettingsChangeTracker";
    private static final long DEBOUNCE_MS = 300;

    // strong reference since shared preferences only keeps listeners weakly
    private static SettingsChangeTracker instance;

    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable flush = this::flush;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> onChanged(key);
    // last resolved values so only real changes are reported. main thread only
    private Map<String, Object> snapshot;
    private boolean pending;

    private SettingsChangeTracker(SharedPreferences prefs) {
        this.prefs = prefs;
        snapshot = SettingsRegistry.resolvedValues(prefs.getAll());
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public static synchronized void start(SharedPreferences prefs) {
        if (instance == null) instance = new SettingsChangeTracker(prefs);
    }

    public static synchronized void stop() {
        if (instance == null) return;
        instance.prefs.unregisterOnSharedPreferenceChangeListener(instance.listener);
        instance.handler.removeCallbacks(instance.flush);
        instance = null;
    }

    // called on the main thread. a null key means the prefs were cleared
    private void onChanged(String key) {
        if (key != null && SettingsRegistry.get(key) == null) return;
        if (pending) return;
        pending = true;
        handler.postDelayed(flush, DEBOUNCE_MS);
    }

    private void flush() {
        pending = false;
        Map<String, Object> now = SettingsRegistry.resolvedValues(prefs.getAll());
        JSONObject changes = new JSONObject();
        try {
            for (Map.Entry<String, Object> entry : now.entrySet()) {
                if (Objects.equals(snapshot.get(entry.getKey()), entry.getValue())) continue;
                changes.put(entry.getKey(), SettingsRegistry.toJson(entry.getValue()));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Could not build settings changes", e);
            return;
        }
        snapshot = now;
        if (changes.length() > 0) SettingsParser.dispatchChanges(changes);
    }
}
