package me.rapierxbox.ShellyElevate;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.AsyncTask;
import android.util.Log;

public class ChromeHelper extends AsyncTask<Void, Void, Void> {
    private Context context;
    private NsdManager mNsdManager;

    public ChromeHelper(Context context) {
        this.context = context;
        mNsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    @Override
    protected Void doInBackground(Void... voids) {
        discoverHomeAssistantService();
        return null;
    }

    public void discoverHomeAssistantService() {
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
                Log.d("discovery", "Service discovery started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d("discovery", "Service discovery stopped");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo.getServiceType().equals("_home-assistant._tcp.")) {
                    Log.d("discovery", "Found Home Assistant service: " + serviceInfo.getServiceName());
                    resolveService(serviceInfo);
                    mNsdManager.stopServiceDiscovery(this);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.d("discovery", "Service lost: " + serviceInfo);
            }
        };

        mNsdManager.discoverServices("_home-assistant._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    private void resolveService(NsdServiceInfo serviceInfo) {
        NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e("discovery", "Resolve failed: Error code: " + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                Log.d("discovery", "Service resolved: " + serviceInfo);
                String ipAddress = serviceInfo.getHost().getHostAddress();
                Log.d("discovery", "Home Assistant IP Address: " + ipAddress);
                String url = "http://" + ipAddress + ":8123";
                Uri uri = Uri.parse(url);
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                browserIntent.setPackage("com.android.chrome");
                browserIntent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 0);
                browserIntent.putExtra("android.support.customtabs.extra.TINT_ACTION_BUTTON", false);
                context.startActivity(browserIntent);

            }
        };

        mNsdManager.resolveService(serviceInfo, resolveListener);
    }
}
