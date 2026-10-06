package me.rapierxbox.shellyelevatev2.deprecated;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import me.rapierxbox.shellyelevatev2.Constants;

// every feature that the home assistant integration replaces and that a later release removes
// the whole deprecated package goes away with that release so nothing outside the wiring points imports it
/** @deprecated registry of the features the removal release deletes together with this package */
@Deprecated
public final class DeprecatedFeatures {
    private static final String TAG = "DeprecatedFeatures";

    private DeprecatedFeatures() {}

    /** @deprecated see the feature descriptions */
    @Deprecated
    public enum Feature {
        // esphome native api bluetooth proxy on port 6053
        ESPHOME_PROXY("esphome_proxy", Constants.SP_BLUETOOTH_PROXY_ENABLED, Constants.SP_BLE_SCANNER_ENABLED,
                new String[]{Constants.SP_BLUETOOTH_PROXY_ENABLED, Constants.SP_BLUETOOTH_PROXY_NAME}),
        // own home assistant websocket voice satellite with a long lived token
        HA_SATELLITE("ha_satellite", Constants.SP_VOICE_ASSISTANT_ENABLED, Constants.SP_HA_VOICE_ENABLED,
                new String[]{Constants.SP_VOICE_ASSISTANT_ENABLED, Constants.SP_VOICE_ASSISTANT_TOKEN,
                        Constants.SP_VOICE_ASSISTANT_PIPELINE_ID});

        public final String id;
        // pref that switches the feature on
        public final String enableKey;
        // pref of the replacement that the integration drives
        public final String replacementKey;
        // every pref that only this feature reads
        public final String[] keys;

        Feature(String id, String enableKey, String replacementKey, String[] keys) {
            this.id = id;
            this.enableKey = enableKey;
            this.replacementKey = replacementKey;
            this.keys = keys;
        }
    }

    // first app version that marks the features deprecated
    public static final String SINCE = "3.26279";

    private static final Set<Feature> warned = ConcurrentHashMap.newKeySet();

    public static boolean isEnabled(Feature feature) {
        return mSharedPreferences != null && mSharedPreferences.getBoolean(feature.enableKey, false);
    }

    // ids of the deprecated features that are switched on for the api info and the legacy root
    public static List<String> activeIds() {
        List<String> ids = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (isEnabled(feature)) ids.add(feature.id);
        }
        return Collections.unmodifiableList(ids);
    }

    // true when the key belongs to a deprecated feature
    public static Feature featureOf(String key) {
        for (Feature feature : Feature.values()) {
            for (String k : feature.keys) {
                if (k.equals(key)) return feature;
            }
        }
        return null;
    }

    // logs once per process so a user who still runs the feature sees it in the log
    public static void warnOnce(Feature feature) {
        if (warned.add(feature)) {
            Log.w(TAG, feature.id + " is deprecated since " + SINCE
                    + " and will be removed. use the Shelly Elevate Home Assistant integration instead");
        }
    }
}
