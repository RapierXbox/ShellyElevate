package me.rapierxbox.shellyelevatev2;

import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

import me.rapierxbox.shellyelevatev2.helper.DeviceHelper;

public class DeviceSensorManager implements SensorEventListener {
    private static float lastMeasuredLux = 0.0f;
    private final SharedPreferences sharedPreferences;

    public static float getLastMeasuredLux() {
        return lastMeasuredLux;
    }

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

    public static int getScreenBrightnessFromLux(float lux) {
        if (lux >= 500) return 255;
        if (lux <= 30) return 48;

        double slope = (255.0 - 48.0) / (500.0 - 30.0);
        return (int) (48 + slope * (lux - 30));
    }
}
