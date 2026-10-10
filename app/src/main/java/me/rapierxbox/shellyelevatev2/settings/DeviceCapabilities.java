package me.rapierxbox.shellyelevatev2.settings;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.helper.DeviceHelper;
import me.rapierxbox.shellyelevatev2.helper.PrivAppInstaller;

// what this unit has as the v1 info capabilities report it
// the settings requires entries name these keys
public final class DeviceCapabilities {
    private DeviceCapabilities() {}

    public static final String RELAYS = "relays";
    public static final String OPTIONAL_RELAYS_FROM = "optional_relays_from";
    public static final String INPUTS = "inputs";
    public static final String BUTTONS = "buttons";
    public static final String PROXIMITY = "proximity";
    public static final String POWER_BUTTON = "power_button";
    public static final String DIMMER = "dimmer";
    public static final String TEMPERATURE = "temperature";
    public static final String HUMIDITY = "humidity";
    public static final String LUX = "lux";
    public static final String SPEAKER = "speaker";
    public static final String MICROPHONE = "microphone";
    public static final String BLUETOOTH = "bluetooth";
    public static final String SCREENSHOT = "screenshot";
    public static final String SELF_UPDATE = "self_update";
    // the dashboard takes a home assistant login over ha_login.set
    public static final String HA_LOGIN = "ha_login";

    // integers and booleans by capability name
    public static Map<String, Object> snapshot(Context context) {
        DeviceModel device = DeviceModel.getReportedDevice();
        Map<String, Object> caps = new LinkedHashMap<>();
        // none on unknown hardware since the fallback relay paths are only a guess
        int relays = DeviceModel.apiRelayCount();
        caps.put(RELAYS, relays);
        // the second relay of these models only exists with the power base which the app cannot detect
        if (relays > 1) caps.put(OPTIONAL_RELAYS_FROM, 1);
        caps.put(INPUTS, device.inputs);
        caps.put(BUTTONS, device.buttons);
        caps.put(PROXIMITY, device.hasProximitySensor);
        caps.put(POWER_BUTTON, device.hasPowerButton);
        caps.put(DIMMER, mDeviceHelper != null && mDeviceHelper.isDimmerAttached());
        caps.put(TEMPERATURE, DeviceHelper.hasTempAndHumSensor());
        caps.put(HUMIDITY, DeviceHelper.hasTempAndHumSensor());
        caps.put(LUX, hasLightSensor(context));
        caps.put(SPEAKER, true);
        caps.put(MICROPHONE, hasMicrophone(context));
        caps.put(BLUETOOTH, BluetoothAdapter.getDefaultAdapter() != null);
        caps.put(SCREENSHOT, true);
        caps.put(SELF_UPDATE, canSelfUpdate(context));
        caps.put(HA_LOGIN, true);
        return Collections.unmodifiableMap(caps);
    }

    // some firmware leaves the feature flag out although a mic records fine
    public static boolean hasMicrophone(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
                || AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0;
    }

    public static boolean hasLightSensor(Context context) {
        SensorManager sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        return sensors != null && sensors.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
    }

    public static boolean canSelfUpdate(Context context) {
        return PrivAppInstaller.isPrivApp(context) && PrivAppInstaller.canInstallPackages(context);
    }
}
