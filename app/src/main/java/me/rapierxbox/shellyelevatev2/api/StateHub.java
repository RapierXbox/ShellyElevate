package me.rapierxbox.shellyelevatev2.api;

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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.helper.ThermalZoneReader;
import me.rapierxbox.shellyelevatev2.stes.StesProtocolHandler;

// the flat state of protocol-v1 section 5 and the state_delta push
// events from the managers only poke it. it rebuilds the state and pushes the keys that changed
final class StateHub {
    private static final String TAG = "ApiState";
    private static final long POKE_DEBOUNCE_MS = 50;
    // sht3x and dimmer values have no change broadcast so they are polled
    private static final long SENSOR_PERIOD_S = 10;
    // values that change all the time are only refreshed this often
    private static final long SLOW_PERIOD_S = 60;

    interface Pusher {
        void push(JSONObject message);
    }

    private final Context context;
    private final Pusher pusher;
    private final AtomicBoolean pokeScheduled = new AtomicBoolean(false);
    private final Map<String, Object> sensorCache = new HashMap<>();
    private final Map<String, Object> slowCache = new HashMap<>();
    private Map<String, Object> last = new LinkedHashMap<>();
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> sensorTick;
    private ScheduledFuture<?> slowTick;
    private BroadcastReceiver receiver;

    StateHub(Context context, Pusher pusher) {
        this.context = context.getApplicationContext();
        this.pusher = pusher;
    }

    synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, TAG));
        ApiHub.setStateChangedHook(this::poke);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                poke();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_LIGHT_UPDATED);
        filter.addAction(INTENT_PROXIMITY_UPDATED);
        filter.addAction(INTENT_SCREEN_SAVER_STARTED);
        filter.addAction(INTENT_SCREEN_SAVER_STOPPED);
        filter.addAction(INTENT_AOD_STARTED);
        filter.addAction(INTENT_AOD_STOPPED);
        filter.addAction(INTENT_VOICE_STATE_CHANGED);
        filter.addAction(INTENT_SETTINGS_CHANGED);
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter);
    }

    synchronized void stop() {
        ApiHub.setStateChangedHook(null);
        if (receiver != null) {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver);
            receiver = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // sensors are only polled while a controller listens
    synchronized void onControllerChanged(boolean connected) {
        if (executor == null) return;
        if (connected && sensorTick == null) {
            sensorTick = executor.scheduleWithFixedDelay(this::refreshSensors, 0, SENSOR_PERIOD_S, TimeUnit.SECONDS);
            slowTick = executor.scheduleWithFixedDelay(this::refreshSlow, 0, SLOW_PERIOD_S, TimeUnit.SECONDS);
        } else if (!connected && sensorTick != null) {
            sensorTick.cancel(false);
            slowTick.cancel(false);
            sensorTick = null;
            slowTick = null;
        }
    }

    void poke() {
        if (!ApiHub.hasController() || !pokeScheduled.compareAndSet(false, true)) return;
        ScheduledExecutorService ex = executor;
        try {
            if (ex != null) {
                ex.schedule(() -> {
                    pokeScheduled.set(false);
                    pushDelta();
                }, POKE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
                return;
            }
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
        pokeScheduled.set(false);
    }

    // the current state without touching the delta baseline. for GET /api/v1/state
    JSONObject snapshot() {
        return toJson(build(true));
    }

    // pushes pending changes to the connected controllers then hands a fresh snapshot to a new one
    // runs under the lock that pushDelta uses so the new controller never misses a change in between
    synchronized void attach(SnapshotConsumer consumer) {
        Map<String, Object> state = build(true);
        Map<String, Object> changes = diff(last, state);
        if (!changes.isEmpty()) send(changes);
        last = state;
        consumer.accept(toJson(state));
    }

    interface SnapshotConsumer {
        void accept(JSONObject state);
    }

    private synchronized void pushDelta() {
        try {
            Map<String, Object> state = build(false);
            Map<String, Object> changes = diff(last, state);
            if (changes.isEmpty()) return;
            last = state;
            send(changes);
        } catch (RuntimeException e) {
            Log.w(TAG, "State update failed", e);
        }
    }

    private void send(Map<String, Object> changes) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "state_delta");
            message.put("changes", toJson(changes));
            pusher.push(message);
        } catch (JSONException e) {
            Log.w(TAG, "Could not build state_delta", e);
        }
    }

    private void refreshSensors() {
        try {
            Map<String, Object> fresh = new HashMap<>();
            readSensors(fresh);
            synchronized (this) {
                sensorCache.clear();
                sensorCache.putAll(fresh);
            }
            pushDelta();
        } catch (RuntimeException e) {
            Log.w(TAG, "Sensor refresh failed", e);
        }
    }

    private void refreshSlow() {
        try {
            Map<String, Object> fresh = new HashMap<>();
            readSlow(fresh);
            synchronized (this) {
                slowCache.clear();
                slowCache.putAll(fresh);
            }
            pushDelta();
        } catch (RuntimeException e) {
            Log.w(TAG, "Slow refresh failed", e);
        }
    }

    private static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey()) || !Objects.equals(before.get(entry.getKey()), entry.getValue())) {
                changes.put(entry.getKey(), entry.getValue());
            }
        }
        // a key that went away is reported as null
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) changes.put(key, null);
        }
        return changes;
    }

    private static JSONObject toJson(Map<String, Object> map) {
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

    // fresh reads every polled value when asked to. otherwise the caches are used
    private Map<String, Object> build(boolean freshPolled) {
        Map<String, Object> state = new LinkedHashMap<>();
        DeviceModel device = DeviceModel.getReportedDevice();

        if (mDeviceHelper != null) {
            for (int i = 0; i < device.relays; i++) {
                state.put("relay." + i, mDeviceHelper.getRelay(i));
            }
        }
        for (int i = 0; i < device.inputs; i++) {
            state.put("input." + i, mSwInputHandler != null ? mSwInputHandler.getLevel(i) : null);
        }

        if (mDeviceSensorManager != null) {
            state.put("lux", round1(mDeviceSensorManager.getLastMeasuredLux()));
            if (device.hasProximitySensor) {
                float distance = mDeviceSensorManager.getLastMeasuredDistance();
                float max = mDeviceSensorManager.getMaxProximitySensorValue();
                state.put("proximity", round1(distance));
                state.put("presence", max > 0 && distance < max);
            }
        }

        boolean saverRunning = mScreenSaverManager != null && mScreenSaverManager.isScreenSaverRunning();
        state.put("screen.on", !saverRunning);
        if (mDeviceHelper != null) state.put("screen.brightness", mDeviceHelper.getScreenBrightness());
        state.put("screen.auto_brightness", mSharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true));
        state.put("night_mode", mNightModeManager != null && mNightModeManager.isEnabled());
        state.put("webview.url", mSharedPreferences.getString(SP_WEBVIEW_URL, ""));

        for (ApiHub.StateProvider provider : ApiHub.stateProviders()) {
            try {
                provider.contribute(state);
            } catch (RuntimeException e) {
                Log.w(TAG, "State provider failed", e);
            }
        }

        if (freshPolled) {
            Map<String, Object> sensors = new HashMap<>();
            Map<String, Object> slow = new HashMap<>();
            readSensors(sensors);
            readSlow(slow);
            synchronized (this) {
                sensorCache.clear();
                sensorCache.putAll(sensors);
                slowCache.clear();
                slowCache.putAll(slow);
            }
        }
        synchronized (this) {
            state.putAll(sensorCache);
            state.putAll(slowCache);
        }
        return state;
    }

    private void readSensors(Map<String, Object> out) {
        if (mDeviceHelper == null) return;
        double temperature = mDeviceHelper.getTemperature();
        double humidity = mDeviceHelper.getHumidity();
        // -999 marks a missing sensor or a failed read and is never reported
        if (temperature > -100) out.put("temperature", round1(temperature));
        if (humidity > -100) out.put("humidity", round1(humidity));
        if (mDeviceHelper.isDimmerAttached()) {
            StesProtocolHandler.DimmerStatus status = mDeviceHelper.getDimmerStatus();
            StesProtocolHandler.DimmerPower power = mDeviceHelper.getDimmerPower();
            if (status != null) {
                out.put("dimmer.on", status.on);
                out.put("dimmer.brightness", status.actualBrightness / 10);
            }
            if (power != null) out.put("dimmer.power", round1(power.powerW));
        }
    }

    private void readSlow(Map<String, Object> out) {
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
    }

    // the hottest cpu or soc zone since zone names differ per soc
    private static Float cpuTemperature() {
        Float hottest = null;
        for (ThermalZoneReader.Zone zone : ThermalZoneReader.discoverZones()) {
            String type = zone.type.toLowerCase(java.util.Locale.ROOT);
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
