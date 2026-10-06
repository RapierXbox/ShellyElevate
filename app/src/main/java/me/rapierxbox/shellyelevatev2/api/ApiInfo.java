package me.rapierxbox.shellyelevatev2.api;

import static me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_CLIENTID;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
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
import me.rapierxbox.shellyelevatev2.helper.DeviceHelper;
import me.rapierxbox.shellyelevatev2.helper.PrivAppInstaller;

// identity and capabilities of this display for hello info and mdns
public final class ApiInfo {
    public static final String API_VERSION = "1.0";
    public static final int TLS_PORT = 8443;

    private static volatile String cachedMac;

    private ApiInfo() {}

    // the stable id equals the mqtt device id so an mqtt and a v1 entry describe the same display
    // MQTTServer replaces the shared legacy defaults the same way on its first start
    public static synchronized String deviceId() {
        String id = mSharedPreferences.getString(SP_MQTT_CLIENTID, "");
        if (id.equals("shellyelevate") || id.equals("shellywalldisplay") || id.length() <= 2) {
            id = "shellyelevate-" + UUID.randomUUID().toString().replace("-", "").substring(2, 6);
            mSharedPreferences.edit().putString(SP_MQTT_CLIENTID, id).apply();
        }
        return id;
    }

    public static String name() {
        return DeviceModel.getReportedDevice().displayName;
    }

    public static String codename() {
        return DeviceModel.getReportedDevice().name();
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
        json.put("model", DeviceModel.getReportedDevice().sku);
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

    static JSONObject capabilities(Context context) throws JSONException {
        DeviceModel device = DeviceModel.getReportedDevice();
        PackageManager pm = context.getPackageManager();
        SensorManager sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        JSONObject caps = new JSONObject();
        caps.put("relays", device.relays);
        // the second relay of these models only exists with the power base which the app cannot detect
        if (device.relays > 1) caps.put("optional_relays_from", 1);
        caps.put("inputs", device.inputs);
        caps.put("buttons", device.buttons);
        caps.put("proximity", device.hasProximitySensor);
        caps.put("power_button", device.hasPowerButton);
        caps.put("dimmer", mDeviceHelper != null && mDeviceHelper.isDimmerAttached());
        caps.put("temperature", DeviceHelper.hasTempAndHumSensor());
        caps.put("humidity", DeviceHelper.hasTempAndHumSensor());
        caps.put("lux", sensors != null && sensors.getDefaultSensor(Sensor.TYPE_LIGHT) != null);
        caps.put("speaker", true);
        caps.put("microphone", pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE));
        caps.put("bluetooth", BluetoothAdapter.getDefaultAdapter() != null);
        caps.put("screenshot", true);
        caps.put("self_update", canSelfUpdate(context));
        return caps;
    }

    static boolean canSelfUpdate(Context context) {
        return PrivAppInstaller.isPrivApp(context) && PrivAppInstaller.canInstallPackages(context);
    }
}
