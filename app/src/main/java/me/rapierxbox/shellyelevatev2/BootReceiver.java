package me.rapierxbox.shellyelevatev2;

import static android.content.Context.MODE_PRIVATE;
import static me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME;
import static me.rapierxbox.shellyelevatev2.Constants.SP_LITE_MODE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Objects;

import me.rapierxbox.shellyelevatev2.helper.ServiceHelper;


public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("BootReceiver", "Received intent: " + intent.getAction());

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i("BootReceiver", "Starting... (If not already started)");

            // Start KioskService as foreground service
            ServiceHelper.ensureKioskService(context);

            // Decide whether to start MainActivity
            SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
            boolean liteMode = prefs.getBoolean(SP_LITE_MODE, false);

            if (liteMode) {
                Log.i("BootReceiver", "Lite mode enabled, skipping MainActivity");
            } else {
                Log.i("BootReceiver", "Starting MainActivity");
                Intent activityIntent = new Intent(context, MainActivity.class);
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(activityIntent);
            }
        }
    }
}
