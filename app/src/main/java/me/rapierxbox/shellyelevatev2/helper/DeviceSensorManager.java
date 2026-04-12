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
    // Stores the SensorManager proximity max range; -1 means no hardware sensor was registered.
    private float fallbackProximityMaxRange = -1f;
    private final String proximityEventDevice;
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

        // Resolve the gpio_keys event device path for this hardware model (null = use SensorManager)
        proximityEventDevice = DeviceModel.getReportedDevice().getGpioProximityEventPath();

        // Always register the SensorManager proximity sensor when present so it can serve as a
        // live fallback if the gpio_keys reader fails or terminates unexpectedly.
        Sensor proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximitySensor != null) {
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            fallbackProximityMaxRange = proximitySensor.getMaximumRange();
            Log.i(TAG, "SensorManager proximity sensor registered (max range " + fallbackProximityMaxRange + ")");
        }

        // Prefer gpio_keys proximity when the device supports it; onSensorChanged will ignore
        // SensorManager proximity events while usingGpioKeysProximity is true.
        usingGpioKeysProximity = startProximityKeyFallback();
        if (usingGpioKeysProximity) {
            maxProximitySensorValue = 1f;
            Log.i(TAG, "Using gpio_keys proximity from " + proximityEventDevice);
            proximitySensorAvailable = true;
        } else {
            applyProximityFallback();
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
        } else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY && !usingGpioKeysProximity) {
            // Handle proximity from SensorManager only if not using gpio_keys
            lastMeasuredDistance = event.values[0];
            publishProximity(lastMeasuredDistance);
        }
    }

    private boolean startProximityKeyFallback() {
        if (proximityEventDevice == null) {
            return false;
        }
        File eventDevice = new File(proximityEventDevice);
        if (!eventDevice.exists() || !eventDevice.canRead()) {
            return false;
        }

        try {
            proximityFallbackExecutor = Executors.newSingleThreadExecutor();
            proximityFallbackExecutor.execute(this::runProximityKeyReader);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start proximity key reader", t);
            return false;
        }
    }

    private void runProximityKeyReader() {
        Process process = null;
        try {
            process = new ProcessBuilder("getevent", "-l", proximityEventDevice)
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
            // Clear gpio flags regardless of how the reader terminated (IOException, EOF, or
            // non-zero exit). If a SensorManager proximity sensor was registered earlier it will
            // automatically take over because onSensorChanged gates on !usingGpioKeysProximity.
            if (usingGpioKeysProximity) {
                usingGpioKeysProximity = false;
                applyProximityFallback();
            }
        }
    }

    private void applyProximityFallback() {
        if (fallbackProximityMaxRange >= 0f) {
            maxProximitySensorValue = fallbackProximityMaxRange;
            proximitySensorAvailable = true;
            Log.i(TAG, "Using SensorManager proximity sensor with max range " + maxProximitySensorValue);
        } else {
            proximitySensorAvailable = false;
            Log.w(TAG, "Proximity sensor unavailable (no gpio_keys or SensorManager sensor)");
        }
    }

    private void handleProximityKeyLine(String line) {
        String normalized = line.toUpperCase(Locale.US);
        if (!normalized.contains(" DOWN")) {
            return;
        }

        if (normalized.contains(PROXIMITY_KEY_NEAR)) {
            publishProximity(0f);
        } else if (normalized.contains(PROXIMITY_KEY_FAR)) {
            publishProximity(1f);
        }
    }

    private void publishProximity(float value) {
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
        // Ignore
    }

    public void onDestroy() {
        ((SensorManager) context.getSystemService(Context.SENSOR_SERVICE)).unregisterListener(this);
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
