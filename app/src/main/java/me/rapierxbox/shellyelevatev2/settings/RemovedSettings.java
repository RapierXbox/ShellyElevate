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
        // users of a removed feature keep the feature through its replacement in the integration
        // both only stream to a controller and voice records nothing before the first one connects
        editor = carryOver(prefs, editor, "bluetoothProxyEnabled", "bleScannerEnabled");
        editor = carryOver(prefs, editor, "voiceAssistantEnabled", "haVoiceEnabled");
        for (String key : KEYS) {
            if (!prefs.contains(key)) continue;
            if (editor == null) editor = prefs.edit();
            editor.remove(key);
            Log.i(TAG, "Removed the setting " + key + " of a removed feature");
        }
        if (editor != null) editor.apply();
    }

    private static SharedPreferences.Editor carryOver(SharedPreferences prefs, SharedPreferences.Editor editor,
                                                      String removedKey, String replacementKey) {
        if (!prefs.getBoolean(removedKey, false) || prefs.contains(replacementKey)) return editor;
        if (editor == null) editor = prefs.edit();
        editor.putBoolean(replacementKey, true);
        Log.i(TAG, removedKey + " was on so " + replacementKey + " is switched on");
        return editor;
    }
}
