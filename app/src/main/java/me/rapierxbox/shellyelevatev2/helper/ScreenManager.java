package me.rapierxbox.shellyelevatev2.helper;

import static android.content.Context.MODE_PRIVATE;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_LIGHT_KEY;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_LIGHT_UPDATED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STARTED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STOPPED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_OFF;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_ON;
import static me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME;
import static me.rapierxbox.shellyelevatev2.Constants.SP_AUTOMATIC_BRIGHTNESS;
import static me.rapierxbox.shellyelevatev2.Constants.SP_BRIGHTNESS;
import static me.rapierxbox.shellyelevatev2.Constants.SP_MIN_BRIGHTNESS;
import static me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_MIN_BRIGHTNESS;
import static me.rapierxbox.shellyelevatev2.Constants.SP_TOUCH_TO_WAKE;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.screensavers.AODScreenSaver;
import me.rapierxbox.shellyelevatev2.screensavers.ScreenOffScreenSaver;

public class ScreenManager extends BroadcastReceiver {

    private static final String TAG = "ScreenManager";
    // Wait this long after the desired brightness changes before actually animating,
    // so a flickering lux sensor doesn't yo-yo the backlight.
    private static final long HYSTERESIS_DELAY_MS = 3000L;
    public static final long FADE_DURATION_MS = 1000L;
    private static final int MIN_BRIGHTNESS_STEP = 3;

    public static final int MIN_BRIGHTNESS_DEFAULT = 48;
    public static final int DEFAULT_BRIGHTNESS = 255;

    private float lastMeasuredLux = 0.0f;

    private long lastUpdateTime = 0L;
    private volatile int currentBrightness = -1;
    private volatile int targetBrightness = -1;
    private volatile boolean inScreenSaver = false;
    private volatile boolean screenOn = true;

    private final Handler fadeHandler = new Handler(Looper.getMainLooper());
    private final Runnable fadeRunnable = this::checkAndApplyBrightness;

    private final SharedPreferences prefs;
    private volatile boolean cachedAutomaticBrightness = true;
    private volatile int cachedFixedBrightness = DEFAULT_BRIGHTNESS;
    private volatile int cachedMinBrightness = MIN_BRIGHTNESS_DEFAULT;
    private volatile int cachedScreenSaverMinBrightness = MIN_BRIGHTNESS_DEFAULT;
    private volatile boolean cachedTouchToWake = true;

    private final Context context;
    private final BrightnessAnimator brightnessAnimator = new BrightnessAnimator();

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (sharedPreferences, key) -> {
                if (SP_AUTOMATIC_BRIGHTNESS.equals(key)) {
                    cachedAutomaticBrightness = sharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true);
                } else if (SP_BRIGHTNESS.equals(key)) {
                    cachedFixedBrightness = clamp(sharedPreferences.getInt(SP_BRIGHTNESS, DEFAULT_BRIGHTNESS), 0, 255);
                } else if (SP_MIN_BRIGHTNESS.equals(key)) {
                    cachedMinBrightness = clamp(sharedPreferences.getInt(SP_MIN_BRIGHTNESS, MIN_BRIGHTNESS_DEFAULT), 0, 255);
                } else if (SP_SCREEN_SAVER_MIN_BRIGHTNESS.equals(key)) {
                    cachedScreenSaverMinBrightness = clamp(sharedPreferences.getInt(SP_SCREEN_SAVER_MIN_BRIGHTNESS, MIN_BRIGHTNESS_DEFAULT), 0, 255);
                } else if (SP_TOUCH_TO_WAKE.equals(key)) {
                    cachedTouchToWake = sharedPreferences.getBoolean(SP_TOUCH_TO_WAKE, true);
                }
            };

    public ScreenManager(Context ctx) {
        this.context = ctx.getApplicationContext();
        prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE);
        loadPrefsToCache();
        Log.i(TAG, "ScreenManager initialized: cachedTouchToWake=" + cachedTouchToWake + ", cachedAutomaticBrightness=" + cachedAutomaticBrightness);

        prefs.registerOnSharedPreferenceChangeListener(prefsListener);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(INTENT_SCREEN_SAVER_STARTED);
        intentFilter.addAction(INTENT_SCREEN_SAVER_STOPPED);
        intentFilter.addAction(INTENT_TURN_SCREEN_ON);
        intentFilter.addAction(INTENT_TURN_SCREEN_OFF);
        intentFilter.addAction(INTENT_LIGHT_UPDATED);

        LocalBroadcastManager.getInstance(context).registerReceiver(this, intentFilter);

        // Force the screen on at startup so we don't boot at brightness 0; the
        // screensaver will dim later if it activates.
        setScreenOn(true);
        updateBrightness();
    }

    private void loadPrefsToCache() {
        cachedAutomaticBrightness = prefs.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true);
        cachedFixedBrightness = clamp(prefs.getInt(SP_BRIGHTNESS, DEFAULT_BRIGHTNESS), 0, 255);
        cachedMinBrightness = clamp(prefs.getInt(SP_MIN_BRIGHTNESS, MIN_BRIGHTNESS_DEFAULT), 0, 255);
        cachedScreenSaverMinBrightness = clamp(prefs.getInt(SP_SCREEN_SAVER_MIN_BRIGHTNESS, MIN_BRIGHTNESS_DEFAULT), 0, 255);
        cachedTouchToWake = prefs.getBoolean(SP_TOUCH_TO_WAKE, true);
    }

    public void onDestroy() {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(this);
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        fadeHandler.removeCallbacksAndMessages(null);
        brightnessAnimator.cancel();
    }

    public void setScreenOn(boolean on) {
        screenOn = on;
        if (!on) {
            currentBrightness = 0;
        }
        mDeviceHelper.setScreenOn(on);
        if (!on) {
            applyBrightness(0, "screen off");
        }
    }

    /** Forwarded from MainActivity's WebView touch listener. */
    public void onTouchEvent() {
        if (BuildConfig.DEBUG) Log.d(TAG, "onTouchEvent called: screenOn=" + screenOn + ", cachedTouchToWake=" + cachedTouchToWake + ", currentBrightness=" + currentBrightness + ", inScreenSaver=" + inScreenSaver);
        if (inScreenSaver) {
            Log.i(TAG, "Touch detected, exiting screensaver");
            mScreenSaverManager.stopScreenSaver();
            return;
        }

        if (!screenOn && cachedTouchToWake) {
            Log.i(TAG, "Touch detected, waking screen via touch-to-wake");
            setScreenOn(true);
            // Apply brightness immediately on wake; hysteresis is only for lux jitter.
            wakeScreen("touch-to-wake");
        }
    }

    /** True when a touch should wake the screen instead of being delivered to the WebView. */
    public boolean shouldConsumeTouchForWake() {
        return inScreenSaver || !screenOn || currentBrightness == 0;
    }

    private boolean automaticBrightness() {
        return cachedAutomaticBrightness;
    }

    private int fixedBrightness() {
        return cachedFixedBrightness;
    }

    @Override
    @MainThread
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case INTENT_SCREEN_SAVER_STARTED:
                updateScreenSaverState(true);
                break;
            case INTENT_SCREEN_SAVER_STOPPED:
                updateScreenSaverState(false);
                break;
            case INTENT_TURN_SCREEN_ON:
                // Clear screensaver state before waking: INTENT_TURN_SCREEN_ON can arrive
                // before INTENT_SCREEN_SAVER_STOPPED (race between the handler post and the
                // executor send in ScreenOffScreenSaver / ScreenSaverManager), so we must
                // clear inScreenSaver here to let computeDesiredBrightness return the real
                // target instead of 0.
                inScreenSaver = false;
                setScreenOn(true);
                wakeScreen("screen on");
                break;
            case INTENT_TURN_SCREEN_OFF:
                setScreenOn(false);
                targetBrightness = 0;
                currentBrightness = 0;
                break;
            case INTENT_LIGHT_UPDATED:
                float lux = intent.getFloatExtra(INTENT_LIGHT_KEY, 0.0f);
                if (Float.isNaN(lux) || lux < 0f) lux = 0f;
                lastMeasuredLux = lux;

                // TODO: debounce when the lux sensor is noisy.
                if (automaticBrightness())
                    updateBrightness();

                break;
        }
    }

    private synchronized void updateBrightness() {
        int desiredBrightness = computeDesiredBrightness();

        // aod bypasses MIN_BRIGHTNESS_STEP so small panel-min writes are not dropped
        if (inScreenSaver && isAODSaverActive()) {
            int panelMin = clamp(DeviceModel.getReportedDevice().panelMinBacklight, 0, 255);
            targetBrightness = panelMin;
            fadeHandler.removeCallbacks(fadeRunnable);
            brightnessAnimator.cancel();
            applyBrightness(panelMin, "aod");
            return;
        }

        if (!screenOn || (inScreenSaver && isScreenOffSaverActive())) {
            targetBrightness = 0;
            applyBrightness(0, "screen off or screen-off screensaver");
            fadeHandler.removeCallbacks(fadeRunnable);
            brightnessAnimator.cancel();
            return;
        }

        if (desiredBrightness != targetBrightness) {
            if (targetBrightness >= 0 && Math.abs(desiredBrightness - targetBrightness) < MIN_BRIGHTNESS_STEP) {
                return;
            }
            targetBrightness = desiredBrightness;
            lastUpdateTime = System.currentTimeMillis();
            fadeHandler.removeCallbacks(fadeRunnable);
            fadeHandler.postDelayed(fadeRunnable, HYSTERESIS_DELAY_MS);
            if (BuildConfig.DEBUG) Log.d(TAG, "Desired brightness: " + desiredBrightness + ", targetBrightness: " + targetBrightness + ", lastUpdateTime: " + lastUpdateTime + ", currentBrightness: " + currentBrightness);
        }
    }

    private int computeDesiredBrightness() {
        // aod pins to panel minimum, skip lux ramp
        if (inScreenSaver && isAODSaverActive()) {
            return clamp(DeviceModel.getReportedDevice().panelMinBacklight, 0, 255);
        }
        if (!screenOn || (inScreenSaver && isScreenOffSaverActive())) {
            return 0;
        }

        int desiredBrightness = automaticBrightness()
                ? getScreenBrightnessFromLux(lastMeasuredLux)
                : fixedBrightness();

        return clamp(desiredBrightness, 0, 255);
    }

    private synchronized void checkAndApplyBrightness() {
        // Skip the hysteresis delay when we're heading to 0 so the screen turns
        // off promptly.
        boolean force = currentBrightness != 0 && targetBrightness == 0;
        long now = System.currentTimeMillis();

        if (now - lastUpdateTime >= HYSTERESIS_DELAY_MS || force) {
            if (currentBrightness == -1) {
                applyBrightness(targetBrightness, "initial apply");
            } else if (currentBrightness != targetBrightness) {
                animateBrightnessTransition(currentBrightness, targetBrightness);
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "No brightness change needed.");
            }
        } else {
            fadeHandler.removeCallbacks(fadeRunnable);
            long delay = Math.max(0, HYSTERESIS_DELAY_MS - (now - lastUpdateTime));
            fadeHandler.postDelayed(fadeRunnable, delay);
        }
    }

    private void animateBrightnessTransition(int from, int to) {
        from = clamp(from, 0, 255);
        to = clamp(to, 0, 255);

        try {
            brightnessAnimator.animate(from, to, value -> applyBrightness(value, "animate"));
        } catch (Throwable t) {
            if (BuildConfig.DEBUG) Log.d(TAG, "BrightnessAnimator failed, applying immediate value. " + t.getMessage());
            applyBrightness(to, "animate fallback");
        }
    }

    // Linear ramp from cachedMinBrightness at 30 lux to 255 at 500 lux. Below/above
    // those breakpoints we clamp to the endpoints so the screen never goes fully
    // dark in a lit room and never gets stuck below max in bright sunlight.
    private int getScreenBrightnessFromLux(float lux) {
        if (Float.isNaN(lux) || lux < 0f) lux = 0f;

        int minBrightness = inScreenSaver ? cachedScreenSaverMinBrightness : cachedMinBrightness;
        minBrightness = clamp(minBrightness, 0, 255);

        if (lux >= 500f) return 255;
        if (lux <= 30f) return minBrightness;

        double slope = (255.0 - minBrightness) / (500.0 - 30.0);
        double computed = minBrightness + slope * (lux - 30.0);
        return clamp((int) Math.round(computed), 0, 255);
    }

    // Saver-active transitions intentionally bypass HYSTERESIS_DELAY_MS: dimming
    // on screen-off is asymmetric (we want fast off, smooth-faded on, no jitter
    // delay) so we drive brightness directly here instead of via updateBrightness.
    private synchronized void updateScreenSaverState(boolean newState) {
        this.inScreenSaver = newState;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "updateScreenSaverState newState=" + newState + ", screenOn=" + screenOn + ", currentBrightness=" + currentBrightness + ", targetBrightness=" + targetBrightness + ", lux=" + lastMeasuredLux);
        }
        if (newState) {
            if (isAODSaverActive()) {
                int panelMin = clamp(DeviceModel.getReportedDevice().panelMinBacklight, 0, 255);
                targetBrightness = panelMin;
                fadeHandler.removeCallbacks(fadeRunnable);
                brightnessAnimator.cancel();
                applyBrightness(panelMin, "aod start");
            } else {
                updateBrightness();
                if (isScreenOffSaverActive()) {
                    // Some panels swallow the first brightness=0 write right after
                    // entering the saver; repeating it ~300 ms later sticks reliably.
                    fadeHandler.postDelayed(() -> {
                        if (inScreenSaver && isScreenOffSaverActive()) {
                            applyBrightness(0, "screen-off screensaver second write", true);
                        }
                    }, 300L);
                }
            }
        } else {
            // Wake immediately; the hysteresis delay is only meant for lux jitter.
            setScreenOn(true);
            wakeScreen("exit screensaver");
            if (BuildConfig.DEBUG) Log.d(TAG, "Exited screensaver, applied brightness immediately");
        }
    }

    /**
     * Immediately restores brightness to the configured target without waiting for
     * the hysteresis delay.  Call this whenever the screen transitions from off/dark
     * to on so users see the correct brightness instantly rather than after the
     * 3-second lux-jitter guard fires.
     */
    private synchronized void wakeScreen(String reason) {
        int desiredBrightness = computeDesiredBrightness();
        targetBrightness = desiredBrightness;
        currentBrightness = desiredBrightness;
        fadeHandler.removeCallbacks(fadeRunnable);
        brightnessAnimator.cancel();
        applyBrightness(desiredBrightness, reason);
        lastUpdateTime = System.currentTimeMillis();
        if (BuildConfig.DEBUG) Log.d(TAG, "wakeScreen(" + reason + "): applied brightness=" + desiredBrightness);
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private boolean isScreenOffSaverActive() {
        if (mScreenSaverManager == null || !inScreenSaver) return false;
        var saver = mScreenSaverManager.getCurrentScreenSaver();
        return saver instanceof ScreenOffScreenSaver;
    }

    private boolean isAODSaverActive() {
        if (mScreenSaverManager == null || !inScreenSaver) return false;
        var saver = mScreenSaverManager.getCurrentScreenSaver();
        return saver instanceof AODScreenSaver;
    }

    private void applyBrightness(int rawValue, String reason) {
        applyBrightness(rawValue, reason, false);
    }

    private void applyBrightness(int rawValue, String reason, boolean force) {
        int clamped = clamp(rawValue, 0, 255);
        mDeviceHelper.setScreenBrightness(clamped, force);
        currentBrightness = clamped;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Brightness set to " + clamped + " (" + reason + "), target=" + targetBrightness + ", inScreenSaver=" + inScreenSaver + ", screenOn=" + screenOn);
        }
    }
}
