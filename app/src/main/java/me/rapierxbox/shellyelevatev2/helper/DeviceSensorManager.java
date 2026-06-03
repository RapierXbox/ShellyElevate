package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_LIGHT_KEY;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_LIGHT_UPDATED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_KEY;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_UPDATED;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.rapierxbox.shellyelevatev2.DeviceModel;

public class DeviceSensorManager implements SensorEventListener {
    private static final String TAG = "DeviceSensorManager";
    private static final String PROXIMITY_KEY_NEAR = "KEY_F5";
    private static final String PROXIMITY_KEY_FAR = "KEY_F6";

    private static final long MIN_LUX_EVENT_INTERVAL_MS = 1000L;
    private static final float LUX_RELATIVE_THRESHOLD = 0.15f;

    private float lastMeasuredLux = 0.0f;
    private float lastPublishedLux = -1f;
    private long lastLuxBroadcastAtMs = 0L;

    private float lastMeasuredDistance = 1.0f;
    private float lastPublishedProximity = -1f;

    private final Context context;
    private final boolean lightSensorAvailable;
    private volatile boolean proximitySensorAvailable;
    private volatile boolean usingGpioKeysProximity = false;

    private float maxProximitySensorValue = 1.0f;
    private float fallbackProximityMaxRange = -1f;
    private Sensor proximitySensor;
    private volatile boolean gpioProximityConfirmed = false;
    private volatile boolean sensorManagerProximitySuppressed = false;
    private final String[] inputEventPaths;
    private InputMonitor mInputMonitor;
    private ExecutorService proximityFallbackExecutor;
    private volatile Process proximityFallbackProcess;

    public DeviceSensorManager(Context ctx) {
        context = ctx;
        SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        List<Sensor> deviceSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor sensor : deviceSensors) {
            Log.d(TAG, sensor.getName());
        }

        Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        lightSensorAvailable = lightSensor != null;
        if (lightSensorAvailable) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        inputEventPaths = DeviceModel.getReportedDevice().getInputEventPaths();

        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximitySensor != null) {
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            fallbackProximityMaxRange = proximitySensor.getMaximumRange();
            Log.i(TAG, "SensorManager proximity sensor registered (max range " + fallbackProximityMaxRange + ")");
        }

        // Try the native input monitor, then a getevent fallback. SensorManager
        // proximity stays registered as a backup since not every model's gpio_keys
        // actually emits KEY_F5/KEY_F6.
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

    /**
     * Resets the published-proximity tracking so the next reading is always
     * broadcast, even if the sensor value has not changed.  Call this whenever
     * the app re-enters a state where proximity needs to be re-evaluated (e.g.
     * just after the screensaver starts) so that a user who is already in range
     * can wake the screen without first moving away and back.
     */
    public synchronized void resetProximityState() {
        lastPublishedProximity = -1f;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null) return;

        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            lastMeasuredLux = event.values[0];
            boolean shouldPublish;

            if (lastPublishedLux < 0f) {
                shouldPublish = true;
            } else {
                float diff = Math.abs(lastMeasuredLux - lastPublishedLux);
                float change = diff / Math.max(1f, lastPublishedLux);
                shouldPublish = change >= LUX_RELATIVE_THRESHOLD;
            }

            long now = SystemClock.elapsedRealtime();
            boolean intervalOk = now - lastLuxBroadcastAtMs >= MIN_LUX_EVENT_INTERVAL_MS;

            if (shouldPublish && mMQTTServer != null && mMQTTServer.shouldSend()) {
                mMQTTServer.publishLux(lastMeasuredLux);
                lastPublishedLux = lastMeasuredLux;
            }

            if (intervalOk) {
                Intent intent = new Intent(INTENT_LIGHT_UPDATED);
                intent.putExtra(INTENT_LIGHT_KEY, lastMeasuredLux);
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
                lastLuxBroadcastAtMs = now;
            }
        } else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            if (sensorManagerProximitySuppressed) return;
            lastMeasuredDistance = event.values[0];
            publishProximity(lastMeasuredDistance);
        }
    }

    private boolean startNativeInputMonitor() {
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (String p : inputEventPaths) {
            if (new File(p).exists()) paths.add(p);
        }
        if (paths.isEmpty()) return false;
        try {
            mInputMonitor = new InputMonitor();
            mInputMonitor.start(this::handleNativeKeyEvent, paths);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Native input monitor failed: " + t.getMessage());
            mInputMonitor = null;
            return false;
        }
    }

    private void handleNativeKeyEvent(int keyCode, int action, int repeatCount) {
        // 63 = key_f5 (near), 64 = key_f6 (far)
        if (action == 1) { // down
            if (keyCode == 63) onGpioProximityEvent(true);
            else if (keyCode == 64) onGpioProximityEvent(false);
            else Log.i(TAG, "Unhandled input key code on monitored event path: " + keyCode);
        }
    }

    private boolean startProximityKeyFallback() {
        if (inputEventPaths.length == 0) return false;
        // `getevent` only takes one node; use the first that exists.
        String firstPath = null;
        for (String p : inputEventPaths) {
            if (new File(p).exists()) { firstPath = p; break; }
        }
        if (firstPath == null) return false;
        final String eventDevice = firstPath;
        try {
            proximityFallbackExecutor = Executors.newSingleThreadExecutor();
            proximityFallbackExecutor.execute(() -> runProximityKeyReader(eventDevice));
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

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                    handleProximityKeyLine(line);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Proximity key reader failed", e);
        } finally {
            if (process != null) {
                process.destroy();
            }
            proximityFallbackProcess = null;
            // Reader exited; clear the flag. SensorManager proximity, if registered,
            // keeps publishing on its own.
            if (usingGpioKeysProximity) {
                usingGpioKeysProximity = false;
                applyProximityFallback();
            }
        }
    }

    private void applyProximityFallback() {
        if (fallbackProximityMaxRange >= 0f) {
            // gpio reader is gone; revive the sensormanager proximity sensor if suppressed
            if (sensorManagerProximitySuppressed && proximitySensor != null) {
                try {
                    ((SensorManager) context.getSystemService(Context.SENSOR_SERVICE))
                            .registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to re-register SensorManager proximity sensor", e);
                }
            }
            gpioProximityConfirmed = false;
            sensorManagerProximitySuppressed = false;
            maxProximitySensorValue = fallbackProximityMaxRange;
            proximitySensorAvailable = true;
            Log.i(TAG, "Using SensorManager proximity sensor with max range " + maxProximitySensorValue);
        } else {
            proximitySensorAvailable = false;
            Log.w(TAG, "Proximity sensor unavailable (no gpio_keys or SensorManager sensor)");
        }
    }

    // Parses one line of `getevent -l` output, e.g.
    //   "/dev/input/event3: EV_KEY KEY_F5 DOWN"
    // We only care about the press edge; UP events come paired and would just
    // toggle the value back.
    private void handleProximityKeyLine(String line) {
        String normalized = line.toUpperCase(Locale.US);
        if (!normalized.contains(" DOWN")) {
            return;
        }

        if (normalized.contains(PROXIMITY_KEY_NEAR)) {
            onGpioProximityEvent(true);
        } else if (normalized.contains(PROXIMITY_KEY_FAR)) {
            onGpioProximityEvent(false);
        } else {
            Log.i(TAG, "Unhandled gpio key line on proximity event path: " + line.trim());
        }
    }

    // gpio_keys is the wide-range proximity source. once it actually fires, treat it
    // as primary and stop the short-range sensormanager sensor from competing.
    // confirming on the first event leaves models whose gpio never emits these keys
    // on the sensormanager fallback.
    private synchronized void onGpioProximityEvent(boolean near) {
        if (!gpioProximityConfirmed) {
            gpioProximityConfirmed = true;
            maxProximitySensorValue = 1.0f; // binary near/far scale
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
            // unregister only proximity; the light sensor shares this listener
            ((SensorManager) context.getSystemService(Context.SENSOR_SERVICE))
                    .unregisterListener(this, proximitySensor);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister SensorManager proximity sensor", e);
        }
    }

    private synchronized void publishProximity(float value) {
        if (Float.compare(lastPublishedProximity, value) == 0) {
            return;
        }

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
        ((SensorManager) context.getSystemService(Context.SENSOR_SERVICE)).unregisterListener(this);
        if (mInputMonitor != null) {
            mInputMonitor.stop();
            mInputMonitor = null;
        }
        if (proximityFallbackProcess != null) {
            proximityFallbackProcess.destroy();
            proximityFallbackProcess = null;
        }
        if (proximityFallbackExecutor != null) {
            proximityFallbackExecutor.shutdownNow();
            proximityFallbackExecutor = null;
        }
    }
}
