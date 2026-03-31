package me.rapierxbox.shellyelevatev2.screensavers;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_OFF;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_ON;

import android.content.Context;
import android.content.Intent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class ScreenOffScreenSaver extends ScreenSaver {
    @Override
    public void onStart(Context context) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(INTENT_TURN_SCREEN_OFF));
    }

    @Override
    public void onEnd(Context context) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(INTENT_TURN_SCREEN_ON));
    }

    @Override
    public String getName() {
        return "Screen Off";
    }
}
