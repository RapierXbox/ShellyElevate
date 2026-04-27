package me.rapierxbox.shellyelevatev2.screensavers;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_OFF;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_ON;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

// Posts the broadcasts via the main Looper so the saver can return immediately;
// LocalBroadcastManager.sendBroadcast can otherwise dispatch synchronously to
// receivers on the calling thread.
public class ScreenOffScreenSaver extends ScreenSaver {
    @Override
    public void onStart(Context context) {
        new Handler(Looper.getMainLooper()).post(() ->
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(INTENT_TURN_SCREEN_OFF)));
    }

    @Override
    public void onEnd(Context context) {
        new Handler(Looper.getMainLooper()).post(() ->
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(INTENT_TURN_SCREEN_ON)));
    }

    @Override
    public String getName() {
        return "Screen Off";
    }
}
