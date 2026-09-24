package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_LIGHT_KEY;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_LIGHT_UPDATED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_KEY;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_UPDATED;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mButtonHandler;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwInputHandler;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.rapierxbox.shellyelevatev2.DeviceModel;

public class DeviceSensorManager implements SensorEventListener {
    private static final String TAG = "DeviceSensorManager";

    // getevent -l key names on the proximity node
    private static final String PROXIMITY_KEY_NEAR = "KEY_F5";
    private static final String PROXIMITY_KEY_FAR = "KEY_F6";

    // linux key codes of the same keys for the native monitor
    private static final int LINUX_KEY_F5_NEAR = 63;
    private static final int LINUX_KEY_F6_FAR = 64;
    private static final int KEY_ACTION_DOWN = 1;

    private static final long MIN_LUX_EVENT_INTERVAL_MS = 1000L;
    private static final float LUX_RELATIVE_THRESHOLD = 0.15f;

    private final Context context;
    private final SensorManager sensorManager;
    private final boolean lightSensorAvailable;
    private final Sensor proximitySensor;
    // negative when there is no sensormanager proximity sensor
    private final float fallbackProximityMaxRange;
    private final String[] inputEventPaths;
    // true when the hardware reports large values when near and 0 when far (x2i jenna)
    private final boolean invertProximity;

    // sensor callbacks run on the main thread but the getters are read from http and mqtt threads
    private volatile float lastMeasuredLux = 0.0f;
    private float lastPublishedLux = -1f;
    private long lastLuxBroadcastAtMs = 0L;
    // delivers the newest throttled reading since the light sensor only reports on change
    private final Handler luxHandler = new Handler(Looper.getMainLooper());
    private final Runnable trailingLux = () -> onLightChanged(lastMeasuredLux);

    private volatile float lastMeasuredDistance = 1.0f;
    private float lastPublishedProximity = -1f;
    private volatile float maxProximitySensorValue = 1.0f;

    private volatile boolean proximitySensorAvailable;
    private volatile boolean usingGpioKeysProximity = false;
    private volatile boolean gpioProximityConfirmed = false;
    private volatile boolean sensorManagerProximitySuppressed = false;

    private InputMonitor mInputMonitor;
    private ExecutorService proximityFallbackExecutor;
    private volatile Process proximityFallbackProcess;
    private volatile boolean destroyed = false;

    public DeviceSensorManager(Context ctx) {
        context = ctx;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            Log.d(TAG, sensor.getName());
        }

        Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        lightSensorAvailable = lightSensor != null;
        if (lightSensorAvailable) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        DeviceModel model = DeviceModel.getReportedDevice();
        inputEventPaths = model.getInputEventPaths();
        invertProximity = model.invertProximity;

        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximitySensor != null) {
            fallbackProximityMaxRange = proximitySensor.getMaximumRange();
            maxProximitySensorValue = fallbackProximityMaxRange;
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            Log.i(TAG, "SensorManager proximity sensor registered (max range " + fallbackProximityMaxRange + ")");
        } else {
            fallbackProximityMaxRange = -1f;
        }

        // native input monitor first then a getevent fallback
        // sensormanager proximity stays registered as a backup since not every gpio_keys emits KEY_F5 and KEY_F6
        if (inputEventPaths.length > 0 && InputMonitor.isAvailable()) {
            usingGpioKeysProximity = startNativeInputMonitor();
        }
        if (!usingGpioKeysProximity && inputEventPaths.length > 0) {
            usingGpioKeysProximity = startProximityKeyFallback();
        }
        if (fallbackProximityMaxRange >= 0f) {
            maxProximitySensorValue = fallbackProximityMaxRange;
            proximitySensorAvailable = true;
        } else if (usingGpioKeysProximity) {
            maxProximitySensorValue = 1f;
            proximitySensorAvailable = true;
        } else {
            proximitySensorAvailable = false;
            Log.w(TAG, "Proximity sensor unavailable (no gpio_keys or SensorManager sensor)");
        }
        if (usingGpioKeysProximity) {
            Log.i(TAG, "GPIO input active via " + (mInputMonitor != null ? "JNI" : "getevent"));
        }
    }

    public float getLastMeasuredLux() {
        return lastMeasuredLux;
    }

    public float getLastMeasuredDistance() {
        return lastMeasuredDistance;
    }

    public float getMaxProximitySensorValue() {
        return maxProximitySensorValue;
    }

    public boolean isLightSensorAvailable() {
        return lightSensorAvailable;
    }

    public boolean isProximitySensorAvailable() {
        return proximitySensorAvailable;
    }

    // makes the next proximity reading broadcast even when unchanged
    // call it when proximity has to be evaluated again (right after the screensaver starts)
    // so a user already in range can wake the screen without stepping away first
    public synchronized void resetProximityState() {
        lastPublishedProximity = -1f;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null) return;

        int type = event.sensor.getType();
        if (type == Sensor.TYPE_LIGHT) {
            onLightChanged(event.values[0]);
        } else if (type == Sensor.TYPE_PROXIMITY) {
            if (sensorManagerProximitySuppressed) return;
            float raw = event.values[0];
            // normalize to 0 = near and max = far since x2i reports the opposite polarity
            publishProximity(invertProximity ? (maxProximitySensorValue - raw) : raw);
        }
    }

    private void onLightChanged(float lux) {
        lastMeasuredLux = lux;

        boolean changedEnough;
        if (lastPublishedLux < 0f) {
            changedEnough = true;
        } else {
            float relativeChange = Math.abs(lux - lastPublishedLux) / Math.max(1f, lastPublishedLux);
            changedEnough = relativeChange >= LUX_RELATIVE_THRESHOLD;
        }

        long now = SystemClock.elapsedRealtime();
        long sinceLast = now - lastLuxBroadcastAtMs;
        luxHandler.removeCallbacks(trailingLux);
        if (sinceLast < MIN_LUX_EVENT_INTERVAL_MS) {
            if (!destroyed) luxHandler.postDelayed(trailingLux, MIN_LUX_EVENT_INTERVAL_MS - sinceLast);
            return;
        }

        if (changedEnough && mMQTTServer != null && mMQTTServer.shouldSend()) {
            mMQTTServer.publishLux(lux);
            lastPublishedLux = lux;
        }

        Intent intent = new Intent(INTENT_LIGHT_UPDATED);
        intent.putExtra(INTENT_LIGHT_KEY, lux);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        lastLuxBroadcastAtMs = now;
    }

    private boolean startNativeInputMonitor() {
        List<String> paths = new ArrayList<>();
        for (String p : inputEventPaths) {
            if (new File(p).exists()) paths.add(p);
        }
        if (paths.isEmpty()) return false;
        try {
            mInputMonitor = new InputMonitor();
            if (mInputMonitor.start(this::handleNativeKeyEvent, paths)) return true;
            // nothing opened natively so let the getevent fallback try
            Log.w(TAG, "Native input monitor opened no devices");
            mInputMonitor = null;
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "Native input monitor failed: " + t.getMessage());
            mInputMonitor = null;
            return false;
        }
    }

    // runs on the native monitor thread so it keeps working whichever activity has focus
    private void handleNativeKeyEvent(int keyCode, int action, int repeatCount) {
        // 87 and 88 (f11 and f12 edge pulses) plus 2 (key_1 level contact) are the two sw terminal schemes
        if (SwInputHandler.isNativeSwInputCode(keyCode)) {
            if (mSwInputHandler != null) mSwInputHandler.onNativeKey(keyCode, action);
            return;
        }
        // 59 to 62 (f1 to f4 capacitive buttons) and 68 (f10 power)
        // this path is the whole point of #101 since it keeps working while lite mode has another app in front
        if (ButtonHandler.isNativeButtonCode(keyCode)) {
            if (mButtonHandler != null) mButtonHandler.onNativeKey(keyCode, action);
            return;
        }
        if (action != KEY_ACTION_DOWN) return;
        if (keyCode == LINUX_KEY_F5_NEAR) {
            onGpioProximityEvent(true);
        } else if (keyCode == LINUX_KEY_F6_FAR) {
            onGpioProximityEvent(false);
        } else {
            Log.i(TAG, "Unhandled input key code on monitored event path: " + keyCode);
        }
    }

    private boolean startProximityKeyFallback() {
        // getevent only takes one node so use the first that exists
        String eventDevice = null;
        for (String p : inputEventPaths) {
            if (new File(p).exists()) {
                eventDevice = p;
                break;
            }
        }
        if (eventDevice == null) return false;
        final String device = eventDevice;
        try {
            proximityFallbackExecutor = Executors.newSingleThreadExecutor();
            proximityFallbackExecutor.execute(() -> runProximityKeyReader(device));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start proximity key reader", t);
            return false;
        }
    }

    private void runProximityKeyReader(String eventDevice) {
        Process process = null;
        try {
            process = new ProcessBuilder("getevent", "-l", eventDevice)
                    .redirectErrorStream(true)
                    .start();
            proximityFallbackProcess = process;
            // ondestroy may have run before the process was published so it would never be killed
            if (destroyed) return;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                    handleProximityKeyLine(line);
                }
            }
        } catch (IOException e) {
            if (!destroyed) Log.w(TAG, "Proximity key reader failed", e);
        } finally {
            if (process != null) process.destroy();
            proximityFallbackProcess = null;
            // sensormanager proximity keeps publishing on its own if it is registered
            if (usingGpioKeysProximity) {
                usingGpioKeysProximity = false;
                // dont register the sensor listener again while tearing down
                if (!destroyed) applyProximityFallback();
            }
        }
    }

    private synchronized void applyProximityFallback() {
        if (fallbackProximityMaxRange < 0f) {
            proximitySensorAvailable = false;
            Log.w(TAG, "Proximity sensor unavailable (no gpio_keys or SensorManager sensor)");
            return;
        }
        // the gpio reader is gone so bring back the sensormanager sensor if it was suppressed
        if (sensorManagerProximitySuppressed && proximitySensor != null) {
            try {
                sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            } catch (Exception e) {
                Log.w(TAG, "Failed to re-register SensorManager proximity sensor", e);
            }
        }
        gpioProximityConfirmed = false;
        sensorManagerProximitySuppressed = false;
        maxProximitySensorValue = fallbackProximityMaxRange;
        proximitySensorAvailable = true;
        Log.i(TAG, "Using SensorManager proximity sensor with max range " + maxProximitySensorValue);
    }

    // parses one getevent -l line like "/dev/input/event3: EV_KEY KEY_F5 DOWN"
    private void handleProximityKeyLine(String line) {
        String normalized = line.toUpperCase(Locale.US);
        boolean isDown = normalized.contains(" DOWN");

        // f11 and f12 are the rising and falling edge pulses of the sw terminal
        // like the proximity keys only the down line carries the transition
        if (normalized.contains("KEY_F11") || normalized.contains("KEY_F12")) {
            if (mSwInputHandler != null && isDown) {
                int code = normalized.contains("KEY_F11")
                        ? SwInputStateMachine.LINUX_KEY_SW_RISING
                        : SwInputStateMachine.LINUX_KEY_SW_FALLING;
                mSwInputHandler.onNativeKey(code, KEY_ACTION_DOWN);
            }
            return;
        }

        // exact token match because contains() would let KEY_F1 eat KEY_F10
        String keyToken = keyTokenOf(normalized);

        // KEY_1 is the level coded sw terminal where down closes the contact and up opens it
        if ("KEY_1".equals(keyToken)) {
            if (mSwInputHandler != null) {
                mSwInputHandler.onNativeKey(SwInputStateMachine.LINUX_KEY_SW_LEVEL, isDown ? 1 : 0);
            }
            return;
        }

        // capacitive and power buttons share the node
        if (keyToken != null) {
            int buttonCode = ButtonHandler.linuxCodeForKeyName(keyToken);
            if (buttonCode >= 0) {
                if (mButtonHandler != null) mButtonHandler.onNativeKey(buttonCode, isDown ? 1 : 0);
                return;
            }
        }

        // proximity only needs the press edge since the paired up line would just toggle it back
        if (!isDown) return;

        if (normalized.contains(PROXIMITY_KEY_NEAR)) {
            onGpioProximityEvent(true);
        } else if (normalized.contains(PROXIMITY_KEY_FAR)) {
            onGpioProximityEvent(false);
        } else {
            Log.i(TAG, "Unhandled gpio key line on proximity event path: " + line.trim());
        }
    }

    // the KEY_ token of one getevent -l line or null when there is none
    private static String keyTokenOf(String normalizedLine) {
        for (String token : normalizedLine.trim().split("\\s+")) {
            if (token.startsWith("KEY_")) return token;
        }
        return null;
    }

    // gpio_keys is the wide range proximity source so once it actually fires it becomes primary
    // and the short range sensormanager sensor stops competing
    // confirming on the first event keeps models whose gpio never emits these keys on sensormanager
    private synchronized void onGpioProximityEvent(boolean near) {
        if (!gpioProximityConfirmed) {
            gpioProximityConfirmed = true;
            maxProximitySensorValue = 1.0f; // binary near and far scale
            proximitySensorAvailable = true;
            suppressSensorManagerProximity();
            Log.i(TAG, "gpio_keys proximity confirmed, using as primary; SensorManager proximity suppressed");
        }
        publishProximity(near ? 0f : maxProximitySensorValue);
    }

    private void suppressSensorManagerProximity() {
        sensorManagerProximitySuppressed = true;
        if (proximitySensor == null) return;
        try {
            // only proximity since the light sensor shares this listener
            sensorManager.unregisterListener(this, proximitySensor);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister SensorManager proximity sensor", e);
        }
    }

    private synchronized void publishProximity(float value) {
        if (Float.compare(lastPublishedProximity, value) == 0) return;

        lastMeasuredDistance = value;
        lastPublishedProximity = value;

        Intent intent = new Intent(INTENT_PROXIMITY_UPDATED);
        intent.putExtra(INTENT_PROXIMITY_KEY, value);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public void onDestroy() {
        destroyed = true;
        sensorManager.unregisterListener(this);
        luxHandler.removeCallbacks(trailingLux);
        if (mInputMonitor != null) {
            mInputMonitor.stop();
            mInputMonitor = null;
        }
        Process process = proximityFallbackProcess;
        if (process != null) {
            process.destroy();
            proximityFallbackProcess = null;
        }
        if (proximityFallbackExecutor != null) {
            proximityFallbackExecutor.shutdownNow();
            proximityFallbackExecutor = null;
        }
    }
}
