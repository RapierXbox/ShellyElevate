package me.rapierxbox.ShellyElevate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, HttpServer.class);
            context.startService(serviceIntent);

            String url = "http://" + getIPFromHostname("homeassistant.local") + ":8123";
            Uri uri = Uri.parse(url);
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            browserIntent.setPackage("com.android.chrome");
            browserIntent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 0);
            browserIntent.putExtra("android.support.customtabs.extra.TINT_ACTION_BUTTON", false);
            context.startActivity(browserIntent);
        }
    }

    private static String getIPFromHostname(String hostname) {
        try {
            boolean wifiConnected = waitForWiFiConnection(60_000);
            if (!wifiConnected) {return "homeassistant.local";}
            InetAddress address = InetAddress.getByName(hostname);
            return address.getHostAddress();
        } catch (UnknownHostException e) {
            e.printStackTrace();
            return "homeassistant.local";
        }
    }
    private static boolean waitForWiFiConnection(long timeoutMillis) {
        long startTime = System.currentTimeMillis();
        while (!isWiFiConnected()) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (System.currentTimeMillis() - startTime >= timeoutMillis) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWiFiConnected() {
        try {
            NetworkInterface networkInterface = NetworkInterface.getByName("wlan0");
            return networkInterface != null && networkInterface.isUp();
        } catch (Exception e) {
            return false;
        }
    }
}
