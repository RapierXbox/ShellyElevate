package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STARTED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STOPPED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED;
import static me.rapierxbox.shellyelevatev2.Constants.SLEEP_OPT_AGGRESSIVE;
import static me.rapierxbox.shellyelevatev2.Constants.SLEEP_OPT_NONE;
import static me.rapierxbox.shellyelevatev2.Constants.SLEEP_OPT_STANDARD;
import static me.rapierxbox.shellyelevatev2.Constants.SP_SLEEP_OPTIMIZATION_LEVEL;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mBluetoothProxyManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mVoiceEngine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// applies the user chosen sleep optimization level while a screensaver runs
// standard lowers the cpu governor and aggressive also throttles mqtt bluetooth and voice
public class PowerOptimizer extends BroadcastReceiver {

    private static final String TAG = "PowerOptimizer";

    private final Context appContext;
    private final CpuGovernor cpuGovernor = new CpuGovernor();
    // single thread keeps apply and restore ordered and off the main thread
    private final ExecutorService sysfsExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean sleepActive = false;
    private volatile int activeLevel = SLEEP_OPT_NONE;

    public PowerOptimizer(Context ctx) {
        this.appContext = ctx.getApplicationContext();

        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_SCREEN_SAVER_STARTED);
        filter.addAction(INTENT_SCREEN_SAVER_STOPPED);
        filter.addAction(INTENT_SETTINGS_CHANGED);
        LocalBroadcastManager.getInstance(appContext).registerReceiver(this, filter);

        Log.i(TAG, "PowerOptimizer initialized, level=" + currentLevel());
    }

    public void onDestroy() {
        LocalBroadcastManager.getInstance(appContext).unregisterReceiver(this);

        if (sleepActive) {
            try {
                exitSleep();
            } catch (Exception e) {
                Log.w(TAG, "exitSleep on destroy failed: " + e.getMessage());
            }
        }
        // shutdown and not shutdownNow so a queued restore still runs
        sysfsExecutor.shutdown();
    }

    private int currentLevel() {
        if (mSharedPreferences == null) return SLEEP_OPT_NONE;
        return mSharedPreferences.getInt(SP_SLEEP_OPTIMIZATION_LEVEL, SLEEP_OPT_NONE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case INTENT_SCREEN_SAVER_STARTED:
                // every saver including aod uses the user defined sleep level
                enterSleep();
                break;
            case INTENT_SCREEN_SAVER_STOPPED:
                exitSleep();
                break;
            case INTENT_SETTINGS_CHANGED:
                if (sleepActive) {
                    int newLevel = currentLevel();
                    if (newLevel != activeLevel) {
                        Log.i(TAG, "Level changed mid-sleep " + activeLevel + " -> " + newLevel + ", reapplying");
                        exitSleep();
                        enterSleep();
                    }
                }
                break;
        }
    }

    private synchronized void enterSleep() {
        if (sleepActive) return;
        int level = currentLevel();
        activeLevel = level;
        sleepActive = true;

        Log.i(TAG, "Entering sleep, level=" + level);

        if (level >= SLEEP_OPT_STANDARD) {
            sysfsExecutor.execute(cpuGovernor::applyLowPower);
        }
        if (level >= SLEEP_OPT_AGGRESSIVE) {
            if (mMQTTServer != null) mMQTTServer.setLowPowerMode(true);
            if (mBluetoothProxyManager != null) mBluetoothProxyManager.setLowPowerMode(true);
            if (mVoiceEngine != null) mVoiceEngine.setLowPowerMode(true);
        }
    }

    private synchronized void exitSleep() {
        if (!sleepActive) return;
        int level = activeLevel;
        sleepActive = false;
        activeLevel = SLEEP_OPT_NONE;

        Log.i(TAG, "Exiting sleep, level was=" + level);

        // undone in reverse order of enterSleep
        if (level >= SLEEP_OPT_AGGRESSIVE) {
            if (mVoiceEngine != null) mVoiceEngine.setLowPowerMode(false);
            if (mBluetoothProxyManager != null) mBluetoothProxyManager.setLowPowerMode(false);
            if (mMQTTServer != null) mMQTTServer.setLowPowerMode(false);
        }
        if (level >= SLEEP_OPT_STANDARD) {
            sysfsExecutor.execute(cpuGovernor::restore);
        }
    }
}
