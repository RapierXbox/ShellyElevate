package me.rapierxbox.shellyelevatev2.screensavers;

import static android.view.MotionEvent.ACTION_UP;
import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.view.MotionEvent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication;

/**
 * Handles automatic screensaver start/stop logic and proximity-based wake.
 */
public class ScreenSaverManager extends BroadcastReceiver {

    private static final String TAG = "ScreenSaverManager";

    private final Context appContext;
    private final ScheduledExecutorService scheduler;
    private final ScreenSaver[] screenSavers;

    private long lastTouchEventTime;
    private boolean screenSaverRunning;
	private volatile boolean keepAliveFlag = false;
    private long lastProximityEventTime = 0L;
    private long lastProximityWakeTime = 0L;
    private Boolean lastNearState = null;
    private volatile ScheduledFuture<?> idleTask;

    public static ScreenSaver[] getAvailableScreenSavers() {
        return new ScreenSaver[]{
                new ScreenOffScreenSaver(),
                new DigitalClockScreenSaver(),
                new DigitalClockAndDateScreenSaver()
        };
    }

    public ScreenSaverManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.screenSavers = getAvailableScreenSavers();
        this.lastTouchEventTime = System.currentTimeMillis();
        this.screenSaverRunning = false;

        rescheduleIdleCheck();

        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_PROXIMITY_UPDATED);
        filter.addAction(INTENT_SETTINGS_CHANGED);
        LocalBroadcastManager.getInstance(appContext).registerReceiver(this, filter);

        Log.i(TAG, "ScreenSaverManager initialized");
    }

    public void onDestroy() {
        try {
            LocalBroadcastManager.getInstance(appContext).unregisterReceiver(this);
        } catch (Exception e) {
            Log.w(TAG, "Receiver already unregistered", e);
        }

        if (!scheduler.isShutdown()) scheduler.shutdownNow();
        Log.i(TAG, "ScreenSaverManager destroyed");
    }

    public boolean onTouchEvent(MotionEvent event) {
        lastTouchEventTime = System.currentTimeMillis();
        rescheduleIdleCheck();
        if (event == null) return true;

        if (event.getAction() == ACTION_UP && isScreenSaverRunning()) {
            stopScreenSaver();
        }
        return true;
    }

    public boolean isScreenSaverRunning() {
        return screenSaverRunning;
    }

    public ScreenSaver getCurrentScreenSaver() {
        if (ShellyElevateApplication.mSharedPreferences == null)
            return screenSavers[0];

        int id = getCurrentScreenSaverId();
        return screenSavers[Math.max(0, Math.min(id, screenSavers.length - 1))];
    }

    public int getCurrentScreenSaverId() {
        return ShellyElevateApplication.mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0);
    }

    public boolean isScreenSaverEnabled() {
        return ShellyElevateApplication.mSharedPreferences != null &&
                ShellyElevateApplication.mSharedPreferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true);
    }

	private synchronized void rescheduleIdleCheck() {
		ScheduledFuture<?> current = idleTask;
		if (current != null) { current.cancel(false); idleTask = null; }
		if (scheduler.isShutdown()) return;

		var prefs = ShellyElevateApplication.mSharedPreferences;
		if (prefs == null) return;
		if (keepAliveFlag || screenSaverRunning) return;
		if (!prefs.getBoolean(SP_SCREEN_SAVER_ENABLED, true)) return;

		long delayMs = Math.max(5, prefs.getInt(SP_SCREEN_SAVER_DELAY, 45)) * 1000L;
		long elapsed = System.currentTimeMillis() - lastTouchEventTime;
		long remaining = Math.max(0L, delayMs - elapsed);
		idleTask = scheduler.schedule(this::onIdleDeadline, remaining, TimeUnit.MILLISECONDS);
	}

	private void onIdleDeadline() {
		idleTask = null;
		var prefs = ShellyElevateApplication.mSharedPreferences;
		if (prefs == null) return;
		if (keepAliveFlag || screenSaverRunning) return;
		if (!prefs.getBoolean(SP_SCREEN_SAVER_ENABLED, true)) return;

		long delayMs = Math.max(5, prefs.getInt(SP_SCREEN_SAVER_DELAY, 45)) * 1000L;
		long elapsed = System.currentTimeMillis() - lastTouchEventTime;
		if (elapsed >= delayMs) startScreenSaver();
		else rescheduleIdleCheck();
	}

	public void keepAlive(boolean keepAlive) {
		this.keepAliveFlag = keepAlive;
		if (keepAlive) {
			Log.i(TAG, "KeepAlive enabled: screensaver will not start");
			if (screenSaverRunning) stopScreenSaver();
			rescheduleIdleCheck();
		} else {
			Log.i(TAG, "KeepAlive disabled: screensaver logic resumes");
			lastTouchEventTime = System.currentTimeMillis();
			rescheduleIdleCheck();
		}
	}

    public void startScreenSaver() {
        if (screenSaverRunning || !isScreenSaverEnabled()) return;

        screenSaverRunning = true;
        ScreenSaver saver = getCurrentScreenSaver();
        saver.onStart(appContext);
        Log.i(TAG, "Starting screensaver: " + saver.getClass().getSimpleName());

        var mqtt = ShellyElevateApplication.mMQTTServer;
        if (mqtt != null && mqtt.shouldSend()) mqtt.publishSleeping(true);

        // Defer non-critical broadcast to reduce main-thread pressure
        scheduler.execute(() -> LocalBroadcastManager.getInstance(appContext)
                .sendBroadcast(new Intent(INTENT_SCREEN_SAVER_STARTED)));
    }

    public void stopScreenSaver() {
        if (!screenSaverRunning) return;

        screenSaverRunning = false;
        ScreenSaver saver = getCurrentScreenSaver();
        saver.onEnd(appContext);

        // Defer non-critical broadcasts to reduce main-thread pressure
        scheduler.execute(() -> {
            appContext.sendBroadcast(new Intent(INTENT_END_SCREENSAVER));
            LocalBroadcastManager.getInstance(appContext)
                    .sendBroadcast(new Intent(INTENT_SCREEN_SAVER_STOPPED));
        });

        lastTouchEventTime = System.currentTimeMillis();
        rescheduleIdleCheck();

        Log.i(TAG, "Stopping screensaver: " + saver.getClass().getSimpleName());

        var mqtt = ShellyElevateApplication.mMQTTServer;
        if (mqtt != null && mqtt.shouldSend()) mqtt.publishSleeping(false);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (INTENT_SETTINGS_CHANGED.equals(intent.getAction())) {
            rescheduleIdleCheck();
            return;
        }
        float maxProximitySensorValue = mDeviceSensorManager != null
                ? mDeviceSensorManager.getMaxProximitySensorValue()
                : 5.0f;
        float proximity = intent.getFloatExtra(INTENT_PROXIMITY_KEY, maxProximitySensorValue);
        if (BuildConfig.DEBUG) Log.i(TAG, "Proximity event: " + proximity + " - Value: " + proximity);

        long now = System.currentTimeMillis();
        if (now - lastProximityEventTime < 350L) {
            return; // debounce rapid proximity updates
        }
        lastProximityEventTime = now;

        var mqtt = ShellyElevateApplication.mMQTTServer;
        if (mqtt != null && mqtt.shouldSend()) mqtt.publishProximity(proximity);

        var prefs = ShellyElevateApplication.mSharedPreferences;
        if (prefs == null) return;

        boolean wakeOnProximity = prefs.getBoolean(SP_WAKE_ON_PROXIMITY, true);
        int configuredKeepAwakeSeconds = Math.max(0, prefs.getInt(SP_PROXIMITY_KEEP_AWAKE_SECONDS, 30));
        long keepAwakeMs = configuredKeepAwakeSeconds * 1000L;
        float threshold = maxProximitySensorValue <= 1.5f ? 0.5f : Math.max(0.5f, maxProximitySensorValue * 0.1f);
        boolean isNear = proximity < maxProximitySensorValue - threshold;

        // Skip duplicate near/far state updates to avoid spammy wake handling.
        if (lastNearState != null && lastNearState == isNear) {
            return;
        }
        lastNearState = isNear;

        if (screenSaverRunning && isNear) {
            // Wake even if the pref is off to avoid being stuck at brightness 0.
            stopScreenSaver();
            lastProximityWakeTime = now;
            keepAwakeAfterProximity(now, keepAwakeMs);
        } else if (wakeOnProximity && isNear) {
            // Ignore repeated near edges that happen too quickly due to noisy sensors.
            if (now - lastProximityWakeTime < 1000L) return;
            lastProximityWakeTime = now;
            keepAwakeAfterProximity(now, keepAwakeMs);
        }
    }

    private void keepAwakeAfterProximity(long now, long keepAwakeMs) {
        var prefs = ShellyElevateApplication.mSharedPreferences;
        if (prefs == null) return;

        long idleDelayMs = Math.max(5, prefs.getInt(SP_SCREEN_SAVER_DELAY, 45)) * 1000L;
        if (keepAwakeMs <= 0L) {
            lastTouchEventTime = now;
        } else {
            lastTouchEventTime = now - idleDelayMs + keepAwakeMs;
        }
        rescheduleIdleCheck();
    }
}
