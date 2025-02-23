package me.rapierxbox.shellyelevatev2;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

public class SettingsParser {
    public static JSONObject getSettings(SharedPreferences sharedPreferences) throws JSONException {
        JSONObject settings = new JSONObject();
        for (String key : sharedPreferences.getAll().keySet()) {
            settings.put(key, sharedPreferences.getAll().get(key));
        }
        return settings;
    }

    public static void setSettings(SharedPreferences sharedPreferences, JSONObject settings) throws JSONException {
        SharedPreferences.Editor editor = sharedPreferences.edit();
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
    }
}
