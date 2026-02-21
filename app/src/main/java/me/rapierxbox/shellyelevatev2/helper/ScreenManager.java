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
import me.rapierxbox.shellyelevatev2.screensavers.ScreenOffScreenSaver;

public class ScreenManager extends BroadcastReceiver {

    private static final String TAG = "ScreenManager";
    private static final long HYSTERESIS_DELAY_MS = 3000L; // 3 seconds
    public static final long FADE_DURATION_MS = 1000L;
    private static final int MIN_BRIGHTNESS_STEP = 3;

    public static final int MIN_BRIGHTNESS_DEFAULT = 48;
    public static final int DEFAULT_BRIGHTNESS = 255;

    // sensor
    private float lastMeasuredLux = 0.0f;

    // fade/state
    private long lastUpdateTime = 0L;
    private volatile int currentBrightness = -1; // -1 = uninitialized
    private volatile int targetBrightness = -1;
    private volatile boolean inScreenSaver = false;
    private volatile boolean screenOn = true; // explicit screen state separate from brightness

    // handler
    private final Handler fadeHandler = new Handler(Looper.getMainLooper());
    private final Runnable fadeRunnable = this::checkAndApplyBrightness;

    // prefs cached
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

        // Force screen on at boot so we never start at brightness 0; screensaver will handle dimming later
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
        // keep explicit boolean for state; do not conflate with brightness
        screenOn = on;
        if (!on) {
            // keep brightness state consistent with screen off
            currentBrightness = 0;
        }
        // keep using existing mDeviceHelper (per request to avoid DI change)
        mDeviceHelper.setScreenOn(on);
        // ensure device brightness is in sync
        if (!on) {
            applyBrightness(0, "screen off");
        }
    }

    /**
     * Handle touch events to wake screen if touch-to-wake is enabled.
     * Call this from MainActivity when touch events are detected on the WebView.
     */
    public void onTouchEvent() {
        if (BuildConfig.DEBUG) Log.d(TAG, "onTouchEvent called: screenOn=" + screenOn + ", cachedTouchToWake=" + cachedTouchToWake + ", currentBrightness=" + currentBrightness + ", inScreenSaver=" + inScreenSaver);
        // If screensaver is active, stop it immediately on touch
        if (inScreenSaver) {
            Log.i(TAG, "Touch detected, exiting screensaver");
            mScreenSaverManager.stopScreenSaver();
            return;
        }

        // If screen is off and touch-to-wake is enabled, turn screen back on
        if (!screenOn && cachedTouchToWake) {
            Log.i(TAG, "Touch detected, waking screen via touch-to-wake");
            setScreenOn(true);
            updateBrightness();
        }
    }

    /**
     * Determines if a touch should be consumed for waking instead of propagating to WebView.
     */
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
                setScreenOn(true);
                break;
            case INTENT_TURN_SCREEN_OFF:
                setScreenOn(false);
                // When screen is turned off, reset brightness targets to 0 to avoid stale values
                targetBrightness = 0;
                currentBrightness = 0;
                break;
            case INTENT_LIGHT_UPDATED:
                float lux = intent.getFloatExtra(INTENT_LIGHT_KEY, 0.0f);
                if (Float.isNaN(lux) || lux < 0f) lux = 0f; // sanitize
                lastMeasuredLux = lux;

                // update brightness when lux changes only if automatic bri is enabled to prevent too many request
                // TODO: implement some kind of debounce to prevent too many calls
                if (automaticBrightness())
                    updateBrightness();

                break;
        }
    }

    private synchronized void updateBrightness() {
        int desiredBrightness = computeDesiredBrightness();

        // Force brightness to 0 only when the screen is explicitly off or the active saver is "Screen Off"
        if (!screenOn || (inScreenSaver && isScreenOffSaverActive())) {
            targetBrightness = 0;
            applyBrightness(0, "screen off or screen-off screensaver");
            fadeHandler.removeCallbacks(fadeRunnable);
            brightnessAnimator.cancel();
            return;
        }

        if (desiredBrightness != targetBrightness) {
            if (targetBrightness >= 0 && Math.abs(desiredBrightness - targetBrightness) < MIN_BRIGHTNESS_STEP) {
                return; // ignore tiny adjustments to reduce churn
            }
            targetBrightness = desiredBrightness;
            lastUpdateTime = System.currentTimeMillis();
            // cancel any pending fade to avoid race with outdated tasks
            fadeHandler.removeCallbacks(fadeRunnable);
            // post hysteresis delayed task on main looper
            fadeHandler.postDelayed(fadeRunnable, HYSTERESIS_DELAY_MS);
            if (BuildConfig.DEBUG) Log.d(TAG, "Desired brightness: " + desiredBrightness + ", targetBrightness: " + targetBrightness + ", lastUpdateTime: " + lastUpdateTime + ", currentBrightness: " + currentBrightness);
        }
    }

    private int computeDesiredBrightness() {
        if (!screenOn || (inScreenSaver && isScreenOffSaverActive())) {
            return 0;
        }

        int desiredBrightness;
        if (automaticBrightness()) {
            desiredBrightness = getScreenBrightnessFromLux(lastMeasuredLux);
        } else {
            desiredBrightness = fixedBrightness();
        }

        return clamp(desiredBrightness, 0, 255);
    }

    private synchronized void checkAndApplyBrightness() {
        // force in case of brightness 0 (ensure immediate apply to 0)
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
            // A possible in-flight update; re-schedule to ensure we eventually apply.
            fadeHandler.removeCallbacks(fadeRunnable);
            long delay = Math.max(0, HYSTERESIS_DELAY_MS - (now - lastUpdateTime));
            fadeHandler.postDelayed(fadeRunnable, delay);
        }
    }

    private void animateBrightnessTransition(int from, int to) {
        // ensure bounds
        from = clamp(from, 0, 255);
        to = clamp(to, 0, 255);

        // Use the existing animator class if available; fallback to ValueAnimator if needed.
        try {
            brightnessAnimator.animate(from, to, value -> {
                applyBrightness(value, "animate");
            });
        } catch (Throwable t) {
            // fallback safe path: immediate set (shouldn't happen often)
            if (BuildConfig.DEBUG) Log.d(TAG, "BrightnessAnimator failed, applying immediate value. " + t.getMessage());
            applyBrightness(to, "animate fallback");
        }
    }

    private int getScreenBrightnessFromLux(float lux) {
        // sanitize input
        if (Float.isNaN(lux) || lux < 0f) lux = 0f;

        int minBrightness = inScreenSaver ? cachedScreenSaverMinBrightness : cachedMinBrightness;
        minBrightness = clamp(minBrightness, 0, 255);

        if (lux >= 500f) return 255;
        if (lux <= 30f) return minBrightness;

        double slope = (255.0 - minBrightness) / (500.0 - 30.0);
        double computed = minBrightness + slope * (lux - 30.0);
        return clamp((int) Math.round(computed), 0, 255);
    }

    private synchronized void updateScreenSaverState(boolean newState) {
        this.inScreenSaver = newState;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "updateScreenSaverState newState=" + newState + ", screenOn=" + screenOn + ", currentBrightness=" + currentBrightness + ", targetBrightness=" + targetBrightness + ", lux=" + lastMeasuredLux);
        }
        if (newState) {
            updateBrightness();
            if (isScreenOffSaverActive()) {
                // Force a second write shortly after entering screen-off saver to avoid hardware ignoring the first set
                fadeHandler.postDelayed(() -> {
                    if (inScreenSaver && isScreenOffSaverActive()) {
                        applyBrightness(0, "screen-off screensaver second write");
                    }
                }, 300L);
            }
        } else {
            // On wake from screensaver, raise brightness immediately (no 3s hysteresis)
            // Ensure screen is marked on before computing brightness so we don't stick at 0
            setScreenOn(true);

            int desiredBrightness = computeDesiredBrightness();
            targetBrightness = desiredBrightness;
            currentBrightness = desiredBrightness;
            fadeHandler.removeCallbacks(fadeRunnable);
            brightnessAnimator.cancel();
            applyBrightness(desiredBrightness, "exit screensaver immediate");
            lastUpdateTime = System.currentTimeMillis();
            if (BuildConfig.DEBUG) Log.d(TAG, "Exited screensaver, applied brightness immediately: " + desiredBrightness);
        }
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

    private void applyBrightness(int rawValue, String reason) {
        int clamped = clamp(rawValue, 0, 255);
        mDeviceHelper.setScreenBrightness(clamped);
        currentBrightness = clamped;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Brightness set to " + clamped + " (" + reason + "), target=" + targetBrightness + ", inScreenSaver=" + inScreenSaver + ", screenOn=" + screenOn);
        }
    }
}
