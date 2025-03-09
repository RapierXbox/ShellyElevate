package me.rapierxbox.shellyelevatev2.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.util.function.Consumer;

public class ServiceHelper {
    public static void getHAURL(Context context, Consumer<String> action) {
        NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e("discovery", "Discovery failed: Error code: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e("discovery", "Discovery failed to stop: Error code: " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i("discovery", "Service discovery started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i("discovery", "Service discovery stopped");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo.getServiceType().equals("_home-assistant._tcp.")) {
                    Log.i("discovery", "Found Home Assistant service: " + serviceInfo.getServiceName());

                    NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            Log.e("discovery", "Resolve failed: Error code: " + errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            Log.i("discovery", "Service resolved: " + serviceInfo);
                            String url = "http://" + serviceInfo.getHost().getHostAddress() + ":" + serviceInfo.getPort();
                            Log.i("discovery", "Home Assistant URL: " + url);
                            action.accept(url);
                        }
                    };

                    nsdManager.resolveService(serviceInfo, resolveListener);

                    nsdManager.stopServiceDiscovery(this);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.i("discovery", "Service lost: " + serviceInfo);
            }
        };

        nsdManager.discoverServices("_home-assistant._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    public static String getWebviewUrl(SharedPreferences sP) {
        if (sP.contains("homeAssistantIp")) {
            sP.edit()
                    .putString("webviewUrl", "http://" + sP.getString("homeAssistantIp", "") + ":8123")
                    .remove("homeAssistantIp")
                    .apply();
        }
        return sP.getString("webviewUrl", "");
    }
}
