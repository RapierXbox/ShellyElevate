package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME;
import static me.rapierxbox.shellyelevatev2.Constants.SP_DEVICE;
import static me.rapierxbox.shellyelevatev2.Constants.SP_HTTP_SERVER_ENABLED;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.util.Log;

import java.io.IOException;

import me.rapierxbox.shellyelevatev2.helper.DeviceHelper;
import me.rapierxbox.shellyelevatev2.helper.DeviceSensorManager;
import me.rapierxbox.shellyelevatev2.helper.MediaHelper;
import me.rapierxbox.shellyelevatev2.helper.SwipeHelper;
import me.rapierxbox.shellyelevatev2.mqtt.MQTTServer;
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManager;

public class ShellyElevateApplication extends Application {
    public static HttpServer mHttpServer;

    public static DeviceHelper mDeviceHelper;
    public static DeviceSensorManager mDeviceSensorManager;
    public static SwipeHelper mSwipeHelper;
    public static ShellyElevateJavascriptInterface mShellyElevateJavascriptInterface;
    public static MQTTServer mMQTTServer;
    public static MediaHelper mMediaHelper;
    public static ScreenSaverManager mScreenSaverManager;

    public static Context mApplicationContext;
    public static SharedPreferences mSharedPreferences;

    private static long applicationStartTime;

    @Override
    public void onCreate() {
        super.onCreate();

        applicationStartTime = System.currentTimeMillis();

        mApplicationContext = getApplicationContext();
        mSharedPreferences = getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE);

        Log.i("ShellyElevateApplication", "Device: " + mSharedPreferences.getString(SP_DEVICE, "unconfigured"));

        mDeviceHelper = new DeviceHelper();
        mScreenSaverManager = new ScreenSaverManager();

        // Sensors Init
        mDeviceSensorManager = new DeviceSensorManager();
        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        sensorManager.registerListener(mDeviceSensorManager, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);

        DeviceModel device = DeviceModel.getDevice(mSharedPreferences);

        if (device.hasProximitySensor) {
            Sensor proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            Log.d("ShellyElevateApplication", "Default proximity sensor: " + proximitySensor);
            sensorManager.registerListener(mDeviceSensorManager, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            Log.i("ShellyElevateApplication", "Registered proximity sensor for DeviceSensorHelper");
        }


        mSwipeHelper = new SwipeHelper();
        mShellyElevateJavascriptInterface = new ShellyElevateJavascriptInterface();

        mMediaHelper = new MediaHelper();
        mHttpServer = new HttpServer();

        mMQTTServer = new MQTTServer();

        if (mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)) {
            try {
                mHttpServer.start();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        Log.i("ShellyElevateV2", "Application started");
    }

    public static long getApplicationStartTime() {
        return applicationStartTime;
    }

    @Override
    public void onTerminate() {
        mHttpServer.onDestroy();
        ((SensorManager) getSystemService(Context.SENSOR_SERVICE)).unregisterListener(mDeviceSensorManager);
        mScreenSaverManager.onDestroy();
        mMQTTServer.onDestroy();
        mMediaHelper.onDestroy();

        mDeviceHelper.setScreenOn(true);

        Log.i("ShellyElevateV2", "BYEEEEEEEEEEEEEEEEEEEE :)");

        super.onTerminate();
    }
}
