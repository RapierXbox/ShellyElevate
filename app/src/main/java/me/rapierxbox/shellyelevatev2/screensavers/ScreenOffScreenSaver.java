package me.rapierxbox.shellyelevatev2.screensavers;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_OFF;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_ON;

import android.content.Context;

// blanks the panel entirely instead of showing a saver visual
public class ScreenOffScreenSaver extends ScreenSaver {
    @Override
    public void onStart(Context context) {
        sendOnMainThread(context, INTENT_TURN_SCREEN_OFF);
    }

    @Override
    public void onEnd(Context context) {
        sendOnMainThread(context, INTENT_TURN_SCREEN_ON);
    }

    @Override
    public String getName() {
        return "Screen Off";
    }
}
