package me.rapierxbox.ShellyElevate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

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

    public static String getIPFromHostname(String hostname) {
        long startTime = System.currentTimeMillis();
        long maxWaitTime = 60000;

        while (System.currentTimeMillis() - startTime < maxWaitTime) {
            try {
                InetAddress address = InetAddress.getByName("8.8.8.8");
                if (address.isReachable(2000)) {
                    try (JmDNS jmdns = JmDNS.create()) {
                        ServiceInfo[] services = jmdns.list("_http._tcp.local.");
                        for (ServiceInfo serviceInfo : services) {
                            if (serviceInfo.getName().equalsIgnoreCase(hostname)) {
                                InetAddress[] addresses = serviceInfo.getInetAddresses();
                                if (addresses.length > 0) {
                                    return addresses[0].getHostAddress();
                                }
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        return "homeassistant.local";
                    }
                } else {
                    System.out.println("homeassistant.local");
                    Thread.sleep(5000);
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                return "homeassistant.local";
            }
        }

        return "homeassistant.local";
    }
}
