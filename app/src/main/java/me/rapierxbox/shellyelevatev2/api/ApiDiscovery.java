package me.rapierxbox.shellyelevatev2.api;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.DeviceModel;

// announces _shellyelevate._tcp with the txt record of protocol-v1 section 1
// the record carries no fingerprint since mdns is unauthenticated
final class ApiDiscovery {
    private static final String TAG = "ApiDiscovery";
    static final String SERVICE_TYPE = "_shellyelevate._tcp";

    private final NsdManager nsd;
    private NsdManager.RegistrationListener listener;
    private boolean registeredPaired;

    ApiDiscovery(Context context) {
        nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
    }

    synchronized void register(boolean paired) {
        if (nsd == null) return;
        if (listener != null) {
            if (registeredPaired == paired) return;
            unregister();
        }
        NsdServiceInfo info = new NsdServiceInfo();
        String id = ApiInfo.deviceId();
        info.setServiceName(id);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(ApiInfo.TLS_PORT);
        DeviceModel device = DeviceModel.getReportedDevice();
        info.setAttribute("id", id);
        info.setAttribute("mac", ApiInfo.mac());
        info.setAttribute("model", device.sku);
        info.setAttribute("codename", device.name());
        info.setAttribute("name", ApiInfo.name());
        info.setAttribute("fw", BuildConfig.VERSION_NAME);
        info.setAttribute("api", ApiInfo.API_VERSION);
        info.setAttribute("paired", paired ? "1" : "0");

        NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "mDNS registration failed: " + errorCode);
                synchronized (ApiDiscovery.this) {
                    if (listener == this) listener = null;
                }
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "mDNS unregistration failed: " + errorCode);
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "Announced " + serviceInfo.getServiceName());
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
            listener = l;
            registeredPaired = paired;
        } catch (RuntimeException e) {
            Log.w(TAG, "mDNS registration rejected", e);
        }
    }

    synchronized void unregister() {
        if (nsd == null || listener == null) return;
        try {
            nsd.unregisterService(listener);
        } catch (RuntimeException ignored) {
            // never registered
        }
        listener = null;
    }
}
