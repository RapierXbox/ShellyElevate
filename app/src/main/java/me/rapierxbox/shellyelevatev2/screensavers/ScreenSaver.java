package me.rapierxbox.shellyelevatev2.screensavers;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

// base type for the pluggable screensavers selected via SP_SCREEN_SAVER_ID
public abstract class ScreenSaver {

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public abstract void onStart(Context context);
    public abstract void onEnd(Context context);
    public abstract String getName();

    // posts the broadcast from the main looper so a saver started from a background
    // thread returns immediately instead of blocking on synchronous receiver dispatch
    protected static void sendOnMainThread(Context context, String action) {
        MAIN_HANDLER.post(() -> LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(action)));
    }
}
