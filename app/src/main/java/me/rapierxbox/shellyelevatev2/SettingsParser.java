package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.Intent;
import android.content.SharedPreferences;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import me.rapierxbox.shellyelevatev2.display.DisplayModuleRegistry;
import me.rapierxbox.shellyelevatev2.settings.SettingDef;
import me.rapierxbox.shellyelevatev2.settings.SettingsRegistry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.CopyOnWriteArrayList;

public class SettingsParser {
    // keys whose values must always be stored as Float in SharedPreferences
    // any component that calls getFloat() on these keys must be listed here so
    // that the http /settings api cannot accidentally corrupt them by writing
    // whole-number json values as Integer
    // display module float options join through the registry
    private static final Set<String> FLOAT_PREF_KEYS = buildFloatKeys();

    private static Set<String> buildFloatKeys() {
        Set<String> keys = new HashSet<>(Arrays.asList(
            Constants.SP_DYNAMIC_TEMP_OFFSET_BASELINE,
            Constants.SP_DYNAMIC_TEMP_OFFSET_K
        ));
        keys.addAll(DisplayModuleRegistry.floatKeys());
        return Collections.unmodifiableSet(keys);
    }

    public JSONObject getSettings() throws JSONException {
        JSONObject settings = new JSONObject();
        Map<String, ?> allPreferences = mSharedPreferences.getAll();
        for (Map.Entry<String, ?> entry : allPreferences.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Set) {
                JSONArray arr = new JSONArray();
                for (Object o : (Set<?>) value) {
                    if (o != null) arr.put(String.valueOf(o));
                }
                settings.put(key, arr);
            } else if (value instanceof Float) {
                // json numbers are doubles widen here so precision survives the round trip
                settings.put(key, ((Float) value).doubleValue());
            } else {
                settings.put(key, value);
            }
        }
        return settings;
    }

    // keys only the paired tls api and the settings screen may change
    // adb over wifi would otherwise be one unauthenticated request away for anyone on the lan
    private static final Set<String> TRUSTED_ONLY_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            Constants.SP_ADB_WIFI_ENABLED
    )));

    public static boolean isTrustedOnly(String key) {
        return TRUSTED_ONLY_KEYS.contains(key);
    }

    // the unauthenticated write path. returns the keys it refused to write
    public JSONArray setSettings(JSONObject settings) throws JSONException {
        JSONArray refused = new JSONArray();
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        // snapshot once so number writes can preserve the stored type of each key
        Map<String, ?> existingPrefs = mSharedPreferences.getAll();
        for (Iterator<String> it = settings.keys(); it.hasNext(); ) {
            String key = it.next();
            Object value = settings.get(key);
            // the v1 api id never changes once set
            if (Constants.SP_API_DEVICE_ID.equals(key)) continue;
            if (isTrustedOnly(key)) {
                refused.put(key);
                continue;
            }

            // explicit json null removes the key
            if (value == JSONObject.NULL) {
                editor.remove(key);
                continue;
            }

            // known keys follow the schema so a wrong type can never crash a later get at startup
            SettingDef def = SettingsRegistry.get(key);
            if (def != null) {
                if (Constants.SP_MQTT_CLIENTID.equals(key) && String.valueOf(value).matches(".*[/+#].*")) {
                    refused.put(key);
                    continue;
                }
                try {
                    write(editor, def, SettingsRegistry.coerce(def, value));
                } catch (IllegalArgumentException | ClassCastException e) {
                    refused.put(key);
                }
                continue;
            }

            if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof JSONArray) {
                JSONArray arr = (JSONArray) value;
                Set<String> set = new LinkedHashSet<>();
                boolean allStrings = true;
                for (int i = 0; i < arr.length(); i++) {
                    Object v = arr.get(i);
                    if (v == JSONObject.NULL) continue;
                    if (v instanceof String) {
                        set.add((String) v);
                    } else {
                        allStrings = false;
                        break;
                    }
                }
                if (allStrings) {
                    editor.putStringSet(key, set);
                }
                // mixed-type arrays are dropped SharedPreferences only stores StringSet
            } else if (value instanceof Number) {
                Number num = (Number) value;
                double d = num.doubleValue();
                // always use putFloat() for known Float pref keys regardless of whether
                // the current stored type is Float/Int/Long this repairs any corruption
                // caused by a previous write that stored a whole-number float as Integer
                Object existing = existingPrefs.get(key);
                if (FLOAT_PREF_KEYS.contains(key) || existing instanceof Float) {
                    editor.putFloat(key, (float) d);
                } else if (existing instanceof Integer) {
                    // keep the stored type or the next getInt would crash with a cast error
                    editor.putInt(key, (int) Math.round(d));
                } else if (existing instanceof Long) {
                    editor.putLong(key, Math.round(d));
                } else {
                    boolean isWhole = Math.floor(d) == d && !Double.isInfinite(d) && !Double.isNaN(d);
                    if (!isWhole) {
                        editor.putFloat(key, (float) d);
                    } else if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                        editor.putInt(key, (int) d);
                    } else {
                        editor.putLong(key, (long) d);
                    }
                }
            }
        }
        editor.apply();

        LocalBroadcastManager.getInstance(mApplicationContext)
                .sendBroadcast(new Intent(Constants.INTENT_SETTINGS_CHANGED));
        return refused;
    }

    // ---- v1 api ----

    // gets resolved values of every registry key whose value changed no matter who wrote it
    public interface ChangeListener {
        void onSettingsChanged(JSONObject resolvedChanges);
    }

    private static final CopyOnWriteArrayList<ChangeListener> changeListeners = new CopyOnWriteArrayList<>();

    public static void addChangeListener(ChangeListener listener) {
        changeListeners.addIfAbsent(listener);
    }

    public static void removeChangeListener(ChangeListener listener) {
        changeListeners.remove(listener);
    }

    // called by SettingsChangeTracker only so every write produces exactly one event
    public static void dispatchChanges(JSONObject resolvedChanges) {
        for (ChangeListener listener : changeListeners) {
            try {
                listener.onSettingsChanged(resolvedChanges);
            } catch (RuntimeException e) {
                android.util.Log.w("SettingsParser", "Settings listener failed", e);
            }
        }
    }

    public static final class PatchResult {
        // key to resolved value for keys whose value actually changed
        public final JSONObject changes;
        public final boolean restartRequired;
        // keys the schema does not know. they are skipped and not written
        public final JSONArray ignored;

        PatchResult(JSONObject changes, boolean restartRequired, JSONArray ignored) {
            this.changes = changes;
            this.restartRequired = restartRequired;
            this.ignored = ignored;
        }
    }

    // validates every key first so a bad value leaves all settings untouched
    // null resets a key to its default. unknown keys are skipped and reported as ignored
    // since GET /settings passes unknown stored keys through and a client may send them back
    public PatchResult applyPatch(JSONObject patch) throws IllegalArgumentException {
        Map<String, Object> writes = new LinkedHashMap<>();
        JSONArray ignored = new JSONArray();
        for (Iterator<String> it = patch.keys(); it.hasNext(); ) {
            String key = it.next();
            SettingDef def = SettingsRegistry.get(key);
            if (def == null) {
                ignored.put(key);
                continue;
            }
            Object value = patch.opt(key);
            // ApiInfo replaces short ids with a random one which would change the display identity
            if (Constants.SP_MQTT_CLIENTID.equals(key) && (value == null || value == JSONObject.NULL
                    || String.valueOf(value).trim().length() <= 2)) {
                throw new IllegalArgumentException(key + " must be longer than 2 characters");
            }
            // the id is a topic segment so a separator or wildcard would break every subscribe
            if (Constants.SP_MQTT_CLIENTID.equals(key) && String.valueOf(value).matches(".*[/+#].*")) {
                throw new IllegalArgumentException(key + " must not contain / + or #");
            }
            writes.put(key, value == null || value == JSONObject.NULL ? null : SettingsRegistry.coerce(def, value));
        }

        Map<String, Object> before = SettingsRegistry.resolvedValues(mSharedPreferences.getAll());
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        for (Map.Entry<String, Object> entry : writes.entrySet()) {
            write(editor, SettingsRegistry.get(entry.getKey()), entry.getValue());
        }
        editor.apply();
        Map<String, Object> after = SettingsRegistry.resolvedValues(mSharedPreferences.getAll());

        JSONObject changes = new JSONObject();
        boolean restart = false;
        try {
            for (String key : writes.keySet()) {
                Object now = after.get(key);
                if (Objects.equals(before.get(key), now)) continue;
                changes.put(key, SettingsRegistry.toJson(now));
                restart |= SettingsRegistry.get(key).requiresRestart;
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }

        if (!writes.isEmpty()) {
            LocalBroadcastManager.getInstance(mApplicationContext)
                    .sendBroadcast(new Intent(Constants.INTENT_SETTINGS_CHANGED));
        }
        return new PatchResult(changes, restart, ignored);
    }

    // stores with the shared preferences type every reader of the key expects
    private static void write(SharedPreferences.Editor editor, SettingDef def, Object value) {
        if (value == null) {
            editor.remove(def.key);
            return;
        }
        switch (def.type) {
            case SettingDef.TYPE_BOOL:
                editor.putBoolean(def.key, (Boolean) value);
                break;
            case SettingDef.TYPE_INT:
                editor.putInt(def.key, (Integer) value);
                break;
            case SettingDef.TYPE_FLOAT:
                editor.putFloat(def.key, ((Double) value).floatValue());
                break;
            case SettingDef.TYPE_ENUM:
                if (value instanceof Integer) editor.putInt(def.key, (Integer) value);
                else editor.putString(def.key, (String) value);
                break;
            case SettingDef.TYPE_STRING_LIST:
                @SuppressWarnings("unchecked") List<String> list = (List<String>) value;
                editor.putStringSet(def.key, new LinkedHashSet<>(list));
                break;
            default:
                editor.putString(def.key, (String) value);
                break;
        }
    }
}
