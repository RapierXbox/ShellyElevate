package me.rapierxbox.shellyelevatev2;

import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.util.Log;

public class DeviceSensorManager implements SensorEventListener {
    public static float lastMeasuredLux = 0.0f;
    private final SharedPreferences sharedPreferences;

    DeviceSensorManager(SharedPreferences sP) {
        super();
        sharedPreferences = sP;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT && sharedPreferences.getBoolean("automaticBrightness", true)) {
            lastMeasuredLux = event.values[0];
            DeviceHelper.setScreenBrightness(getScreenBrightnessFromLux(lastMeasuredLux));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    private int getScreenBrightnessFromLux(float lux) {
        if (lux >= 500) return 255;
        if (lux <= 30) return 48;

        double slope = (255.0 - 48.0) / (500.0 - 30.0);
        return (int) (48 + slope * (lux - 30));
    }
}
