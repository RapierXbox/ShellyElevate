package me.rapierxbox.shellyelevatev2.screensavers;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_AOD_STARTED;
import static me.rapierxbox.shellyelevatev2.Constants.INTENT_AOD_STOPPED;

import android.content.Context;

// always-on-display saver
// ScreenManager reacts to the broadcasts by pinning brightness to the panel
// minimum instead of blanking the screen
public class AODScreenSaver extends ScreenSaver {
    @Override
    public void onStart(Context context) {
        sendOnMainThread(context, INTENT_AOD_STARTED);
    }

    @Override
    public void onEnd(Context context) {
        sendOnMainThread(context, INTENT_AOD_STOPPED);
    }

    @Override
    public String getName() {
        return "Always On Display";
    }
}
