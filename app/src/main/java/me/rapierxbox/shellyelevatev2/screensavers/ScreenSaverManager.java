package me.rapierxbox.shellyelevatev2.screensavers;

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;
import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication;

// Idle timer for the screensaver, plus proximity-based wake handling.
public class ScreenSaverManager extends BroadcastReceiver {

    private static final String TAG = "ScreenSaverManager";

    private final Context appContext;
    private final ScheduledExecutorService scheduler;
    private final ScreenSaver[] screenSavers;

    private long lastTouchEventTime;
    private volatile boolean screenSaverRunning;
	private volatile boolean keepAliveFlag = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long gestureToken = 0L;
    // Tracks whether the current touch gesture became multi-touch.
    private volatile boolean gestureIsMultiTouch = false;
    // Set by SwipeHelper when a multi-finger swipe was successfully recognized.
    private volatile boolean swipeFired = false;
    private long lastProximityWakeTime = 0L;
    private volatile Boolean lastNearState = null;
    private volatile ScheduledFuture<?> idleTask;
    private int runningSaverId = -1;

    public static ScreenSaver[] getAvailableScreenSavers() {
        return new ScreenSaver[]{
                new ScreenOffScreenSaver(),
                new DigitalClockScreenSaver(),
                new DigitalClockAndDateScreenSaver(),
                new AODScreenSaver()
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
        if (event == null) {
            rescheduleIdleCheck();
            return true;
        }

        int actionMasked = event.getActionMasked();

        if (actionMasked == ACTION_DOWN) {
            // New gesture starts: assume single-touch until a second pointer joins.
            gestureToken++;
            gestureIsMultiTouch = false;
            swipeFired = false;
            rescheduleIdleCheck();
            // Do not wake on DOWN yet; wait for ACTION_UP so multi-touch gestures
            // can complete while the screensaver remains active.
        }

        if (actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // Second (or more) finger joined this gesture.
            gestureIsMultiTouch = true;
        }

        if (actionMasked == ACTION_UP) {
            rescheduleIdleCheck();
            final long tokenAtUp = gestureToken;
            // Decide tap-vs-swipe at the end of this dispatch turn so SwipeHelper
            // can still mark onSwipeFired() even if it receives ACTION_UP later.
            mainHandler.post(() -> {
                if (tokenAtUp != gestureToken) return;
                if (isScreenSaverRunning() && !swipeFired) {
                    // Wake on taps (single- and multi-touch). Only keep running
                    // when a swipe was explicitly recognized for this gesture.
                    stopScreenSaver();
                }
                swipeFired = false;
                gestureIsMultiTouch = false;
            });
        }

        if (actionMasked == MotionEvent.ACTION_CANCEL) {
            if (isScreenSaverRunning()) {
                stopScreenSaver();
            }
            swipeFired = false;
            gestureIsMultiTouch = false;
        }
        return true;
    }

    public void onSwipeFired() {
        swipeFired = true;
    }

    public boolean isScreenSaverRunning() {
        return screenSaverRunning;
    }

    public ScreenSaver getCurrentScreenSaver() {
        if (ShellyElevateApplication.mSharedPreferences == null)
            return screenSavers[0];

        return getScreenSaverById(getCurrentScreenSaverId());
    }

    public ScreenSaver getScreenSaverById(int id) {
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

    // synchronized because paho scheduler and main threads all call this
    public synchronized void startScreenSaver() {
        if (screenSaverRunning || !isScreenSaverEnabled()) return;

        screenSaverRunning = true;

        // Reset proximity tracking so the first event after the screensaver starts is
        // always treated as a fresh transition.  Without this, a user who was already
        // near when the idle timeout fired would be unable to wake the screen because
        // lastNearState would already equal the incoming isNear value and the
        // transition guard would silently return early.
        lastNearState = null;
        if (mDeviceSensorManager != null) {
            // Force SensorManager-based sensors (which fire continuously) to re-publish
            // their current value so the screensaver can wake immediately when the user
            // is already in range.
            mDeviceSensorManager.resetProximityState();
        }

        ScreenSaver saver = getCurrentScreenSaver();
        runningSaverId = getCurrentScreenSaverId();
        saver.onStart(appContext);
        Log.i(TAG, "Starting screensaver: " + saver.getClass().getSimpleName());

        var mqtt = ShellyElevateApplication.mMQTTServer;
        if (mqtt != null && mqtt.shouldSend()) mqtt.publishSleeping(true);

        final int activeId = runningSaverId;
        // Broadcasts are observed by ScreenManager and friends; pushing them off
        // the main thread keeps the saver activity responsive on slow devices.
        scheduler.execute(() -> LocalBroadcastManager.getInstance(appContext)
                .sendBroadcast(new Intent(INTENT_SCREEN_SAVER_STARTED)
                        .putExtra(EXTRA_SCREEN_SAVER_ID, activeId)));
    }

    public synchronized void stopScreenSaver() {
        if (!screenSaverRunning) return;

        screenSaverRunning = false;
        // end the saver that was started not the one currently selected in prefs
        ScreenSaver saver = runningSaverId >= 0 ? getScreenSaverById(runningSaverId) : getCurrentScreenSaver();
        saver.onEnd(appContext);

        final int activeId = runningSaverId;
        runningSaverId = -1;

        scheduler.execute(() -> {
            LocalBroadcastManager.getInstance(appContext)
                    .sendBroadcast(new Intent(INTENT_END_SCREENSAVER));
            LocalBroadcastManager.getInstance(appContext)
                    .sendBroadcast(new Intent(INTENT_SCREEN_SAVER_STOPPED)
                            .putExtra(EXTRA_SCREEN_SAVER_ID, activeId));
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
            // stop active saver if user switched type mid-sleep so its side effects unwind
            if (screenSaverRunning && runningSaverId != getCurrentScreenSaverId()) {
                stopScreenSaver();
            }
            rescheduleIdleCheck();
            return;
        }
        float maxProximitySensorValue = mDeviceSensorManager != null
                ? mDeviceSensorManager.getMaxProximitySensorValue()
                : 5.0f;
        float proximity = intent.getFloatExtra(INTENT_PROXIMITY_KEY, maxProximitySensorValue);
        if (BuildConfig.DEBUG) Log.i(TAG, "Proximity event: " + proximity + " - Value: " + proximity);

        long now = System.currentTimeMillis();

        var mqtt = ShellyElevateApplication.mMQTTServer;
        if (mqtt != null && mqtt.shouldSend()) mqtt.publishProximity(proximity);

        var prefs = ShellyElevateApplication.mSharedPreferences;
        if (prefs == null) return;

        boolean wakeOnProximity = prefs.getBoolean(SP_WAKE_ON_PROXIMITY, true);
        int configuredKeepAwakeSeconds = Math.max(0, prefs.getInt(SP_PROXIMITY_KEEP_AWAKE_SECONDS, 30));
        long keepAwakeMs = configuredKeepAwakeSeconds * 1000L;
        float threshold = maxProximitySensorValue <= 1.5f ? 0.5f : Math.max(0.5f, maxProximitySensorValue * 0.1f);
        boolean isNear = proximity < maxProximitySensorValue - threshold;

        // Only act on near/far transitions, not on each repeated event.
        if (lastNearState != null && lastNearState == isNear) {
            return;
        }
        lastNearState = isNear;

        if (screenSaverRunning && isNear) {
            // Force a wake even when SP_WAKE_ON_PROXIMITY is off, otherwise the
            // user would be left with a black screen they can't recover from.
            stopScreenSaver();
            lastProximityWakeTime = now;
            keepAwakeAfterProximity(now, keepAwakeMs);
        } else if (wakeOnProximity && isNear) {
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
