package me.rapierxbox.shellyelevatev2.screensavers;

import android.content.Context;
import android.content.Intent;

import me.rapierxbox.shellyelevatev2.screensavers.activities.DigitalClockAndDateScreenSaverActivity;

public class DigitalClockScreenSaver extends ScreenSaver {

    public void onStart(Context context) {
        Intent intent = new Intent(context, DigitalClockAndDateScreenSaverActivity.class);
        intent.putExtra("date", false);
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
