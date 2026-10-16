package me.rapierxbox.shellyelevatev2.api;

import static me.rapierxbox.shellyelevatev2.Constants.DISPLAY_MODULE_WEBVIEW;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_AOD_STARTED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_AOD_STOPPED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_LIGHT_UPDATED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_UPDATED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STARTED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STOPPED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_STATE_CHANGED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_AUTOMATIC_BRIGHTNESS;
import static me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_URL;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mNightModeManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwInputHandler;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.display.DisplayModuleRegistry;
import me.rapierxbox.shellyelevatev2.display.webview.WebViewDisplayModule;
import me.rapierxbox.shellyelevatev2.helper.ThermalZoneReader;
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManager;
import me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities;
import me.rapierxbox.shellyelevatev2.stes.StesProtocolHandler;

// the flat state of protocol-v1 section 5 for the integration api and mqtt
// the broadcasts of the managers become ApiHub.stateChanged so every listener hears them
public final class StateReader {
    private static final String TAG = "ApiState";
    private static StateReader shared;

    private final Context context;
    // without a light sensor there is no lux and automatic brightness never applies
    private final boolean hasLightSensor;
    // polled values since only the managers above broadcast their changes
    private final Map<String, Object> sensorCache = new HashMap<>();
    private final Map<String, Object> slowCache = new HashMap<>();

    public static synchronized StateReader get(Context context) {
        if (shared == null) shared = new StateReader(context.getApplicationContext());
        return shared;
    }

    private StateReader(Context context) {
        this.context = context;
        hasLightSensor = DeviceCapabilities.hasLightSensor(context);
        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_LIGHT_UPDATED);
        filter.addAction(INTENT_PROXIMITY_UPDATED);
        filter.addAction(INTENT_SCREEN_SAVER_STARTED);
        filter.addAction(INTENT_SCREEN_SAVER_STOPPED);
        filter.addAction(INTENT_AOD_STARTED);
        filter.addAction(INTENT_AOD_STOPPED);
        filter.addAction(INTENT_VOICE_STATE_CHANGED);
        filter.addAction(INTENT_SETTINGS_CHANGED);
        LocalBroadcastManager.getInstance(context).registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) { ApiHub.stateChanged(); }
        }, filter);
    }

    // fresh reads the polled values first. otherwise their last reading is used
    public JSONObject snapshot(boolean fresh) {
        return toJson(build(fresh));
    }

    Map<String, Object> build(boolean fresh) {
        if (fresh) {
            refreshSensors();
            refreshSlow();
        }
        Map<String, Object> state = new LinkedHashMap<>();
        DeviceModel device = DeviceModel.getReportedDevice();

        if (mDeviceHelper != null) {
            for (int i = 0; i < DeviceModel.apiRelayCount(); i++) {
                state.put("relay." + i, mDeviceHelper.getRelay(i));
            }
        }
        for (int i = 0; i < device.inputs; i++) {
            state.put("input." + i, mSwInputHandler != null ? mSwInputHandler.getLevel(i) : null);
        }

        if (mDeviceSensorManager != null) {
            if (hasLightSensor) state.put("lux", round1(mDeviceSensorManager.getLastMeasuredLux()));
            if (device.hasProximitySensor) {
                float distance = mDeviceSensorManager.getLastMeasuredDistance();
                float max = mDeviceSensorManager.getMaxProximitySensorValue();
                state.put("proximity", round1(distance));
                // same near rule as the screensaver wake and never present before the first reading
                state.put("presence", mDeviceSensorManager.hasProximityReading()
                        && ScreenSaverManager.isNear(distance, max));
            }
        }

        // the dimmer values are cached fields so they are read on every build
        if (mDeviceHelper != null && mDeviceHelper.isDimmerAttached()) {
            StesProtocolHandler.DimmerStatus status = mDeviceHelper.getDimmerStatus();
            StesProtocolHandler.DimmerPower power = mDeviceHelper.getDimmerPower();
            if (status != null) {
                state.put("dimmer.on", status.on);
                state.put("dimmer.brightness", status.actualBrightness / 10);
            }
            if (power != null) state.put("dimmer.power", round1(power.powerW));
        }

        boolean saverRunning = mScreenSaverManager != null && mScreenSaverManager.isScreenSaverRunning();
        state.put("screen.on", !saverRunning);
        if (mDeviceHelper != null) state.put("screen.brightness", mDeviceHelper.getScreenBrightness());
        state.put("screen.auto_brightness", hasLightSensor && mSharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true));
        state.put("night_mode", mNightModeManager != null && mNightModeManager.isEnabled());
        // the page on screen and the configured dashboard until the webview loaded one
        String shownUrl = DISPLAY_MODULE_WEBVIEW.equals(DisplayModuleRegistry.activeId(mSharedPreferences))
                ? WebViewDisplayModule.getShownUrl() : null;
        state.put("webview.url", shownUrl != null ? shownUrl : mSharedPreferences.getString(SP_WEBVIEW_URL, ""));

        for (ApiHub.StateProvider provider : ApiHub.stateProviders()) {
            try {
                provider.contribute(state);
            } catch (RuntimeException e) {
                Log.w(TAG, "State provider failed", e);
            }
        }
        synchronized (this) {
            state.putAll(sensorCache);
            state.putAll(slowCache);
        }
        return state;
    }

    void refreshSensors() {
        Map<String, Object> out = new HashMap<>();
        if (mDeviceHelper != null) {
            double temperature = mDeviceHelper.getTemperature();
            double humidity = mDeviceHelper.getHumidity();
            // -999 marks a missing sensor or a failed read and is never reported
            if (temperature > -100) out.put("temperature", round1(temperature));
            if (humidity > -100) out.put("humidity", round1(humidity));
        }
        synchronized (this) {
            sensorCache.clear();
            sensorCache.putAll(out);
        }
    }

    void refreshSlow() {
        Map<String, Object> out = new HashMap<>();
        out.put("uptime", SystemClock.elapsedRealtime() / 1000);
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
            // -127 is what android reports without a connection
            if (info != null && info.getRssi() > -127) out.put("wifi.rssi", info.getRssi());
        } catch (RuntimeException ignored) {
            // no wifi service
        }
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memory);
            out.put("memory.free", memory.availMem / (1024 * 1024));
        }
        Float cpu = cpuTemperature();
        if (cpu != null) out.put("cpu.temperature", round1(cpu));
        synchronized (this) {
            slowCache.clear();
            slowCache.putAll(out);
        }
    }

    static JSONObject toJson(Map<String, Object> map) {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            try {
                json.put(entry.getKey(), entry.getValue() == null ? JSONObject.NULL : entry.getValue());
            } catch (JSONException ignored) {
                // nan values are skipped
            }
        }
        return json;
    }

    // the hottest cpu or soc zone since zone names differ per soc
    private static Float cpuTemperature() {
        Float hottest = null;
        for (ThermalZoneReader.Zone zone : ThermalZoneReader.discoverZones()) {
            String type = zone.type.toLowerCase(Locale.ROOT);
            if (!type.contains("cpu") && !type.contains("soc") && !type.startsWith("mtktscpu")) continue;
            Float temp = ThermalZoneReader.readZoneTempC(zone);
            if (temp != null && (hottest == null || temp > hottest)) hottest = temp;
        }
        return hottest;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
