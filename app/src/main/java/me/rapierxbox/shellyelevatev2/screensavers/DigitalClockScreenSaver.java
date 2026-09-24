package me.rapierxbox.shellyelevatev2.screensavers;

import android.content.Context;
import android.content.Intent;

import me.rapierxbox.shellyelevatev2.screensavers.activities.DigitalClockAndDateScreenSaverActivity;

// clock-only saver that reuses the date activity with the date row hidden
public class DigitalClockScreenSaver extends ScreenSaver {

    @Override
    public void onStart(Context context) {
        Intent intent = new Intent(context, DigitalClockAndDateScreenSaverActivity.class);
        intent.putExtra("date", false);
        // NEW_TASK is required since appContext (not an Activity) starts this
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    public void onEnd(Context context) {
    }

    @Override
    public String getName() {
        return "Digital Clock";
    }
}
