package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;
import static me.rapierxbox.shellyelevatev2.Constants.*;

import android.util.Log;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

import me.rapierxbox.shellyelevatev2.ShellyElevateApplication;


public class DeviceSensorManager implements SensorEventListener {
    private static final String TAG = "DeviceSensorManager" ;
    private float lastMeasuredLux = 0.0f;
    private boolean automaticBrightness = true;

    public float getLastMeasuredLux() {
        return lastMeasuredLux;
    }

    public void updateValues() {
        automaticBrightness = mSharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            lastMeasuredLux = event.values[0];
            Log.d(TAG, "Light sensor value: " + lastMeasuredLux);

            if (automaticBrightness) {
                ShellyElevateApplication.mDeviceHelper.setScreenBrightness(getScreenBrightnessFromLux(lastMeasuredLux));
            }
            if (mMQTTServer.shouldSend()) {
                mMQTTServer.publishLux(lastMeasuredLux);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    public static int getScreenBrightnessFromLux(float lux) {
        if (lux >= 500) return 255;
        if (lux <= 30) return 48;

        double slope = (255.0 - 48.0) / (500.0 - 30.0);
        return (int) (48 + slope * (lux - 30));
    }
}
