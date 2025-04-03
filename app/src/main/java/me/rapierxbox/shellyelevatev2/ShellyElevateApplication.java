package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_REFRESH;
import static me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME;
import static me.rapierxbox.shellyelevatev2.Constants.SP_HTTP_SERVER_ENABLED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_LITE_MODE;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;

import me.rapierxbox.shellyelevatev2.helper.DeviceHelper;
import me.rapierxbox.shellyelevatev2.helper.DeviceSensorManager;
import me.rapierxbox.shellyelevatev2.helper.SwipeHelper;
import me.rapierxbox.shellyelevatev2.mqtt.MQTTServer;
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManager;

public class ShellyElevateApplication extends Application {
    public static HttpServer mHttpServer;

    public static DeviceHelper mDeviceHelper;
    public static ScreenSaverManager mScreenSaverManager;
    public static DeviceSensorManager mDeviceSensorManager;
    public static SwipeHelper mSwipeHelper;
    public static ShellyElevateJavascriptInterface mShellyElevateJavascriptInterface;
    public static MQTTServer mMQTTServer;

    public static SensorManager mSensorManager;
    public static Sensor mLightSensor;

    public static Context mApplicationContext;
    public static SharedPreferences mSharedPreferences;

    private static long applicationStartTime;

    @Override
    public void onCreate() {
        super.onCreate();

        applicationStartTime = System.currentTimeMillis();

        mApplicationContext = getApplicationContext();
        mSharedPreferences = getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE);

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        mHttpServer = new HttpServer();

        mDeviceHelper = new DeviceHelper();
        mScreenSaverManager = new ScreenSaverManager();
        mDeviceSensorManager = new DeviceSensorManager();
        mSwipeHelper = new SwipeHelper();
        mShellyElevateJavascriptInterface = new ShellyElevateJavascriptInterface();
        mMQTTServer = new MQTTServer();

        updateSPValues();

        if (mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)) {
            try {
                mHttpServer.start();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        mSensorManager.registerListener(mDeviceSensorManager, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL);

        Log.i("ShellyElevateV2", "Application started");
    }

    public static long getApplicationStartTime() {
        return applicationStartTime;
    }

    public static void updateSPValues() {
        mScreenSaverManager.updateValues();
        mDeviceSensorManager.updateValues();
        mSwipeHelper.updateValues();
        mShellyElevateJavascriptInterface.updateValues();
        mDeviceHelper.updateValues();
        mMQTTServer.updateValues();

        Intent intent = new Intent(INTENT_WEBVIEW_REFRESH);
        LocalBroadcastManager.getInstance(ShellyElevateApplication.mApplicationContext).sendBroadcast(intent);
    }

    @Override
    public void onTerminate() {
        mHttpServer.onDestroy();
        mSensorManager.unregisterListener(mDeviceSensorManager);
        mScreenSaverManager.onDestroy();
        mMQTTServer.onDestroy();

        mDeviceHelper.setScreenOn(true);

        Log.i("ShellyElevateV2", "BYEEEEEEEEEEEEEEEEEEEE :)");

        super.onTerminate();
    }
}
