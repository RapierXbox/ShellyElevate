package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_LIGHT_KEY;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_LIGHT_UPDATED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_KEY;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_UPDATED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_AUTOMATIC_BRIGHTNESS;
import static me.rapierxbox.shellyelevatev2.Constants.SP_MIN_BRIGHTNESS;
import static me.rapierxbox.shellyelevatev2.Constants.SP_WAKE_ON_PROXIMITY;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

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

import java.util.Arrays;
import java.util.List;

import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication;


public class DeviceSensorManager implements SensorEventListener {
    private static final String TAG = "DeviceSensorManager";
    private float lastMeasuredLux = 0.0f;
    private float lastPublishedLux = -1f; // initialize to invalid value
    private long lastLuxBroadcastAtMs = 0L;
    private static final long MIN_LUX_EVENT_INTERVAL_MS = 1000L; // reduced spam
    private static final float LUX_RELATIVE_THRESHOLD = 0.15f; // increased threshold to reduce broadcasts

    private float lastPublishedProximity = -1f;
    private long lastProximityBroadcastAtMs = 0L;
    private static final long MIN_PROX_EVENT_INTERVAL_MS = 500L; // doubled interval
    private static final float PROX_ABS_THRESHOLD = 0.2f; // increased threshold
    private long lastInvalidProximityLogAtMs = 0L;

    private final Context context;

    public DeviceSensorManager(Context ctx) {
        context = ctx;

        DeviceModel device = DeviceModel.getReportedDevice();
        SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        List<Sensor> deviceSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor sensor : deviceSensors) {
            Log.d(TAG, sensor.getName());
        }

        // light sensor
        Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);

        // proximity sensor
        Log.d(TAG, "Has proximity sensor: " + device.hasProximitySensor);
        if (device.hasProximitySensor) {
            Sensor proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (proximitySensor != null) {
                maxProximitySensorValue = proximitySensor.getMaximumRange();
                Log.d(TAG, "Default proximity sensor: " + proximitySensor + " - Max: " + maxProximitySensorValue);
                sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
    }

    public float getLastMeasuredLux() {
        return lastMeasuredLux;
    }

    private float lastMeasuredDistance = 0.0f;
    public float getLastMeasuredDistance() { return lastMeasuredDistance; }

    private float maxProximitySensorValue = 1.0f;
    public float getMaxProximitySensorValue() { return maxProximitySensorValue;}

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null) return;
        Intent intent;

        //Log.d(TAG, "Got an event from a sensor: " + event.sensor.getType() + " - " + Arrays.toString(event.values));

        switch (event.sensor.getType()) {
            case Sensor.TYPE_LIGHT:
                lastMeasuredLux = event.values[0];
                boolean shouldPublish = false;

                if (lastPublishedLux < 0) {
                    // First reading
                    shouldPublish = true;
                } else {
                    float diff = Math.abs(lastMeasuredLux - lastPublishedLux);
                    float change = diff / Math.max(1f, lastPublishedLux);
                    if (change >= LUX_RELATIVE_THRESHOLD) {
                        shouldPublish = true;
                    }
                }

                long now = SystemClock.elapsedRealtime();
                boolean intervalOk = now - lastLuxBroadcastAtMs >= MIN_LUX_EVENT_INTERVAL_MS;

                if (shouldPublish && mMQTTServer != null && mMQTTServer.shouldSend()) {
                    mMQTTServer.publishLux(lastMeasuredLux);
                    lastPublishedLux = lastMeasuredLux;
                }

                if (intervalOk) {
                    intent = new Intent(INTENT_LIGHT_UPDATED);
                    intent.putExtra(INTENT_LIGHT_KEY, lastMeasuredLux);
                    LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
                    lastLuxBroadcastAtMs = now;
                }
                break;

            case Sensor.TYPE_PROXIMITY:
                float rawProximity = event.values[0];
                lastMeasuredDistance = normalizeProximityValue(rawProximity);
                boolean first = lastPublishedProximity < 0f;
                float delta = Math.abs(lastMeasuredDistance - (first ? lastMeasuredDistance : lastPublishedProximity));
                long nowProx = SystemClock.elapsedRealtime();
                boolean intervalOkProx = nowProx - lastProximityBroadcastAtMs >= MIN_PROX_EVENT_INTERVAL_MS;

                if (first || (delta >= PROX_ABS_THRESHOLD && intervalOkProx)) {
                    intent = new Intent(INTENT_PROXIMITY_UPDATED);
                    intent.putExtra(INTENT_PROXIMITY_KEY, lastMeasuredDistance);
                    LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
                    lastProximityBroadcastAtMs = nowProx;
                    lastPublishedProximity = lastMeasuredDistance;
                }
                break;
        }
    }

    private float normalizeProximityValue(float rawProximity) {
        if (rawProximity < 0f) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastInvalidProximityLogAtMs > 5000L) {
                Log.w(TAG, "Invalid proximity value from HAL: " + rawProximity + ", treating as FAR");
                lastInvalidProximityLogAtMs = now;
            }
            return maxProximitySensorValue;
        }

        // Some Smatek/Shelly firmwares expose binary proximity (0/1)
        if (maxProximitySensorValue <= 1.5f) {
            return rawProximity <= 0.5f ? 0f : 1f;
        }

        // Distance-mode sensor: clamp noisy out-of-range values
        if (rawProximity > maxProximitySensorValue) {
            return maxProximitySensorValue;
        }

        return rawProximity;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Ignore
    }

    public void onDestroy() {
        ((SensorManager) context.getSystemService(Context.SENSOR_SERVICE)).unregisterListener(this);
    }
}
