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
                JSONArray arr = new JSONArray();
                for (Object o : (Set<?>) value) {
                    if (o != null) arr.put(String.valueOf(o));
                }
                settings.put(key, arr);
            } else if (value instanceof Float) {
                // JSON numbers are doubles; widen here so precision survives the round trip.
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

            // Explicit JSON null removes the key.
            if (value == JSONObject.NULL) {
                editor.remove(key);
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
                // Mixed-type arrays are dropped: SharedPreferences only stores StringSet.
            } else if (value instanceof Number) {
                Number num = (Number) value;
                double d = num.doubleValue();
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
        editor.apply();

        LocalBroadcastManager.getInstance(mApplicationContext)
                .sendBroadcast(new Intent(Constants.INTENT_SETTINGS_CHANGED));
    }
}
