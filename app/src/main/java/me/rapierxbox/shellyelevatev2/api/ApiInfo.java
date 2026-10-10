package me.rapierxbox.shellyelevatev2.api;

import static me.rapierxbox.shellyelevatev2.Constants.SP_API_DEVICE_ID;
import static me.rapierxbox.shellyelevatev2.Constants.SP_DEVICE_NAME;
import static me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_CLIENTID;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.helper.PrivAppInstaller;
import me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities;

// identity and capabilities of this display for hello info and mdns
public final class ApiInfo {
    public static final String API_VERSION = "1.0";
    public static final int TLS_PORT = 8443;

    private static volatile String cachedMac;

    private ApiInfo() {}

    // the mqtt client id. the shared legacy defaults are replaced with a random one
    public static synchronized String mqttClientId() {
        String id = mSharedPreferences.getString(SP_MQTT_CLIENTID, "");
        if (id.equals("shellyelevate") || id.equals("shellywalldisplay") || id.length() <= 2) {
            id = "shellyelevate-" + UUID.randomUUID().toString().replace("-", "").substring(2, 6);
            mSharedPreferences.edit().putString(SP_MQTT_CLIENTID, id).apply();
        }
        return id;
    }

    // the v1 api id starts as the mqtt id so an mqtt and a v1 entry describe the same display
    // it is stored on its own so renaming the mqtt id does not break existing pairings
    public static synchronized String deviceId() {
        String id = mSharedPreferences.getString(SP_API_DEVICE_ID, "");
        if (id.isEmpty()) {
            id = mqttClientId();
            mSharedPreferences.edit().putString(SP_API_DEVICE_ID, id).apply();
        }
        return id;
    }

    public static String name() {
        String custom = mSharedPreferences.getString(SP_DEVICE_NAME, "").trim();
        return custom.isEmpty() ? defaultName() : custom;
    }

    // model name plus the last 4 of the mac so every display is distinct out of the box
    public static String defaultName() {
        String base = DeviceModel.getReportedDevice().displayName;
        String mac = mac().replace(":", "");
        String suffix = "";
        if (mac.length() >= 4) {
            suffix = mac.substring(mac.length() - 4);
        } else {
            String id = deviceId();
            if (id.length() >= 4) suffix = id.substring(id.length() - 4);
        }
        return suffix.isEmpty() ? base : base + " " + suffix;
    }

    public static String codename() {
        return DeviceModel.apiCodename();
    }

    public static String mac() {
        String mac = cachedMac;
        if (mac != null) return mac;
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!"wlan0".equals(nif.getName())) continue;
                byte[] hw = nif.getHardwareAddress();
                if (hw == null || hw.length != 6) continue;
                StringBuilder sb = new StringBuilder();
                for (byte b : hw) {
                    if (sb.length() > 0) sb.append(':');
                    sb.append(String.format(Locale.ROOT, "%02X", b));
                }
                cachedMac = sb.toString();
                return cachedMac;
            }
        } catch (Exception ignored) {
            // no wlan0 or no permission
        }
        return "";
    }

    public static JSONObject hello(boolean paired) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", deviceId());
        json.put("mac", mac());
        json.put("model", DeviceModel.apiModel());
        json.put("codename", codename());
        json.put("name", name());
        json.put("fw", BuildConfig.VERSION_NAME);
        json.put("api", API_VERSION);
        json.put("paired", paired);
        return json;
    }

    public static JSONObject info(Context context) throws JSONException {
        JSONObject json = hello(true);
        json.remove("paired");
        json.put("android", Build.VERSION.RELEASE);
        json.put("privileged", PrivAppInstaller.isPrivApp(context));
        json.put("capabilities", capabilities(context));
        return json;
    }

    // shared with the settings ui so both agree on what the unit has
    static JSONObject capabilities(Context context) throws JSONException {
        return new JSONObject(DeviceCapabilities.snapshot(context));
    }

    static boolean canSelfUpdate(Context context) {
        return DeviceCapabilities.canSelfUpdate(context);
    }
}
