package me.rapierxbox.shellyelevatev2;

import static android.content.Context.MODE_PRIVATE;
import static me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME;
import static me.rapierxbox.shellyelevatev2.Constants.SP_LITE_MODE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Objects;


public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Objects.equals(intent.getAction(), Intent.ACTION_BOOT_COMPLETED)) {
            Log.i("ShellyElevateV2", "Starting... (If not already started)");

            if (context.getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE).getBoolean(SP_LITE_MODE, false)) {
                Intent appIntent = new Intent(context, ShellyElevateApplication.class);
                context.startService(appIntent);
            } else {
                Log.i("ShellyElevateV2", "Starting MainActivity");
                Intent activityIntent = new Intent(context, MainActivity.class);
                context.startActivity(activityIntent);
            }
        }
    }
}
