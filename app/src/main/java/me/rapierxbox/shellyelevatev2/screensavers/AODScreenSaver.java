package me.rapierxbox.shellyelevatev2.screensavers;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_AOD_STARTED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_AOD_STOPPED;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

// post on main looper to avoid synchronous receiver dispatch on the caller thread
public class AODScreenSaver extends ScreenSaver {
    @Override
    public void onStart(Context context) {
        new Handler(Looper.getMainLooper()).post(() ->
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(INTENT_AOD_STARTED)));
    }

    @Override
    public void onEnd(Context context) {
        new Handler(Looper.getMainLooper()).post(() ->
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(INTENT_AOD_STOPPED)));
    }

    @Override
    public String getName() {
        return "Always On Display";
    }
}
