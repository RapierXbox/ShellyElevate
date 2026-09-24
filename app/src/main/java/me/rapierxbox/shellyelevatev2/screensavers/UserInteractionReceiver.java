package me.rapierxbox.shellyelevatev2.screensavers;

import static me.rapierxbox.shellyelevatev2.Constants.ACTION_USER_INTERACTION;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Objects;

// exported entry point so an external trigger like adb or automation can reset the idle timer
public class UserInteractionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Objects.equals(intent.getAction(), ACTION_USER_INTERACTION)) {
            mScreenSaverManager.onTouchEvent(null);
        }
    }
}
