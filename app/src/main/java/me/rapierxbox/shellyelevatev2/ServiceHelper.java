package me.rapierxbox.shellyelevatev2;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.util.function.Consumer;

public class ServiceHelper {
    static <T> void getHAIP(Context context, Consumer<T> action) {
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
                            String ipAddress = serviceInfo.getHost().getHostAddress();
                            Log.i("discovery", "Home Assistant IP Address: " + ipAddress);
                            action.accept((T) ipAddress);
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


}
