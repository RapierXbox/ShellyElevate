package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.Intent;
import android.content.SharedPreferences;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

public class SettingsParser {
    public JSONObject getSettings() throws JSONException {
        JSONObject settings = new JSONObject();
        Map<String, ?> allPreferences = mSharedPreferences.getAll();
        for (Map.Entry<String, ?> entry : allPreferences.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Set) {
                // Explicitly convert String sets to JSON arrays for consistency
                JSONArray arr = new JSONArray();
                for (Object o : (Set<?>) value) {
                    if (o != null) arr.put(String.valueOf(o));
                }
                settings.put(key, arr);
            } else if (value instanceof Float) {
                // JSON numbers are doubles; preserve precision as double
                settings.put(key, ((Float) value).doubleValue());
            } else {
                settings.put(key, value);
            }
        }
        return settings;
    }

    public void setSettings(JSONObject settings) throws JSONException {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        for (Iterator<String> it = settings.keys(); it.hasNext(); ) {
            String key = it.next();
            Object value = settings.get(key);

            // Support removal via explicit null
            if (value == JSONObject.NULL) {
                editor.remove(key);
                continue;
            }

            if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof JSONArray) {
                // Only accept arrays of strings as StringSet
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
                // If not all strings, ignore this key to avoid corrupting prefs
            } else if (value instanceof Number) {
                Number num = (Number) value;
                double d = num.doubleValue();
                boolean isWhole = Math.floor(d) == d && !Double.isInfinite(d) && !Double.isNaN(d);
                if (!isWhole) {
                    // Fractional -> store as float (SharedPreferences supports float)
                    editor.putFloat(key, (float) d);
                } else {
                    // Whole number: choose int if within range, otherwise long
                    if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                        editor.putInt(key, (int) d);
                    } else {
                        editor.putLong(key, (long) d);
                    }
                }
            }
            // Unknown types are ignored to maintain prefs integrity
        }
        editor.apply();

        // Notify listeners that settings changed (keeps components in sync)
        LocalBroadcastManager.getInstance(mApplicationContext)
                .sendBroadcast(new Intent(Constants.INTENT_SETTINGS_CHANGED));
    }
}
