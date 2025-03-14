package me.rapierxbox.shellyelevatev2.screensavers;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.util.Log;
import android.widget.ArrayAdapter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ScreenSaverManager {
    private long lastTouchEventTime;
    private int screenSaverDelay;
    private boolean screenSaverRunning;

    private final ScheduledExecutorService scheduler;

    private final ScreenSaver[] screenSavers;
    private int currentScreenSaverId;

    private boolean screenSaverEnabled;

    public ScreenSaverManager() {

        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleWithFixedDelay(this::checkLastTouchEventTime, 0, 1, TimeUnit.SECONDS);

        lastTouchEventTime = 0;
        screenSaverDelay = 45;
        currentScreenSaverId = 0;
        screenSaverRunning = false;

        screenSavers = new ScreenSaver[]{
            new ScreenOffScreenSaver(),
            new DigitalClockScreenSaver(),
            new DigitalClockAndDateScreenSaver()
        };
    }

    public void onTouchEvent() {
        lastTouchEventTime = System.currentTimeMillis();
        stopScreenSaver();
    }

    public void updateValues() {
        stopScreenSaver();

        screenSaverDelay = mSharedPreferences.getInt(SP_SCREEN_SAVER_DELAY, 45);
        if (screenSaverDelay < 5) {
            screenSaverDelay = 5;
            mSharedPreferences.edit().putInt(SP_SCREEN_SAVER_DELAY, 5).apply();
        }
        currentScreenSaverId = mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0);
        if (currentScreenSaverId < 0 || currentScreenSaverId >= screenSavers.length) {
            currentScreenSaverId = 0;
            mSharedPreferences.edit().putInt(SP_SCREEN_SAVER_ID, 0).apply();
        }
        screenSaverEnabled = mSharedPreferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true);
        currentScreenSaverId = Math.min(Math.max(currentScreenSaverId, 0), screenSavers.length - 1);
        lastTouchEventTime = System.currentTimeMillis();
    }

    public ArrayAdapter<String> getScreenSaverSpinnerAdapter() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(mApplicationContext, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        for (ScreenSaver screenSaver : screenSavers) {
            adapter.add(screenSaver.getName());
        }

        return adapter;
    }

    public void onDestroy() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    private void checkLastTouchEventTime() {
        if (System.currentTimeMillis() - lastTouchEventTime > screenSaverDelay * 1000L && screenSaverEnabled) {
            startScreenSaver();
        }
    }

    public void startScreenSaver() {
        if (!screenSaverRunning) {
            screenSaverRunning = true;
            screenSavers[currentScreenSaverId].onStart(mApplicationContext);
            Log.i("ShellyElevateV2", "Starting screen saver with id: " + currentScreenSaverId);
        }
    }

    public void stopScreenSaver() {
        if (screenSaverRunning) {
            screenSaverRunning = false;
            screenSavers[currentScreenSaverId].onEnd(mApplicationContext);
            Log.i("ShellyElevateV2", "Ending screen saver with id: " + currentScreenSaverId);
        }
    }
}
