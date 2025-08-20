package me.rapierxbox.shellyelevatev2.screensavers;

import static android.view.MotionEvent.ACTION_UP;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_END_SCREENSAVER;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_KEY;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_UPDATED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STARTED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STOPPED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_DELAY;
import static me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_ENABLED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_ID;
import static me.rapierxbox.shellyelevatev2.Constants.SP_WAKE_ON_PROXIMITY;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.view.MotionEvent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ScreenSaverManager extends BroadcastReceiver {
    private long lastTouchEventTime;
    private boolean screenSaverRunning;

    private final ScheduledExecutorService scheduler;

    private final ScreenSaver[] screenSavers;

    public static ScreenSaver[] getAvailableScreenSavers() {
        return new ScreenSaver[]{new ScreenOffScreenSaver(), new DigitalClockScreenSaver(), new DigitalClockAndDateScreenSaver()};
    }

    public ScreenSaverManager(Context ctx) {

        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleWithFixedDelay(this::checkLastTouchEventTime, 0, 1, TimeUnit.SECONDS);

        lastTouchEventTime = System.currentTimeMillis();
        screenSaverRunning = false;

        screenSavers = getAvailableScreenSavers();

        LocalBroadcastManager.getInstance(ctx).registerReceiver(this, new IntentFilter(INTENT_PROXIMITY_UPDATED));
    }

    public boolean onTouchEvent(MotionEvent event) {
        lastTouchEventTime = System.currentTimeMillis();
        if ((event == null || event.getAction() == ACTION_UP) && isScreenSaverRunning()) {
            stopScreenSaver();
        }
        return true;
    }

    public boolean isScreenSaverRunning() {
        return screenSaverRunning;
    }

    public int getCurrentScreenSaverId() {
        return mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0);
    }

    public boolean isScreenSaverEnabled() {
        return mSharedPreferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true);
    }

    public void onDestroy(Context ctx) {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }

        LocalBroadcastManager.getInstance(ctx).unregisterReceiver(this);
    }

    private void checkLastTouchEventTime() {
        if (System.currentTimeMillis() - lastTouchEventTime > mSharedPreferences.getInt(SP_SCREEN_SAVER_DELAY, 45) * 1000L && mSharedPreferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true)) {
            startScreenSaver();
        }
    }

    public void startScreenSaver() {
        if (!screenSaverRunning) {
            screenSaverRunning = true;

            screenSavers[mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0)].onStart(mApplicationContext);
            Log.i("ShellyElevateV2", "Starting screen saver with id: " + mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0));

            if (mMQTTServer.shouldSend()) {
                mMQTTServer.publishSleeping(true);
            }

            //Let everyone know we are starting the screensaver
            LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(new Intent(INTENT_SCREEN_SAVER_STARTED));
        }
    }

    public void stopScreenSaver() {
        if (screenSaverRunning) {
            screenSaverRunning = false;
            screenSavers[mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0)].onEnd(mApplicationContext);
            mApplicationContext.sendBroadcast(new Intent(INTENT_END_SCREENSAVER));

            lastTouchEventTime = System.currentTimeMillis();
            Log.i("ShellyElevateV2", "Ending screen saver with id: " + mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0));

            if (mMQTTServer.shouldSend()) {
                mMQTTServer.publishSleeping(false);
            }

            //Let everyone know we are stopping the screensaver
            LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(new Intent(INTENT_SCREEN_SAVER_STOPPED));
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (mSharedPreferences.getBoolean(SP_WAKE_ON_PROXIMITY, false)) {
            if (screenSaverRunning && intent.getFloatExtra(INTENT_PROXIMITY_KEY, 100.0f) <= 7.5f) {
                stopScreenSaver();
            }
        }
    }
}
