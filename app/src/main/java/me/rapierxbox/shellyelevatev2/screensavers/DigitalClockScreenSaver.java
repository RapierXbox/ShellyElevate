package me.rapierxbox.shellyelevatev2.screensavers;

import android.content.Context;
import android.content.Intent;

import me.rapierxbox.shellyelevatev2.screensavers.activities.DigitalClockAndDateScreenSaverActivity;

// one activity serves both clock savers and hides the date row when not wanted
public class DigitalClockScreenSaver extends ScreenSaver {
    private final boolean showDate;

    public DigitalClockScreenSaver(boolean showDate) {
        this.showDate = showDate;
    }

    @Override
    public void onStart(Context context) {
        Intent intent = new Intent(context, DigitalClockAndDateScreenSaverActivity.class);
        intent.putExtra("date", showDate);
        // NEW_TASK is required since appContext (not an Activity) starts this
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    public void onEnd(Context context) {
    }

    @Override
    public String getName() {
        return showDate ? "Digital Clock and Date" : "Digital Clock";
    }
}
