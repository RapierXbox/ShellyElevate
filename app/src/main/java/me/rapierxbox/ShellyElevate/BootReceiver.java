package me.rapierxbox.ShellyElevate;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.legacy.content.WakefulBroadcastReceiver;


public class BootReceiver extends WakefulBroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, HttpServer.class);
            startWakefulService(context, serviceIntent);

            String url = "http://homeassistant.local:8123";
            Uri uri = Uri.parse(url);
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            browserIntent.setPackage("com.android.chrome");
            browserIntent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 0);
            browserIntent.putExtra("android.support.customtabs.extra.TINT_ACTION_BUTTON", false);
            context.startActivity(browserIntent);
        }
    }
}
