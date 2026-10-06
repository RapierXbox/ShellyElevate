package me.rapierxbox.shellyelevatev2;

import static fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT;
import static me.rapierxbox.shellyelevatev2.Constants.DISPLAY_MODULE_WEBVIEW;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED;
import static me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME;
import static me.rapierxbox.shellyelevatev2.Constants.SP_HTTP_SERVER_ENABLED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_MEDIA_ENABLED;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.StrictMode;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.webkit.WebViewFeature;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import me.rapierxbox.shellyelevatev2.api.ApiManager;
import me.rapierxbox.shellyelevatev2.api.BleChannel;
import me.rapierxbox.shellyelevatev2.api.MediaCommands;
import me.rapierxbox.shellyelevatev2.display.DisplayController;
import me.rapierxbox.shellyelevatev2.display.DisplayModuleRegistry;
import me.rapierxbox.shellyelevatev2.helper.AdbHelper;
import me.rapierxbox.shellyelevatev2.helper.ButtonHandler;
import me.rapierxbox.shellyelevatev2.helper.DeviceHelper;
import me.rapierxbox.shellyelevatev2.helper.DeviceSensorManager;
import me.rapierxbox.shellyelevatev2.helper.ForegroundActivities;
import me.rapierxbox.shellyelevatev2.helper.MediaHelper;
import me.rapierxbox.shellyelevatev2.helper.NightModeManager;
import me.rapierxbox.shellyelevatev2.helper.PowerOptimizer;
import me.rapierxbox.shellyelevatev2.helper.PrivAppInstaller;
import me.rapierxbox.shellyelevatev2.helper.ScreenManager;
import me.rapierxbox.shellyelevatev2.helper.SwInputHandler;
import me.rapierxbox.shellyelevatev2.helper.SwipeHelper;
import me.rapierxbox.shellyelevatev2.helper.touch.TouchGestureMonitor;
import me.rapierxbox.shellyelevatev2.mqtt.MQTTServer;
import me.rapierxbox.shellyelevatev2.settings.RemovedSettings;
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManager;
import me.rapierxbox.shellyelevatev2.settings.SettingsChangeTracker;
import me.rapierxbox.shellyelevatev2.stes.StesProtocolHandler;
import me.rapierxbox.shellyelevatev2.switcher.AppCatalog;
import me.rapierxbox.shellyelevatev2.voice.VoiceEngine;

public class ShellyElevateApplication extends Application {
    private static final String TAG = "ShellyElevateApplication";

    private static final int HTTP_RETRY_INITIAL_SECONDS = 5;
    private static final int HTTP_RETRY_MAX_SECONDS = 60;
    private static final long HTTP_WATCHDOG_INITIAL_DELAY_SECONDS = 15;
    private static final long HTTP_WATCHDOG_PERIOD_SECONDS = 30;

    // volatile because the watchdog thread reads it while the main thread swaps instances
    // never null after onCreate so the settings screen can call isAlive without a check
    public static volatile HttpServer mHttpServer;

    public static DeviceHelper mDeviceHelper;
    public static SwInputHandler mSwInputHandler;
    public static ButtonHandler mButtonHandler;
    public static DeviceSensorManager mDeviceSensorManager;
    public static SwipeHelper mSwipeHelper;
    public static ShellyElevateJavascriptInterface mShellyElevateJavascriptInterface;
    public static MQTTServer mMQTTServer;
    // volatile since the settings receiver swaps it while http and mqtt threads read it
    public static volatile MediaHelper mMediaHelper;
    public static ScreenSaverManager mScreenSaverManager;
    public static ScreenManager mScreenManager;
    public static NightModeManager mNightModeManager;
    public static VoiceEngine mVoiceEngine;
    public static PowerOptimizer mPowerOptimizer;

    // application context only so holding it statically does not leak an activity
    public static Context mApplicationContext;
    public static SharedPreferences mSharedPreferences;

    private static long applicationStartTime;

    private ScheduledExecutorService httpWatchdog;
    private ScheduledFuture<?> httpWatchdogFuture;
    private ScheduledFuture<?> httpRetryFuture;
    private int retryDelaySeconds = HTTP_RETRY_INITIAL_SECONDS;
    private BroadcastReceiver settingsReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));

        if (BuildConfig.DEBUG) enableStrictMode();

        applicationStartTime = System.currentTimeMillis();

        // bootstrap reads prefs and sysfs on the main thread so relax strict mode until it is done
        StrictMode.ThreadPolicy prevPolicy = StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder(prevPolicy)
                .permitDiskReads()
                .permitDiskWrites()
                .build());
        try {
            initSingletons();
        } finally {
            StrictMode.setThreadPolicy(prevPolicy);
        }

        settingsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                applyHttpServerSetting();
                applyMediaSetting();
                DisplayController.onSettingsChanged(context);
            }
        };
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(settingsReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));

        runFirstRunPrivilegeSetup();

        Log.i(TAG, "Application started");
    }

    // surfaces main thread stalls that could turn into anrs
    private static void enableStrictMode() {
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build());
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build());
    }

    // order matters since later managers reach earlier ones through the static fields
    private void initSingletons() {
        mApplicationContext = getApplicationContext();
        mSharedPreferences = getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE);
        // reports settings changes from every writer to the v1 api
        SettingsChangeTracker.start(mSharedPreferences);
        warmUpWebViewProvider();

        DeviceModel deviceModel = DeviceModel.getReportedDevice();
        Log.i(TAG, "Device: " + deviceModel.sku);

        mDeviceHelper = new DeviceHelper();
        // built before DeviceSensorManager so native input events never hit a null handler
        mSwInputHandler = new SwInputHandler();
        mButtonHandler = new ButtonHandler();
        StesProtocolHandler.init();
        mScreenSaverManager = new ScreenSaverManager(this);
        mScreenManager = new ScreenManager(this);
        mNightModeManager = new NightModeManager(this);
        registerActivityLifecycleCallbacks(mNightModeManager);
        registerActivityLifecycleCallbacks(ForegroundActivities.INSTANCE);
        mDeviceSensorManager = new DeviceSensorManager(this);
        mSwipeHelper = new SwipeHelper();
        mShellyElevateJavascriptInterface = new ShellyElevateJavascriptInterface();
        DisplayController.init(this);
        // loads launchable apps in the background for pickers and the app switcher
        AppCatalog.init(this);
        // reads the touchscreen so swipes also work while another app is in front
        TouchGestureMonitor.start(this);

        if (mSharedPreferences.getBoolean(SP_MEDIA_ENABLED, false)) {
            mMediaHelper = new MediaHelper();
        }
        MediaCommands.register();

        mMQTTServer = new MQTTServer();
        mVoiceEngine = new VoiceEngine();
        BleChannel.start(this);
        mPowerOptimizer = new PowerOptimizer(this);

        // protocol v1 for the home assistant integration. idle until a controller pairs
        ApiManager.start(this);
        // keys of the removed esphome proxy and token satellite
        RemovedSettings.clear(mSharedPreferences);

        mHttpServer = new HttpServer();
        httpWatchdog = Executors.newSingleThreadScheduledExecutor();
        if (isHttpServerEnabled()) {
            tryStartHttpServer();
            scheduleHttpWatchdog();
        }

        mScreenManager.setScreenOn(true);
        mScreenSaverManager.stopScreenSaver();
    }

    // loads the webview package and native library in parallel with the rest of startup
    // the feature query loads the provider without starting chromium so it is safe off the main thread
    private void warmUpWebViewProvider() {
        if (!DISPLAY_MODULE_WEBVIEW.equals(DisplayModuleRegistry.activeId(mSharedPreferences))) return;
        new Thread(() -> {
            try {
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
            } catch (Throwable t) {
                Log.w(TAG, "WebView warmup failed", t);
            }
        }, "WebViewWarmup").start();
    }

    private void applyHttpServerSetting() {
        if (isHttpServerEnabled()) {
            tryStartHttpServer();
            scheduleHttpWatchdog();
        } else {
            cancelHttpWatchdog();
            stopHttpServer();
        }
    }

    // media can be toggled at runtime without a reboot
    private void applyMediaSetting() {
        boolean mediaEnabled = mSharedPreferences.getBoolean(SP_MEDIA_ENABLED, false);
        if (mediaEnabled && mMediaHelper == null) {
            mMediaHelper = new MediaHelper();
        } else if (!mediaEnabled && mMediaHelper != null) {
            mMediaHelper.onDestroy();
            mMediaHelper = null;
        }
    }

    @SuppressWarnings("deprecation")

    // grants the manual adb perms off the main thread
    // promotion to a priv app is left to tools/install-privapp since the app user cannot write /system
    private void runFirstRunPrivilegeSetup() {
        // a plain thread so no idle executor thread lingers for the process lifetime
        new Thread(() -> {
            try {
                PrivAppInstaller.autoGrantPermissions(this);
                // the adb tcp port property resets on reboot so restore the saved state
                AdbHelper.applyFromPrefs();
            } catch (Throwable t) {
                Log.e(TAG, "First run privilege setup failed", t);
            }
        }, "PrivilegeSetup").start();
    }

    private static boolean isHttpServerEnabled() {
        return mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true);
    }

    private synchronized void scheduleHttpWatchdog() {
        if (httpWatchdog == null || httpWatchdog.isShutdown()) {
            httpWatchdog = Executors.newSingleThreadScheduledExecutor();
        }
        if (httpWatchdogFuture != null && !httpWatchdogFuture.isDone()) return;
        httpWatchdogFuture = httpWatchdog.scheduleWithFixedDelay(() -> {
            HttpServer server = mHttpServer;
            if (server == null || !server.isAlive()) {
                Log.w(TAG, "HTTP server not alive. Restarting...");
                tryStartHttpServer();
            }
        }, HTTP_WATCHDOG_INITIAL_DELAY_SECONDS, HTTP_WATCHDOG_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void cancelHttpWatchdog() {
        if (httpWatchdogFuture != null) {
            httpWatchdogFuture.cancel(false);
            httpWatchdogFuture = null;
        }
        // a pending backoff retry would otherwise restart a disabled server
        cancelHttpRetry();
    }

    private synchronized void cancelHttpRetry() {
        if (httpRetryFuture != null) {
            httpRetryFuture.cancel(false);
            httpRetryFuture = null;
        }
    }

    private synchronized void stopHttpServer() {
        HttpServer server = mHttpServer;
        if (server != null && server.isAlive()) server.stop();
    }

    // synchronized because the settings receiver and the watchdog executor race here
    private synchronized void tryStartHttpServer() {
        if (!isHttpServerEnabled()) return;
        startHttpServer();
    }

    // manual start from the settings screen which may run before the enabled pref is saved
    public synchronized boolean startHttpServerNow() {
        startHttpServer();
        HttpServer server = mHttpServer;
        return server != null && server.isAlive();
    }

    private synchronized void startHttpServer() {
        try {
            if (mHttpServer == null) {
                mHttpServer = new HttpServer();
            } else if (mHttpServer.isAlive()) {
                return;
            } else {
                // nanohttpd does not always release the listen socket on stop so use a fresh instance
                releaseQuietly(mHttpServer);
                mHttpServer = new HttpServer();
            }

            mHttpServer.start(SOCKET_READ_TIMEOUT, false);
            Log.i(TAG, "HTTP server started on port 8080");

            retryDelaySeconds = HTTP_RETRY_INITIAL_SECONDS;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server. Retrying in " + retryDelaySeconds + "s...", e);

            releaseQuietly(mHttpServer);
            // keep an unstarted instance so callers can still query isAlive
            mHttpServer = new HttpServer();

            scheduleHttpRetry();
        }
    }

    private synchronized void scheduleHttpRetry() {
        int delay = retryDelaySeconds;
        retryDelaySeconds = Math.min(retryDelaySeconds * 2, HTTP_RETRY_MAX_SECONDS);

        // the watchdog can fail a start while a retry is pending so keep only one queued
        cancelHttpRetry();
        try {
            httpRetryFuture = httpWatchdog.schedule(this::tryStartHttpServer, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "HTTP retry not scheduled since the watchdog is shut down");
        }
    }

    private static void releaseQuietly(HttpServer server) {
        if (server == null) return;
        try {
            server.stop();
            server.closeAllConnections();
        } catch (Throwable ignored) {
            // best effort since the instance is discarded anyway
        }
    }

    public static long getApplicationStartTime() {
        return applicationStartTime;
    }

    @Override
    public void onTerminate() {
        if (settingsReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(settingsReceiver);
            settingsReceiver = null;
        }
        SettingsChangeTracker.stop();

        synchronized (this) {
            cancelHttpWatchdog();
            if (httpWatchdog != null && !httpWatchdog.isShutdown()) {
                httpWatchdog.shutdownNow();
            }
        }
        if (mHttpServer != null) mHttpServer.onDestroy();
        ApiManager.stop();

        if (mDeviceSensorManager != null) mDeviceSensorManager.onDestroy();
        if (mSwInputHandler != null) mSwInputHandler.onDestroy();
        if (mButtonHandler != null) mButtonHandler.onDestroy();

        if (mScreenSaverManager != null) {
            mScreenSaverManager.stopScreenSaver();
            mScreenSaverManager.onDestroy();
        }
        if (mScreenManager != null) {
            mScreenManager.setScreenOn(true);
            mScreenManager.onDestroy();
        }
        if (mNightModeManager != null) {
            unregisterActivityLifecycleCallbacks(mNightModeManager);
            mNightModeManager.onDestroy();
        }

        if (mPowerOptimizer != null) mPowerOptimizer.onDestroy();

        if (mMQTTServer != null) mMQTTServer.onDestroy();
        StesProtocolHandler.close();
        if (mVoiceEngine != null) mVoiceEngine.onDestroy();
        BleChannel.stop();
        if (mMediaHelper != null) mMediaHelper.onDestroy();

        Log.i(TAG, "Application terminated");

        super.onTerminate();
    }
}
