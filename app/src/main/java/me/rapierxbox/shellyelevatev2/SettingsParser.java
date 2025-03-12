package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.updateSPValues;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

public class SettingsParser {
    public JSONObject getSettings() throws JSONException {
        JSONObject settings = new JSONObject();
        Map<String, ?> allPreferences = mSharedPreferences.getAll();
        for (String key : allPreferences.keySet()) {
            settings.put(key, allPreferences.get(key));
        }
        return settings;
    }

    public void setSettings(JSONObject settings) throws JSONException {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        for (Iterator<String> it = settings.keys(); it.hasNext(); ) {
            String key = it.next();
            Class<?> type = settings.get(key).getClass();
            if (type.equals(String.class)) {
                editor.putString(key, settings.getString(key));
            } else if (type.equals(Integer.class)) {
                editor.putInt(key, settings.getInt(key));
            } else if (type.equals(Long.class)) {
                editor.putLong(key, settings.getLong(key));
            } else if (type.equals(Boolean.class)) {
                editor.putBoolean(key, settings.getBoolean(key));
            }
        }
        editor.apply();
        updateSPValues();
    }
}
