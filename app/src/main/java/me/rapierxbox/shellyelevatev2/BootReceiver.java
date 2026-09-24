package me.rapierxbox.shellyelevatev2;

import static me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME;
import static me.rapierxbox.shellyelevatev2.Constants.SP_LITE_MODE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import me.rapierxbox.shellyelevatev2.helper.ServiceHelper;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received intent: " + action);

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;

        Log.i(TAG, "Starting... (If not already started)");
        ServiceHelper.ensureKioskService(context);

        SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(SP_LITE_MODE, false)) {
            Log.i(TAG, "Lite mode enabled, skipping MainActivity");
            return;
        }

        Log.i(TAG, "Starting MainActivity");
        Intent activityIntent = new Intent(context, MainActivity.class);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(activityIntent);
    }
}
