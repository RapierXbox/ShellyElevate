package me.rapierxbox.shellyelevatev2.screensavers;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;

import android.content.Context;

public class ScreenOffScreenSaver extends ScreenSaver{
    @Override
    public void onStart(Context context) {
        mDeviceHelper.setScreenOn(false);
    }

    @Override
    public void onEnd(Context context) {
        mDeviceHelper.setScreenOn(true);
    }

    @Override
    public String getName() {
        return "Screen Off";
    }
}
