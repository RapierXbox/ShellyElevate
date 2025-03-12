package me.rapierxbox.shellyelevatev2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Objects;


public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if(Objects.equals(intent.getAction(), Intent.ACTION_BOOT_COMPLETED)){
            Log.i("ShellyElevateV2", "Starting... (If not already started)");

            Intent appIntent = new Intent(context, ShellyElevateApplication.class);
            context.startService(appIntent);
        }
    }
}
