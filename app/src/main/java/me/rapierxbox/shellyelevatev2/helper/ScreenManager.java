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
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.jetbrains.annotations.NotNull;

public class ScreenManager extends BroadcastReceiver {

    private static final String TAG = "ScreenManager";
    private static final long HYSTERESIS_DELAY_MS = 3000; // 3 seconds
    private static final long FADE_DURATION_MS = 1000;

    public static final int MIN_BRIGHTNESS_DEFAULT = 48;
    public static final int DEFAULT_BRIGHTNESS = 255;

    private float lastMeasuredLux = 0.0f;

    //region Fade Manager
    private long lastUpdateTime = 0;

    private int currentBrightness = -1;
    private int targetBrightness = -1;

    private final Handler fadeHandler = new Handler(Looper.getMainLooper());
    private Runnable fadeRunnable;
    private boolean inScreenSaver = false;
    //endregion

    private final Context context;

    private final BrightnessAnimator brightnessAnimator = new BrightnessAnimator();

    public ScreenManager(Context ctx) {
        context = ctx;

        IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(INTENT_SCREEN_SAVER_STARTED);
        intentFilter.addAction(INTENT_SCREEN_SAVER_STOPPED);
        intentFilter.addAction(INTENT_TURN_SCREEN_ON);
        intentFilter.addAction(INTENT_TURN_SCREEN_OFF);
        intentFilter.addAction(INTENT_LIGHT_UPDATED);

        LocalBroadcastManager.getInstance(context).registerReceiver(this, intentFilter);
    }

    public void setScreenOn(boolean on) {
        mDeviceHelper.setScreenOn(on);
    }

    private boolean automaticBrightness() {
        return context.getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE).getBoolean(SP_AUTOMATIC_BRIGHTNESS, true);
    }

    private int fixedBrightness() {
        return context.getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE).getInt(SP_BRIGHTNESS, DEFAULT_BRIGHTNESS);
    }

    private void updateBrightness() {
        int desiredBrightness;

        if (automaticBrightness()) {
            desiredBrightness = getScreenBrightnessFromLux(lastMeasuredLux);
        } else {
            desiredBrightness = fixedBrightness();
        }

        Log.d(TAG, "Desired brightness: " + desiredBrightness + ", targetBrightness: " + targetBrightness + ", lastUpdateTime: " + lastUpdateTime + ", currentBrightness: " + currentBrightness);

        if (desiredBrightness != targetBrightness) {
            targetBrightness = desiredBrightness;
            lastUpdateTime = System.currentTimeMillis();
            fadeHandler.removeCallbacks(fadeRunnable);
            fadeRunnable = this::checkAndApplyBrightness;
            fadeHandler.postDelayed(fadeRunnable, HYSTERESIS_DELAY_MS);
        }
    }

    private void checkAndApplyBrightness() {
        if (System.currentTimeMillis() - lastUpdateTime >= HYSTERESIS_DELAY_MS) {
            if (currentBrightness == -1) {
                currentBrightness = targetBrightness;
                mDeviceHelper.setScreenBrightness(currentBrightness);
            } else if (currentBrightness != targetBrightness) {
                animateBrightnessTransition(currentBrightness, targetBrightness, FADE_DURATION_MS);
            } else {
                Log.d(TAG, "No brightness change needed.");
            }
        }
    }

    private void animateBrightnessTransition(int from, int to, long duration) {
        brightnessAnimator.animate(from, to, duration, value -> {
            mDeviceHelper.setScreenBrightness(value);
            currentBrightness = value;
        });
    }

    private int getScreenBrightnessFromLux(float lux) {
        int minBrightness;

        if (inScreenSaver) {
            minBrightness = context.getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE).getInt(SP_SCREEN_SAVER_MIN_BRIGHTNESS, MIN_BRIGHTNESS_DEFAULT);
        } else {
            minBrightness = context.getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE).getInt(SP_MIN_BRIGHTNESS, MIN_BRIGHTNESS_DEFAULT);
        }

        if (lux >= 500) return 255;
        if (lux <= 30) return minBrightness;

        double slope = (255.0 - minBrightness) / (500.0 - 30.0);
        return (int) (minBrightness + slope * (lux - 30));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
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
                break;
            case INTENT_LIGHT_UPDATED:
                lastMeasuredLux = intent.getFloatExtra(INTENT_LIGHT_KEY, 0.0f);
                updateBrightness();
                break;
        }
    }

    private void updateScreenSaverState(boolean newState) {
        this.inScreenSaver = newState;
        updateBrightness();
    }
}
