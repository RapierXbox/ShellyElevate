package me.rapierxbox.shellyelevatev2.settings;

import android.content.SharedPreferences;
import android.util.Log;

// prefs of features that were removed. cleared once so exports and the api stop showing them
public final class RemovedSettings {
    private static final String TAG = "RemovedSettings";

    // the esphome proxy and the token voice satellite replaced by the home assistant integration
    public static final String[] KEYS = {
            "bluetoothProxyEnabled",
            "bluetoothProxyName",
            "voiceAssistantEnabled",
            "voiceAssistantToken",
            "voiceAssistantPipelineId",
            "deprecatedMigrationPrompted",
    };

    private RemovedSettings() {}

    public static void clear(SharedPreferences prefs) {
        SharedPreferences.Editor editor = null;
        for (String key : KEYS) {
            if (!prefs.contains(key)) continue;
            if (editor == null) editor = prefs.edit();
            editor.remove(key);
            Log.i(TAG, "Removed the setting " + key + " of a removed feature");
        }
        if (editor != null) editor.apply();
    }
}
