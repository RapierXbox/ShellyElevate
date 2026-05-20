package me.rapierxbox.shellyelevatev2;

import static fi.iki.elonen.NanoHTTPD.*;
import static me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME;
import static me.rapierxbox.shellyelevatev2.Constants.SP_HTTP_SERVER_ENABLED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_SWITCH_ON_SWIPE;
import static me.rapierxbox.shellyelevatev2.Constants.SP_MEDIA_ENABLED;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.os.StrictMode;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import me.rapierxbox.shellyelevatev2.helper.DeviceHelper;
import me.rapierxbox.shellyelevatev2.stes.StesProtocolHandler;
import me.rapierxbox.shellyelevatev2.helper.DeviceSensorManager;
import me.rapierxbox.shellyelevatev2.helper.MediaHelper;
import me.rapierxbox.shellyelevatev2.helper.NightModeManager;
import me.rapierxbox.shellyelevatev2.helper.PowerOptimizer;
import me.rapierxbox.shellyelevatev2.helper.ScreenManager;
import me.rapierxbox.shellyelevatev2.helper.SwipeHelper;
import me.rapierxbox.shellyelevatev2.mqtt.MQTTServer;
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManager;
import me.rapierxbox.shellyelevatev2.bluetooth.BluetoothProxyManager;
import me.rapierxbox.shellyelevatev2.voice.VoiceAssistantManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;

public class ShellyElevateApplication extends Application {
    public static HttpServer mHttpServer;

    public static DeviceHelper mDeviceHelper;
    public static DeviceSensorManager mDeviceSensorManager;
    public static SwipeHelper mSwipeHelper;
    public static ShellyElevateJavascriptInterface mShellyElevateJavascriptInterface;
    public static MQTTServer mMQTTServer;
    public static MediaHelper mMediaHelper;
    public static ScreenSaverManager mScreenSaverManager;
    public static ScreenManager mScreenManager;
    public static NightModeManager mNightModeManager;
    public static VoiceAssistantManager mVoiceAssistantManager;
    public static BluetoothProxyManager mBluetoothProxyManager;
    public static PowerOptimizer mPowerOptimizer;

    public static Context mApplicationContext;
    public static SharedPreferences mSharedPreferences;

    private static long applicationStartTime;
    private ScheduledExecutorService httpWatchdog;
    private ScheduledFuture<?> httpWatchdogFuture;
    private int retryDelaySeconds = 5;
    private BroadcastReceiver httpSettingsReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));

        // Surface main-thread stalls that could trigger ANRs in debug builds.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            );
            StrictMode.setVmPolicy(
                new StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            );
        }

        applicationStartTime = System.currentTimeMillis();

        // Singleton bootstrap reads SharedPreferences and sysfs nodes from the main thread;
        // relax StrictMode for the duration of init so debug builds don't penalty-log every
        // legitimate startup read.
        StrictMode.ThreadPolicy prevPolicy = StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(
            new StrictMode.ThreadPolicy.Builder(prevPolicy)
                .permitDiskReads()
                .permitDiskWrites()
                .build()
        );
        try {
            mApplicationContext = getApplicationContext();
            mSharedPreferences = getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE);

            var deviceModel = DeviceModel.getReportedDevice();
            Log.i("ShellyElevateApplication", "Device: " + deviceModel.sku);

            mDeviceHelper = new DeviceHelper();
            StesProtocolHandler.init();
            mScreenSaverManager = new ScreenSaverManager(this);
            mScreenManager = new ScreenManager(this);
            mNightModeManager = new NightModeManager(this);
            registerActivityLifecycleCallbacks(mNightModeManager);
            mDeviceSensorManager = new DeviceSensorManager(this);
            mSwipeHelper = new SwipeHelper();
            mShellyElevateJavascriptInterface = new ShellyElevateJavascriptInterface();

            if (mSharedPreferences.getBoolean(SP_MEDIA_ENABLED, false)) {
                mMediaHelper = new MediaHelper();
            }

            mMQTTServer = new MQTTServer();
            mVoiceAssistantManager = new VoiceAssistantManager();
            mBluetoothProxyManager = new BluetoothProxyManager();
            mPowerOptimizer = new PowerOptimizer(this);

            mHttpServer = new HttpServer();
            httpWatchdog = Executors.newSingleThreadScheduledExecutor();
            if (mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)) {
                tryStartHttpServer();
                scheduleHttpWatchdog();
            }

            mScreenManager.setScreenOn(true);
            mScreenSaverManager.stopScreenSaver();
        } finally {
            StrictMode.setThreadPolicy(prevPolicy);
        }

        httpSettingsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean enabled = mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true);
                if (!enabled) {
                    if (mHttpServer != null && mHttpServer.isAlive()) mHttpServer.stop();
                    cancelHttpWatchdog();
                } else {
                    tryStartHttpServer();
                    scheduleHttpWatchdog();
                }
            }
        };
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(httpSettingsReceiver, new IntentFilter(Constants.INTENT_SETTINGS_CHANGED));

        Log.i("ShellyElevateV2", "Application started");
    }

    private void scheduleHttpWatchdog() {
        if (httpWatchdog == null || httpWatchdog.isShutdown()) {
            httpWatchdog = Executors.newSingleThreadScheduledExecutor();
        }
        if (httpWatchdogFuture != null && !httpWatchdogFuture.isCancelled() && !httpWatchdogFuture.isDone()) return;
        httpWatchdogFuture = httpWatchdog.scheduleWithFixedDelay(() -> {
            if (mHttpServer == null || !mHttpServer.isAlive()) {
                Log.w("ShellyElevateV2", "HTTP server not alive. Restarting...");
                tryStartHttpServer();
            }
        }, 15, 30, TimeUnit.SECONDS);
    }

    private void cancelHttpWatchdog() {
        if (httpWatchdogFuture != null) {
            httpWatchdogFuture.cancel(false);
            httpWatchdogFuture = null;
        }
    }

    private void tryStartHttpServer() {
        try {
            if (mHttpServer == null) {
                mHttpServer = new HttpServer();
            } else if (mHttpServer.isAlive()) {
                return;
            } else {
                try {
                    mHttpServer.stop();
                    mHttpServer.closeAllConnections();
                } catch (Throwable ignored) {}
                // NanoHTTPD doesn't always release the listen socket cleanly on stop();
                // recreate the instance to avoid binding errors on restart.
                mHttpServer = new HttpServer();
            }

            mHttpServer.start(SOCKET_READ_TIMEOUT, false);
            Log.i("ShellyElevateV2", "HTTP server started on port 8080");

            retryDelaySeconds = 5;
        } catch (IOException e) {
            Log.e("ShellyElevateV2", "Failed to start HTTP server. Retrying in " + retryDelaySeconds + "s...", e);

            try {
                if (mHttpServer != null) {
                    mHttpServer.stop();
                    mHttpServer.closeAllConnections();
                }
            } catch (Throwable ignored) {}
            mHttpServer = null;

            int delay = retryDelaySeconds;
            retryDelaySeconds = Math.min(retryDelaySeconds * 2, 60);

            httpWatchdog.schedule(this::tryStartHttpServer, delay, TimeUnit.SECONDS);
        }
    }

    public static long getApplicationStartTime() {
        return applicationStartTime;
    }

    @Override
    public void onTerminate() {
        mHttpServer.onDestroy();
        mDeviceSensorManager.onDestroy();

        mScreenSaverManager.stopScreenSaver();
        mScreenSaverManager.onDestroy();
        mScreenManager.setScreenOn(true);
        mScreenManager.onDestroy();
        if (mNightModeManager != null) {
            unregisterActivityLifecycleCallbacks(mNightModeManager);
            mNightModeManager.onDestroy();
        }

        if (mPowerOptimizer != null) mPowerOptimizer.onDestroy();

        mMQTTServer.onDestroy();
        StesProtocolHandler.close();
        if (mVoiceAssistantManager != null) mVoiceAssistantManager.onDestroy();
        if (mBluetoothProxyManager != null) mBluetoothProxyManager.onDestroy();
        if (mMediaHelper != null) mMediaHelper.onDestroy();

        if (httpWatchdog != null && !httpWatchdog.isShutdown()) {
            httpWatchdog.shutdownNow();
        }

        if (httpSettingsReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(httpSettingsReceiver);
        }

        Log.i("ShellyElevateV2", "BYEEEEEEEEEEEEEEEEEEEE :)");

        super.onTerminate();
    }
}
