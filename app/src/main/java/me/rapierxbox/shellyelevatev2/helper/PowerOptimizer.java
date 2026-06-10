package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.EXTRA_SLEEP_ACTIVE;
import static me.rapierxbox.shellyelevatev2.Constants.EXTRA_SLEEP_LEVEL;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STARTED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STOPPED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SLEEP_LEVEL_CHANGED;
import static me.rapierxbox.shellyelevatev2.Constants.SLEEP_OPT_AGGRESSIVE;
import static me.rapierxbox.shellyelevatev2.Constants.SLEEP_OPT_NONE;
import static me.rapierxbox.shellyelevatev2.Constants.SLEEP_OPT_STANDARD;
import static me.rapierxbox.shellyelevatev2.Constants.SP_SLEEP_OPTIMIZATION_LEVEL;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mBluetoothProxyManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mVoiceAssistantManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class PowerOptimizer extends BroadcastReceiver {

    private static final String TAG = "PowerOptimizer";

    private final Context appContext;
    private final CpuGovernor cpuGovernor = new CpuGovernor();

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
        try {
            LocalBroadcastManager.getInstance(appContext).unregisterReceiver(this);
        } catch (Exception ignored) {}

        if (sleepActive) {
            try { exitSleep(); } catch (Exception e) {
                Log.w(TAG, "exitSleep on destroy failed: " + e.getMessage());
            }
        }
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
                // every saver (incl aod) uses the user-defined sleep level
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
        broadcastLevel(true, level);

        if (level >= SLEEP_OPT_STANDARD) {
            cpuGovernor.applyLowPower();
        }
        if (level >= SLEEP_OPT_AGGRESSIVE) {
            if (mMQTTServer != null) mMQTTServer.setLowPowerMode(true);
            if (mBluetoothProxyManager != null) mBluetoothProxyManager.setLowPowerMode(true);
            if (mVoiceAssistantManager != null) mVoiceAssistantManager.setLowPowerMode(true);
        }
    }

    private synchronized void exitSleep() {
        if (!sleepActive) return;
        int level = activeLevel;
        sleepActive = false;
        activeLevel = SLEEP_OPT_NONE;

        Log.i(TAG, "Exiting sleep, level was=" + level);
        broadcastLevel(false, SLEEP_OPT_NONE);

        if (level >= SLEEP_OPT_AGGRESSIVE) {
            if (mVoiceAssistantManager != null) mVoiceAssistantManager.setLowPowerMode(false);
            if (mBluetoothProxyManager != null) mBluetoothProxyManager.setLowPowerMode(false);
            if (mMQTTServer != null) mMQTTServer.setLowPowerMode(false);
        }
        if (level >= SLEEP_OPT_STANDARD) {
            cpuGovernor.restore();
        }
    }

    private void broadcastLevel(boolean active, int level) {
        Intent intent = new Intent(INTENT_SLEEP_LEVEL_CHANGED)
                .putExtra(EXTRA_SLEEP_ACTIVE, active)
                .putExtra(EXTRA_SLEEP_LEVEL, level);
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
    }
}
