package me.rapierxbox.shellyelevatev2.screensavers;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_OFF;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_ON;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class ScreenOffScreenSaver extends ScreenSaver {
    @Override
    public void onStart(Context context) {
        // Defer broadcast to avoid blocking saver start
        new Handler(Looper.getMainLooper()).post(() ->
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(INTENT_TURN_SCREEN_OFF)));
    }

    @Override
    public void onEnd(Context context) {
        // Defer broadcast to avoid blocking saver stop
        new Handler(Looper.getMainLooper()).post(() ->
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(INTENT_TURN_SCREEN_ON)));
    }

    @Override
    public String getName() {
        return "Screen Off";
    }
}
