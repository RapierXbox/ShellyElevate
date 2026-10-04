package me.rapierxbox.shellyelevatev2.helper;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// scans for wifi networks and connects through the legacy wifimanager api
// android 7 only returns scan results with a location permission and location mode on
@SuppressWarnings("deprecation")
public final class WifiNetworkManager {
    private static final String TAG = "WifiNetworkManager";

    private static final long CONNECT_TIMEOUT_MS = 30_000;
    // repeated handshake drops mean a wrong key even when no auth error is reported
    private static final int MAX_HANDSHAKE_FAILURES = 2;
    // signature|privileged so only granted when the /system copy requests it
    public static final String PERMISSION_OVERRIDE_WIFI_CONFIG = "android.permission.OVERRIDE_WIFI_CONFIG";

    public static final int FAIL_AUTH = 1;
    public static final int FAIL_TIMEOUT = 2;
    public static final int FAIL_ERROR = 3;

    public enum Security { OPEN, WEP, PSK, EAP, UNSUPPORTED }

    public static final class Network {
        public final String ssid;
        public final int rssi;
        public final int frequency;
        public final Security security;
        public final String capabilities;
        public int savedNetworkId = -1;
        public boolean connected;

        Network(String ssid, int rssi, int frequency, Security security, String capabilities) {
            this.ssid = ssid;
            this.rssi = rssi;
            this.frequency = frequency;
            this.security = security;
            this.capabilities = capabilities != null ? capabilities : "";
        }

        public boolean isSaved() {
            return savedNetworkId != -1;
        }

        public boolean isSupported() {
            return security != Security.EAP && security != Security.UNSUPPORTED;
        }

        public int signalLevel() {
            return WifiManager.calculateSignalLevel(rssi, 5);
        }

        // wpa and wpa2 flavor of a psk network
        public String pskLabel() {
            boolean wpa = capabilities.contains("[WPA-");
            boolean wpa2 = capabilities.contains("[WPA2-") || capabilities.contains("[RSN-");
            if (wpa && wpa2) return "WPA/WPA2";
            return wpa ? "WPA" : "WPA2";
        }
    }

    public static final class SavedNetwork {
        public final int networkId;
        public final String ssid;
        public final Security security;

        SavedNetwork(int networkId, String ssid, Security security) {
            this.networkId = networkId;
            this.ssid = ssid;
            this.security = security;
        }
    }

    public static final class Current {
        public final String ssid;
        public final int networkId;
        public final int rssi;
        public final int frequency;
        @Nullable public final String ip;

        Current(String ssid, int networkId, int rssi, int frequency, @Nullable String ip) {
            this.ssid = ssid;
            this.networkId = networkId;
            this.rssi = rssi;
            this.frequency = frequency;
            this.ip = ip;
        }

        public int signalLevel() {
            return WifiManager.calculateSignalLevel(rssi, 5);
        }
    }

    // all callbacks run on the main thread
    public interface ConnectListener {
        void onProgress(SupplicantState state);
        void onConnected(String ssid, @Nullable String ip);
        void onFailed(int reason);
    }

    private static final class Attempt {
        final String ssid;
        @Nullable ConnectListener listener;
        volatile int targetNetId = -1;
        // only set when this attempt added the config so a failure can drop it again
        volatile int createdNetId = -1;
        int previousNetId = -1;
        SupplicantState lastState;
        int handshakeFailures;
        boolean done;
        BroadcastReceiver receiver;
        Runnable timeout;

        Attempt(String ssid, ConnectListener listener) {
            this.ssid = ssid;
            this.listener = listener;
        }
    }

    private final Context ctx;
    @Nullable private final WifiManager wifi;
    private final Handler main = new Handler(Looper.getMainLooper());
    // wifimanager calls are blocking binder round trips into the state machine
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    @Nullable private Attempt attempt;

    public WifiNetworkManager(Context context) {
        ctx = context.getApplicationContext();
        wifi = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
    }

    public boolean isAvailable() {
        return wifi != null;
    }

    public boolean hasLocationPermission() {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasOverridePermission() {
        return ctx.checkSelfPermission(PERMISSION_OVERRIDE_WIFI_CONFIG) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isLocationEnabled() {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Exception e) {
            return true;
        }
    }

    public boolean isWifiEnabled() {
        try {
            return wifi != null && wifi.isWifiEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    public boolean isConnecting() {
        return attempt != null;
    }

    // turns wifi on first since the state machine scans by itself once it is up
    public boolean startScan() {
        if (wifi == null) return false;
        try {
            if (!wifi.isWifiEnabled()) return wifi.setWifiEnabled(true);
            return wifi.startScan();
        } catch (SecurityException e) {
            Log.w(TAG, "scan not allowed", e);
            return false;
        }
    }

    // one entry per ssid with the strongest bssid and the connected network first
    public List<Network> getNetworks() {
        List<ScanResult> results = null;
        if (wifi != null) {
            try {
                results = wifi.getScanResults();
            } catch (SecurityException e) {
                Log.w(TAG, "scan results not allowed", e);
            }
        }
        if (results == null) return new ArrayList<>();

        Map<String, ScanResult> strongest = new HashMap<>();
        for (ScanResult r : results) {
            if (isBlankSsid(r.SSID)) continue;
            ScanResult prev = strongest.get(r.SSID);
            if (prev == null || r.level > prev.level) strongest.put(r.SSID, r);
        }

        List<SavedNetwork> saved = getSavedNetworks();
        Current current = getCurrent();
        List<Network> out = new ArrayList<>();
        for (ScanResult r : strongest.values()) {
            Network n = new Network(r.SSID, r.level, r.frequency, securityOf(r.capabilities), r.capabilities);
            if (current != null && current.ssid.equals(n.ssid)) {
                n.connected = true;
                n.savedNetworkId = current.networkId;
            } else {
                SavedNetwork s = findSaved(saved, n.ssid, n.security);
                if (s != null) n.savedNetworkId = s.networkId;
            }
            out.add(n);
        }
        Collections.sort(out, (a, b) -> {
            if (a.connected != b.connected) return a.connected ? -1 : 1;
            return Integer.compare(b.rssi, a.rssi);
        });
        return out;
    }

    public List<SavedNetwork> getSavedNetworks() {
        List<SavedNetwork> out = new ArrayList<>();
        if (wifi == null) return out;
        List<WifiConfiguration> configs;
        try {
            configs = wifi.getConfiguredNetworks();
        } catch (SecurityException e) {
            Log.w(TAG, "configured networks not allowed", e);
            return out;
        }
        if (configs == null) return out;
        for (WifiConfiguration c : configs) {
            out.add(new SavedNetwork(c.networkId, unquote(c.SSID), securityOf(c)));
        }
        return out;
    }

    @Nullable
    public Current getCurrent() {
        WifiInfo info = connectionInfo();
        if (info == null || info.getNetworkId() == -1
                || info.getSupplicantState() != SupplicantState.COMPLETED) return null;
        return new Current(unquote(info.getSSID()), info.getNetworkId(), info.getRssi(),
                info.getFrequency(), ipOf(info));
    }

    public void connectSaved(int netId, String ssid, ConnectListener listener) {
        Attempt a = begin(ssid, listener);
        a.targetNetId = netId;
        WORKER.execute(() -> {
            if (!enable(netId)) main.post(() -> fail(a, FAIL_ERROR));
        });
    }

    public void connectNew(String ssid, Security security, String password, boolean hidden, ConnectListener listener) {
        Attempt a = begin(ssid, listener);
        WifiConfiguration config = buildConfig(ssid, security, password, hidden);
        WORKER.execute(() -> {
            Set<Integer> existing = new HashSet<>();
            for (SavedNetwork s : getSavedNetworks()) existing.add(s.networkId);
            int id;
            try {
                // a config with the same ssid and security gets updated in place
                id = wifi != null ? wifi.addNetwork(config) : -1;
            } catch (SecurityException e) {
                Log.w(TAG, "add network not allowed", e);
                id = -1;
            }
            if (id == -1) {
                main.post(() -> fail(a, FAIL_ERROR));
                return;
            }
            if (!existing.contains(id)) a.createdNetId = id;
            a.targetNetId = id;
            if (!enable(id)) main.post(() -> fail(a, FAIL_ERROR));
        });
    }

    // fails for configs created by another app unless OVERRIDE_WIFI_CONFIG is granted
    public boolean forget(int netId) {
        if (wifi == null) return false;
        try {
            if (!wifi.removeNetwork(netId)) return false;
            wifi.saveConfiguration();
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "forget not allowed", e);
            return false;
        }
    }

    // the running attempt still finishes and cleans up but stops reporting
    public void detachListener() {
        if (attempt != null) attempt.listener = null;
    }

    private Attempt begin(String ssid, ConnectListener listener) {
        if (attempt != null) finish(attempt);
        Attempt a = new Attempt(ssid, listener);
        WifiInfo info = connectionInfo();
        a.previousNetId = info != null ? info.getNetworkId() : -1;
        a.receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // both actions are sticky and the cached copy can carry an old auth error
                if (isInitialStickyBroadcast()) return;
                handleEvent(a, intent);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        ContextCompat.registerReceiver(ctx, a.receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        a.timeout = () -> fail(a, FAIL_TIMEOUT);
        main.postDelayed(a.timeout, CONNECT_TIMEOUT_MS);
        attempt = a;
        return a;
    }

    private void handleEvent(Attempt a, Intent intent) {
        if (a.done || a.targetNetId == -1) return;
        String action = intent.getAction();
        if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(action)) {
            if (intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0) == WifiManager.ERROR_AUTHENTICATING) {
                fail(a, FAIL_AUTH);
                return;
            }
            SupplicantState state = intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE);
            if (state == null) return;
            if (state == SupplicantState.DISCONNECTED && a.lastState == SupplicantState.FOUR_WAY_HANDSHAKE
                    && ++a.handshakeFailures >= MAX_HANDSHAKE_FAILURES) {
                fail(a, FAIL_AUTH);
                return;
            }
            a.lastState = state;
            if (a.listener != null) a.listener.onProgress(state);
        } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
            NetworkInfo ni = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            if (ni == null || !ni.isConnected()) return;
            WifiInfo info = connectionInfo();
            if (info == null || info.getNetworkId() != a.targetNetId) return;
            succeed(a, ipOf(info));
        }
    }

    private void succeed(Attempt a, @Nullable String ip) {
        ConnectListener listener = a.listener;
        finish(a);
        if (a.createdNetId != -1) {
            WORKER.execute(() -> {
                try {
                    if (wifi != null) wifi.saveConfiguration();
                } catch (SecurityException e) {
                    Log.w(TAG, "save configuration not allowed", e);
                }
            });
        }
        Log.i(TAG, "connected to " + a.ssid + " ip=" + ip);
        if (listener != null) listener.onConnected(a.ssid, ip);
    }

    private void fail(Attempt a, int reason) {
        if (a.done) return;
        ConnectListener listener = a.listener;
        finish(a);
        Log.w(TAG, "connecting to " + a.ssid + " failed reason=" + reason);
        int created = a.createdNetId;
        int previous = a.previousNetId;
        int target = a.targetNetId;
        WORKER.execute(() -> {
            if (wifi == null) return;
            try {
                // drop an unsaved config so the next attempt asks for the password again
                if (created != -1) wifi.removeNetwork(created);
                // go back so a remote session can come back
                if (previous != -1 && previous != target && wifi.enableNetwork(previous, true)) wifi.reconnect();
            } catch (SecurityException e) {
                Log.w(TAG, "cleanup after failure not allowed", e);
            }
        });
        if (listener != null) listener.onFailed(reason);
    }

    private void finish(Attempt a) {
        a.done = true;
        main.removeCallbacks(a.timeout);
        try {
            ctx.unregisterReceiver(a.receiver);
        } catch (IllegalArgumentException ignored) {}
        if (attempt == a) attempt = null;
    }

    private boolean enable(int netId) {
        if (wifi == null) return false;
        try {
            if (!wifi.enableNetwork(netId, true)) return false;
            wifi.reconnect();
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "enable network not allowed", e);
            return false;
        }
    }

    @Nullable
    private WifiInfo connectionInfo() {
        try {
            return wifi != null ? wifi.getConnectionInfo() : null;
        } catch (SecurityException e) {
            return null;
        }
    }

    public static boolean isValidPassword(Security security, String password) {
        int len = password.length();
        switch (security) {
            case PSK:
                return (len >= 8 && len <= 63) || (len == 64 && isHex(password));
            case WEP:
                return len == 5 || len == 13 || len == 16 || len == 29
                        || ((len == 10 || len == 26 || len == 32 || len == 58) && isHex(password));
            default:
                return true;
        }
    }

    public static boolean isValidSsid(String ssid) {
        return !ssid.isEmpty() && ssid.getBytes(StandardCharsets.UTF_8).length <= 32;
    }

    private static WifiConfiguration buildConfig(String ssid, Security security, String password, boolean hidden) {
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = quote(ssid);
        c.hiddenSSID = hidden;
        switch (security) {
            case PSK:
                c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                c.preSharedKey = password.length() == 64 && isHex(password) ? password : quote(password);
                break;
            case WEP:
                c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
                int len = password.length();
                boolean hex = (len == 10 || len == 26 || len == 32 || len == 58) && isHex(password);
                c.wepKeys[0] = hex ? password : quote(password);
                c.wepTxKeyIndex = 0;
                break;
            default:
                c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                break;
        }
        return c;
    }

    // psk wins over the rest since mixed mode networks still accept it
    static Security securityOf(String capabilities) {
        if (capabilities == null) return Security.OPEN;
        if (capabilities.contains("PSK")) return Security.PSK;
        if (capabilities.contains("EAP")) return Security.EAP;
        if (capabilities.contains("WEP")) return Security.WEP;
        // sae owe and unknown key management cannot be configured on android 7
        if (capabilities.contains("WPA") || capabilities.contains("RSN")) return Security.UNSUPPORTED;
        return Security.OPEN;
    }

    static Security securityOf(WifiConfiguration c) {
        if (c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) return Security.PSK;
        if (c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                || c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) return Security.EAP;
        if (c.wepKeys != null && c.wepKeys[0] != null) return Security.WEP;
        return Security.OPEN;
    }

    @Nullable
    private static SavedNetwork findSaved(List<SavedNetwork> saved, String ssid, Security security) {
        SavedNetwork any = null;
        for (SavedNetwork s : saved) {
            if (!s.ssid.equals(ssid)) continue;
            if (s.security == security) return s;
            any = s;
        }
        // unknown key management cannot be compared so any saved entry will do
        return security == Security.UNSUPPORTED ? any : null;
    }

    private static boolean isBlankSsid(String ssid) {
        return ssid == null || ssid.replace("\u0000", "").trim().isEmpty();
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static String unquote(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return !s.isEmpty();
    }

    @Nullable
    private static String ipOf(WifiInfo info) {
        int ip = info.getIpAddress();
        if (ip != 0) {
            return String.format(Locale.US, "%d.%d.%d.%d",
                    ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >>> 24) & 0xff);
        }
        // the wifiinfo address can lag behind dhcp so ask the interface
        try {
            NetworkInterface nif = NetworkInterface.getByName("wlan0");
            if (nif == null) return null;
            for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                if (addr instanceof Inet4Address) return addr.getHostAddress();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
