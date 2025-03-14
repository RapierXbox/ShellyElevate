package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_REFRESH;
import static me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME;
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
import me.rapierxbox.shellyelevatev2.helper.MediaHelper;
import me.rapierxbox.shellyelevatev2.helper.SwipeHelper;
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManager;

public class ShellyElevateApplication extends Application {
    public static HttpServer mHttpServer;
    public static SettingsParser mSettingsParser;
    public static MediaHelper mMediaHelper;
    public static DeviceHelper mDeviceHelper;
    public static ScreenSaverManager mScreenSaverManager;
    public static DeviceSensorManager mDeviceSensorManager;
    public static SwipeHelper mSwipeHelper;

    public static SensorManager mSensorManager;
    public static Sensor mLightSensor;

    public static Context mApplicationContext;
    public static SharedPreferences mSharedPreferences;

    @Override
    public void onCreate() {
        super.onCreate();

        mApplicationContext = getApplicationContext();
        mSharedPreferences = getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE);

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        mHttpServer = new HttpServer();
        mSettingsParser = new SettingsParser();
        mMediaHelper = new MediaHelper();
        mDeviceHelper = new DeviceHelper();
        mScreenSaverManager = new ScreenSaverManager();
        mDeviceSensorManager = new DeviceSensorManager();
        mSwipeHelper = new SwipeHelper();

        updateSPValues();

        try {
            mHttpServer.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        mSensorManager.registerListener(mDeviceSensorManager, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL);

        if (!mSharedPreferences.getBoolean(SP_LITE_MODE, false)) {
            Log.i("ShellyElevateV2", "Starting MainActivity");
            Intent activityIntent = new Intent(this, MainActivity.class);
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(activityIntent);
        }




        Log.i("ShellyElevateV2", "Application started");
    }

    public static void updateSPValues() {
        mScreenSaverManager.updateValues();
        mDeviceSensorManager.updateValues();
        mSwipeHelper.updateValues();

        Intent intent = new Intent(INTENT_WEBVIEW_REFRESH);
        LocalBroadcastManager.getInstance(ShellyElevateApplication.mApplicationContext).sendBroadcast(intent);
    }

    @Override
    public void onTerminate() {
        mHttpServer.onDestroy();
        mSensorManager.unregisterListener(mDeviceSensorManager);
        mScreenSaverManager.onDestroy();

        mDeviceHelper.setScreenOn(true);

        Log.i("ShellyElevateV2", "BYEEEEEEEEEEEEEEEEEEEE :)");

        super.onTerminate();
    }
}
