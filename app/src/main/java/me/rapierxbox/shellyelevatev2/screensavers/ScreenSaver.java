package me.rapierxbox.shellyelevatev2.screensavers;

import android.content.Context;

public abstract class ScreenSaver {
    public abstract void onStart(Context context);
    public abstract void onEnd(Context context);
    public abstract String getName();
}
