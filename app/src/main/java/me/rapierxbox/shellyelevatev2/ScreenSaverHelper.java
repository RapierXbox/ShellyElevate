package me.rapierxbox.shellyelevatev2;

import android.content.SharedPreferences;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ScreenSaverHelper {
    private static long lastTouchEvent = 0;
    private final ScheduledExecutorService scheduler;
    private final SharedPreferences sharedPreferences;

    public ScreenSaverHelper(SharedPreferences sP) {
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleWithFixedDelay(checkLastTouchEvent(), 0, 5, TimeUnit.SECONDS);
        sharedPreferences = sP;
    }

    public void onTouchEvent() {
        lastTouchEvent = System.currentTimeMillis();
        if (!DeviceHelper.getScreenOn()) {
            DeviceHelper.setScreenOn(true);
        }
    }

    private Runnable checkLastTouchEvent() {
        return () -> {
            if (System.currentTimeMillis() - lastTouchEvent > 45000 && DeviceHelper.getScreenOn() && sharedPreferences.getBoolean("screenSaver", true)) {
                DeviceHelper.setScreenOn(false);
            }
        };
    }

    public void onDestroy() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }
}
