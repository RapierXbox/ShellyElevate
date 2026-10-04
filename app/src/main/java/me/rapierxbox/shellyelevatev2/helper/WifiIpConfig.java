package me.rapierxbox.shellyelevatev2.helper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import me.rapierxbox.shellyelevatev2.R;

// reads and changes the ip configuration of the connected wifi network
// static ip goes through hidden wifi apis which are fine on android 7
public final class WifiIpConfig {
    private static final String TAG = "WifiIpConfig";
    // signature|privileged so only the /system copy of the app gets it
    public static final String PERMISSION_OVERRIDE_WIFI_CONFIG = "android.permission.OVERRIDE_WIFI_CONFIG";
    private static final long SAVE_TIMEOUT_MS = 10_000;

    public static final int FIELD_IP = 0;
    public static final int FIELD_PREFIX = 1;
    public static final int FIELD_GATEWAY = 2;
    public static final int FIELD_DNS1 = 3;
    public static final int FIELD_DNS2 = 4;

    private WifiIpConfig() {}

    public static final class State {
        public boolean connected;
        @Nullable public String ssid;
        public int networkId = -1;
        // live values from the link
        @Nullable public String ipAddress;
        public int prefixLength = -1;
        @Nullable public String gateway;
        public final List<String> dns = new ArrayList<>();
        // stored values from the network config
        public boolean configFound;
        public boolean isStatic;
        @Nullable public String staticIp;
        public int staticPrefix = -1;
        @Nullable public String staticGateway;
        public final List<String> staticDns = new ArrayList<>();
        // permission to change the config of this network
        public boolean permitted;
    }

    public static final class StaticConfig {
        public final Inet4Address ip;
        public final int prefix;
        public final Inet4Address gateway;
        public final List<Inet4Address> dns;

        StaticConfig(Inet4Address ip, int prefix, Inet4Address gateway, List<Inet4Address> dns) {
            this.ip = ip;
            this.prefix = prefix;
            this.gateway = gateway;
            this.dns = dns;
        }
    }

    // either config or field plus error string res is set
    public static final class ParseResult {
        @Nullable public final StaticConfig config;
        public final int field;
        public final int error;

        ParseResult(@Nullable StaticConfig config, int field, int error) {
            this.config = config;
            this.field = field;
            this.error = error;
        }
    }

    // callbacks fire on the main thread
    public interface ApplyListener {
        void onApplied();
        void onFailed(String reason);
    }

    public static boolean hasOverridePermission(Context ctx) {
        return ctx.checkSelfPermission(PERMISSION_OVERRIDE_WIFI_CONFIG) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean hasChangePermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    // does binder calls so keep it off the main thread
    public static State read(Context ctx) {
        Context app = ctx.getApplicationContext();
        State s = new State();
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return s;

        WifiInfo info = wm.getConnectionInfo();
        if (info != null && info.getNetworkId() != -1) {
            s.networkId = info.getNetworkId();
            s.ssid = stripQuotes(info.getSSID());
        }

        readLink(app, s);

        WifiConfiguration cfg = findConfig(wm, s.networkId);
        int creatorUid = -1;
        if (cfg != null) {
            s.configFound = true;
            if (s.ssid == null) s.ssid = stripQuotes(cfg.SSID);
            readStoredIp(cfg, s);
            creatorUid = getCreatorUid(cfg);
        }
        // the framework also lets the creator of a network edit it
        s.permitted = hasChangePermission(app) && (hasOverridePermission(app) || creatorUid == Process.myUid());
        return s;
    }

    private static void readLink(Context app, State s) {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (la.getAddress() instanceof Inet4Address) {
                        s.ipAddress = la.getAddress().getHostAddress();
                        s.prefixLength = la.getPrefixLength();
                        break;
                    }
                }
                for (RouteInfo r : lp.getRoutes()) {
                    if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) {
                        s.gateway = r.getGateway().getHostAddress();
                        break;
                    }
                }
                for (InetAddress d : lp.getDnsServers()) s.dns.add(d.getHostAddress());
                s.connected = s.ipAddress != null;
                if (s.connected) return;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot read link properties", e);
        }
    }

    @Nullable
    private static WifiConfiguration findConfig(WifiManager wm, int networkId) {
        if (networkId == -1) return null;
        try {
            List<WifiConfiguration> list = wm.getConfiguredNetworks();
            if (list == null) return null;
            for (WifiConfiguration c : list) if (c.networkId == networkId) return c;
        } catch (SecurityException e) {
            // android 10 and later also want location here
            Log.w(TAG, "not allowed to list configured networks", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot list configured networks", e);
        }
        return null;
    }

    private static void readStoredIp(WifiConfiguration cfg, State s) {
        try {
            Object assignment = WifiConfiguration.class.getMethod("getIpAssignment").invoke(cfg);
            s.isStatic = assignment != null && "STATIC".equals(((Enum<?>) assignment).name());
            Object sic = WifiConfiguration.class.getMethod("getStaticIpConfiguration").invoke(cfg);
            if (sic == null) return;
            Class<?> sicClass = sic.getClass();
            Object la = sicClass.getField("ipAddress").get(sic);
            if (la instanceof LinkAddress) {
                s.staticIp = ((LinkAddress) la).getAddress().getHostAddress();
                s.staticPrefix = ((LinkAddress) la).getPrefixLength();
            }
            Object gw = sicClass.getField("gateway").get(sic);
            if (gw instanceof InetAddress) s.staticGateway = ((InetAddress) gw).getHostAddress();
            Object dns = sicClass.getField("dnsServers").get(sic);
            if (dns instanceof List) {
                for (Object d : (List<?>) dns) {
                    if (d instanceof InetAddress) s.staticDns.add(((InetAddress) d).getHostAddress());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "cannot read stored ip config", e);
        }
    }

    private static int getCreatorUid(WifiConfiguration cfg) {
        try {
            return WifiConfiguration.class.getField("creatorUid").getInt(cfg);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    // ---- validation ----

    public static ParseResult parseStatic(String ipText, String prefixText, String gatewayText, String dns1Text, String dns2Text) {
        Inet4Address ip = parseIpv4(ipText);
        if (ip == null || !isUsableHost(ip)) return error(FIELD_IP, R.string.wifi_ip_error_ip);

        int prefix = parsePrefix(prefixText);
        if (prefix < 0) return error(FIELD_PREFIX, R.string.wifi_ip_error_prefix);

        int ipInt = toInt(ip);
        int mask = prefixToMask(prefix);
        if (isNetworkOrBroadcast(ipInt, mask)) return error(FIELD_IP, R.string.wifi_ip_error_host);

        Inet4Address gateway = parseIpv4(gatewayText);
        if (gateway == null || !isUsableHost(gateway)) return error(FIELD_GATEWAY, R.string.wifi_ip_error_gateway);
        int gwInt = toInt(gateway);
        if ((gwInt & mask) != (ipInt & mask) || isNetworkOrBroadcast(gwInt, mask)) {
            return error(FIELD_GATEWAY, R.string.wifi_ip_error_gateway_subnet);
        }
        if (gwInt == ipInt) return error(FIELD_GATEWAY, R.string.wifi_ip_error_gateway_same);

        List<Inet4Address> dns = new ArrayList<>();
        Inet4Address dns1 = parseIpv4(dns1Text);
        if (dns1 == null || !isUsableHost(dns1)) return error(FIELD_DNS1, R.string.wifi_ip_error_dns);
        dns.add(dns1);
        if (dns2Text != null && !dns2Text.trim().isEmpty()) {
            Inet4Address dns2 = parseIpv4(dns2Text);
            if (dns2 == null || !isUsableHost(dns2)) return error(FIELD_DNS2, R.string.wifi_ip_error_dns);
            dns.add(dns2);
        }
        return new ParseResult(new StaticConfig(ip, prefix, gateway, dns), -1, 0);
    }

    private static ParseResult error(int field, int res) {
        return new ParseResult(null, field, res);
    }

    // strict dotted quad without any dns lookup
    @Nullable
    public static Inet4Address parseIpv4(@Nullable String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            for (int j = 0; j < p.length(); j++) if (!Character.isDigit(p.charAt(j))) return null;
            int v = Integer.parseInt(p);
            if (v > 255) return null;
            b[i] = (byte) v;
        }
        try {
            return (Inet4Address) InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    // accepts a prefix length or a dotted netmask returns -1 when invalid
    public static int parsePrefix(@Nullable String text) {
        if (text == null) return -1;
        String t = text.trim();
        if (t.startsWith("/")) t = t.substring(1);
        int prefix;
        if (t.contains(".")) {
            Inet4Address mask = parseIpv4(t);
            if (mask == null) return -1;
            int m = toInt(mask);
            prefix = Integer.bitCount(m);
            // the mask must be contiguous ones
            if (prefixToMask(prefix) != m) return -1;
        } else {
            try {
                prefix = Integer.parseInt(t);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return prefix >= 8 && prefix <= 30 ? prefix : -1;
    }

    public static String prefixToNetmask(int prefix) {
        int m = prefixToMask(prefix);
        return ((m >>> 24) & 0xff) + "." + ((m >>> 16) & 0xff) + "." + ((m >>> 8) & 0xff) + "." + (m & 0xff);
    }

    private static int prefixToMask(int prefix) {
        return prefix <= 0 ? 0 : (int) (0xffffffffL << (32 - prefix));
    }

    private static int toInt(Inet4Address a) {
        byte[] b = a.getAddress();
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }

    private static boolean isNetworkOrBroadcast(int addr, int mask) {
        int host = addr & ~mask;
        return host == 0 || host == ~mask;
    }

    // plain unicast only no loopback link local multicast or reserved ranges
    private static boolean isUsableHost(Inet4Address a) {
        int first = a.getAddress()[0] & 0xff;
        return first != 0 && first != 127 && first < 224 && !a.isLinkLocalAddress();
    }

    // ---- apply ----

    // config null switches to dhcp
    public static void apply(Context ctx, int networkId, @Nullable StaticConfig config, ApplyListener listener) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        AtomicBoolean done = new AtomicBoolean(false);
        ApplyListener once = new ApplyListener() {
            @Override public void onApplied() {
                if (done.compareAndSet(false, true)) main.post(listener::onApplied);
            }
            @Override public void onFailed(String reason) {
                if (done.compareAndSet(false, true)) main.post(() -> listener.onFailed(reason));
            }
        };

        new Thread(() -> {
            try {
                WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                if (wm == null) { once.onFailed("Wi-Fi service unavailable"); return; }
                if (!hasChangePermission(app)) { once.onFailed("Missing permission CHANGE_WIFI_STATE"); return; }
                WifiConfiguration cfg = findConfig(wm, networkId);
                if (cfg == null) { once.onFailed("Network config not found"); return; }

                setIpConfig(cfg, config);
                clearSelectionBssid(cfg);

                main.postDelayed(() -> once.onFailed("No answer from the Wi-Fi service"), SAVE_TIMEOUT_MS);
                if (!saveHidden(wm, cfg, once)) saveFallback(wm, cfg, once);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.e(TAG, "apply failed", e);
                once.onFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "WifiIpConfig").start();
    }

    private static void setIpConfig(WifiConfiguration cfg, @Nullable StaticConfig config) throws ReflectiveOperationException {
        Class<?> assignClass = Class.forName("android.net.IpConfiguration$IpAssignment");
        Class<?> sicClass = Class.forName("android.net.StaticIpConfiguration");
        Object sic = null;
        if (config != null) {
            sic = sicClass.getConstructor().newInstance();
            Constructor<LinkAddress> la = LinkAddress.class.getConstructor(InetAddress.class, int.class);
            sicClass.getField("ipAddress").set(sic, la.newInstance(config.ip, config.prefix));
            sicClass.getField("gateway").set(sic, config.gateway);
            @SuppressWarnings("unchecked")
            List<InetAddress> dns = (List<InetAddress>) sicClass.getField("dnsServers").get(sic);
            dns.addAll(config.dns);
        }
        Object assignment = enumValue(assignClass, config != null ? "STATIC" : "DHCP");
        WifiConfiguration.class.getMethod("setIpAssignment", assignClass).invoke(cfg, assignment);
        WifiConfiguration.class.getMethod("setStaticIpConfiguration", sicClass).invoke(cfg, sic);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> cls, String name) {
        return Enum.valueOf((Class) cls, name);
    }

    // a parceled config carries the last selected bssid and saving it would pin the network to one ap
    private static void clearSelectionBssid(WifiConfiguration cfg) {
        try {
            Object status = WifiConfiguration.class.getMethod("getNetworkSelectionStatus").invoke(cfg);
            if (status != null) status.getClass().getMethod("setNetworkSelectionBSSID", String.class).invoke(status, (Object) null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "cannot clear selection bssid", e);
        }
    }

    // same path as the system settings app persists and re provisions ip on the live link
    private static boolean saveHidden(WifiManager wm, WifiConfiguration cfg, ApplyListener once) {
        Class<?> listenerClass;
        Method save;
        try {
            listenerClass = Class.forName("android.net.wifi.WifiManager$ActionListener");
            save = WifiManager.class.getMethod("save", WifiConfiguration.class, listenerClass);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "hidden save not available", e);
            return false;
        }
        int notAuthorized = 9;
        try {
            notAuthorized = WifiManager.class.getField("NOT_AUTHORIZED").getInt(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        final int notAuthorizedCode = notAuthorized;
        Object proxy = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[]{listenerClass},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "onSuccess":
                            Log.i(TAG, "ip config saved");
                            once.onApplied();
                            return null;
                        case "onFailure":
                            int reason = args != null && args.length > 0 ? (Integer) args[0] : -1;
                            Log.w(TAG, "save failed reason=" + reason);
                            once.onFailed(reason == notAuthorizedCode
                                    ? "Not authorized. Reinstall with tools/install-privapp"
                                    : "Wi-Fi service error " + reason);
                            return null;
                        case "hashCode":
                            return System.identityHashCode(p);
                        case "equals":
                            return args != null && args.length > 0 && p == args[0];
                        case "toString":
                            return "WifiIpConfig.ActionListener";
                        default:
                            return null;
                    }
                });
        try {
            save.invoke(wm, cfg, proxy);
            return true;
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Log.e(TAG, "hidden save threw", cause);
            once.onFailed(cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return true;
        }
    }

    // public api path the ip only changes after a reconnect here
    private static void saveFallback(WifiManager wm, WifiConfiguration cfg, ApplyListener once) {
        if (wm.updateNetwork(cfg) == -1) {
            once.onFailed("Not authorized. Reinstall with tools/install-privapp");
            return;
        }
        wm.saveConfiguration();
        wm.disconnect();
        wm.reconnect();
        once.onApplied();
    }

    // ---- change watching ----

    // onChange runs on the main thread
    public static ConnectivityManager.NetworkCallback watch(Context ctx, Runnable onChange) {
        Handler main = new Handler(Looper.getMainLooper());
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { main.post(onChange); }
            @Override public void onLost(Network network) { main.post(onChange); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties lp) { main.post(onChange); }
        };
        ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            NetworkRequest req = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            cm.registerNetworkCallback(req, cb);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot watch wifi changes", e);
        }
        return cb;
    }

    public static void unwatch(Context ctx, ConnectivityManager.NetworkCallback cb) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            cm.unregisterNetworkCallback(cb);
        } catch (RuntimeException ignored) {
            // not registered
        }
    }

    @Nullable
    private static String stripQuotes(@Nullable String ssid) {
        if (ssid == null || ssid.equals("<unknown ssid>")) return null;
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) return ssid.substring(1, ssid.length() - 1);
        return ssid;
    }
}
