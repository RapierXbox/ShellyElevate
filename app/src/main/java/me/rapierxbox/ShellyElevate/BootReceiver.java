package me.rapierxbox.ShellyElevate;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.legacy.content.WakefulBroadcastReceiver;

public class BootReceiver extends WakefulBroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i("autostart", "Starting HTTP Server");
            Intent serviceIntent = new Intent(context, HttpServer.class);
            startWakefulService(context, serviceIntent);

            Log.i("autostart", "Starting Chrome");
            new ChromeHelper(context).execute();
        }
    }
}
