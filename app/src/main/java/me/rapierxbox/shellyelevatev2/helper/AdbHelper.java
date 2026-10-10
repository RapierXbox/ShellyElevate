package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import me.rapierxbox.shellyelevatev2.Constants;

// toggles adb over wifi by setting the adb tcp port props and restarting adbd
// adbd reads service.adb.tcp.port and then persist.adb.tcp.port when it starts
// the stop and start tools refuse anyone but root so adbd is restarted by bouncing
// adb_enabled (needs WRITE_SECURE_SETTINGS) or through init with ctl.restart
public final class AdbHelper {
    private static final String TAG = "AdbHelper";
    private static final String SERVICE_PORT_PROP = "service.adb.tcp.port";
    private static final String PERSIST_PORT_PROP = "persist.adb.tcp.port";
    public static final int ADB_WIFI_PORT = 5555;
    private static final String PORT_ON = String.valueOf(ADB_WIFI_PORT);
    // adbd only listens on a port above 0 and the service prop wins over the persist one
    private static final String PORT_OFF = "-1";
    private static final long STOP_TIMEOUT_MS = 3_000L;
    private static final long LISTEN_TIMEOUT_MS = 8_000L;
    private static final long POLL_MS = 250L;

    public static final String ERROR_NO_PORT =
            "adb over wifi needs the install script or the home assistant installer once";
    public static final String ERROR_PORT_LOCKED =
            "the adb port cannot be changed by the app on this firmware";
    public static final String ERROR_NO_RESTART =
            "the port is set but adbd could not be restarted. it applies after a reboot";

    // shell work must stay off the main thread
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    // self heal so an external adb root or an adbd crash cannot strand wifi adb until a reboot
    private static final long WATCHDOG_PERIOD_S = 60L;
    private static ScheduledExecutorService watchdog;

    // what was last asked for. null until the boot sync ran
    private static volatile Boolean lastRequested;
    private static volatile Boolean rootAvailable;

    public static final class State {
        // adbd listens on the wifi port right now
        public final boolean enabled;
        // why the last change did not apply. null when it did
        @Nullable public final String error;

        State(boolean enabled, @Nullable String error) {
            this.enabled = enabled;
            this.error = error;
        }
    }

    private AdbHelper() {}

    // the boot sync. never turns adb off since the install script and the integration
    // enable it over adb without touching the setting
    public static void applyFromPrefs() {
        startWatchdog();
        EXEC.execute(() -> {
            try {
                if (mSharedPreferences.getBoolean(Constants.SP_ADB_WIFI_ENABLED, false)) {
                    apply(true);
                } else {
                    State state = readState();
                    lastRequested = state.enabled;
                    storePref(state.enabled);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "boot sync failed", e);
            } finally {
                // later changes must still apply even when the boot sync broke
                if (lastRequested == null) {
                    lastRequested = mSharedPreferences.getBoolean(Constants.SP_ADB_WIFI_ENABLED, false);
                }
            }
        });
    }

    // starts the self heal watchdog once. idempotent
    public static synchronized void startWatchdog() {
        if (watchdog != null) return;
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "adb-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleWithFixedDelay(AdbHelper::watchdogTick,
                WATCHDOG_PERIOD_S, WATCHDOG_PERIOD_S, TimeUnit.SECONDS);
    }

    // brings wifi adb back when it was wanted but the listener went away
    private static void watchdogTick() {
        try {
            // boot sync not done yet or wifi adb not wanted
            if (lastRequested == null
                    || !mSharedPreferences.getBoolean(Constants.SP_ADB_WIFI_ENABLED, false)) {
                return;
            }
            // only act when the listener is actually gone so it never thrashes adbd
            if (isListening()) return;
            Log.w(TAG, "wifi adb listener gone - re-asserting");
            apply(true);
        } catch (RuntimeException e) {
            Log.e(TAG, "adb watchdog tick failed", e);
        }
    }

    // runs after settings were saved or patched over the api
    public static void syncFromPrefs() {
        Boolean last = lastRequested;
        boolean wanted = mSharedPreferences.getBoolean(Constants.SP_ADB_WIFI_ENABLED, false);
        // the boot sync has not run yet or nothing changed
        if (last == null || last == wanted) return;
        EXEC.execute(() -> apply(wanted));
    }

    @WorkerThread
    public static State readState() {
        return new State(isListening(), null);
    }

    // idempotent and synchronized so the ui and the api cannot bounce adbd at the same time
    // the setting always ends up as the real state so the ui and the api never show a wish
    @WorkerThread
    public static synchronized State apply(boolean enabled) {
        State state = applyLocked(enabled);
        // the real state and not the wish or the next settings broadcast would undo a failed enable
        lastRequested = state.enabled;
        if (state.error != null) {
            Log.e(TAG, "adb over wifi " + (enabled ? "on" : "off") + " failed: " + state.error);
        } else {
            Log.i(TAG, "adb over wifi " + (state.enabled ? "on at port " + PORT_ON : "off"));
        }
        storePref(state.enabled);
        return state;
    }

    private static State applyLocked(boolean enabled) {
        String desired = enabled ? PORT_ON : PORT_OFF;
        String[] ports = readPorts();
        // persist carries the state over a reboot and the service prop wins until then
        if (portEnabled(ports[1], "") != enabled || portEnabled(ports[0], ports[1]) != enabled) {
            // works where selinux lets the app set the props like on the permissive stargate
            PrivilegedShell.runShell("setprop " + PERSIST_PORT_PROP + " " + desired
                    + "; setprop " + SERVICE_PORT_PROP + " " + desired);
            ports = readPorts();
            if (portEnabled(ports[0], ports[1]) != enabled) {
                if (applyAsRoot(desired, enabled)) return new State(enabled, null);
                return new State(isListening(), enabled ? ERROR_NO_PORT : ERROR_PORT_LOCKED);
            }
            if (portEnabled(ports[1], "") != enabled) {
                Log.w(TAG, PERSIST_PORT_PROP + " did not change so the next boot undoes this");
            }
        }
        if (isListening() == enabled) return new State(enabled, null);

        if (bounceAdbEnabled() && waitListening(enabled)) return new State(enabled, null);
        // init checks only selinux for ctl props and not the uid like the stop tool does
        PrivilegedShell.runShell("setprop ctl.restart adbd");
        if (waitListening(enabled)) return new State(enabled, null);
        if (applyAsRoot(desired, enabled)) return new State(enabled, null);
        return new State(isListening(), ERROR_NO_RESTART);
    }

    // a full restart of adbd. semicolons so start always runs even if stop returns nonzero
    private static boolean applyAsRoot(String desired, boolean enabled) {
        if (!hasRoot()) return false;
        PrivilegedShell.Result r = PrivilegedShell.run("su", "-c",
                "setprop " + PERSIST_PORT_PROP + " " + desired + "; setprop " + SERVICE_PORT_PROP + " "
                        + desired + "; stop adbd; start adbd");
        if (!r.ok()) Log.w(TAG, "su restart of adbd failed exit=" + r.exitCode + " err=" + r.stderr.trim());
        return waitListening(enabled);
    }

    // the usb service stops adbd when adb_enabled goes to 0 and starts it again on 1
    // which makes adbd read the port props again
    private static boolean bounceAdbEnabled() {
        Context ctx = mApplicationContext;
        if (ctx == null || ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        ContentResolver cr = ctx.getContentResolver();
        try {
            if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 0) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0);
                // two quick writes can reach the usb service as one so wait for adbd to go down
                waitFor(() -> !"running".equals(getprop("init.svc.adbd")), STOP_TIMEOUT_MS);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "adb_enabled could not be turned off", e);
        }
        try {
            // usb debugging stays on even when only wifi is turned off
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "adb_enabled could not be turned on", e);
            return false;
        }
    }

    private static boolean hasRoot() {
        Boolean cached = rootAvailable;
        if (cached != null) return cached;
        // the stargate su is only executable for the shell group so the app usually has none
        boolean root = PrivilegedShell.run("su", "-c", "id").stdout.contains("uid=0");
        rootAvailable = root;
        return root;
    }

    // the service port and then the persist port
    private static String[] readPorts() {
        String[] lines = PrivilegedShell.runShell(
                "getprop " + SERVICE_PORT_PROP + "; getprop " + PERSIST_PORT_PROP).stdout.split("\n", -1);
        return new String[]{lines[0], lines.length > 1 ? lines[1] : ""};
    }

    // the port adbd picks on its next start. the service prop wins while it is set
    static boolean portEnabled(String servicePort, String persistPort) {
        String port = servicePort.trim().isEmpty() ? persistPort.trim() : servicePort.trim();
        try {
            return Integer.parseInt(port) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean waitListening(boolean enabled) {
        return waitFor(() -> isListening() == enabled, LISTEN_TIMEOUT_MS);
    }

    private static boolean waitFor(BooleanSupplier done, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (done.getAsBoolean()) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    // reads the listening sockets so nothing connects to adbd. newer android hides
    // /proc/net from apps so a local connect is the fallback
    private static boolean isListening() {
        String tcp = readFile("/proc/net/tcp6") + readFile("/proc/net/tcp");
        if (!tcp.isEmpty()) return listensOn(tcp, ADB_WIFI_PORT);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", ADB_WIFI_PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // true when a /proc/net/tcp or tcp6 line shows a socket listening on the port
    static boolean listensOn(String procNet, int port) {
        String suffix = String.format(Locale.US, ":%04X", port);
        for (String line : procNet.split("\n")) {
            String[] f = line.trim().split("\\s+");
            // 0A is TCP_LISTEN
            if (f.length > 3 && f[1].endsWith(suffix) && "0A".equals(f[3])) return true;
        }
        return false;
    }

    private static String readFile(String path) {
        try (InputStream in = new FileInputStream(path)) {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
            return sb.toString();
        } catch (IOException | SecurityException e) {
            return "";
        }
    }

    private static String getprop(String name) {
        return PrivilegedShell.run("getprop", name).stdout.trim();
    }

    private static void storePref(boolean enabled) {
        if (mSharedPreferences.getBoolean(Constants.SP_ADB_WIFI_ENABLED, false) == enabled) return;
        mSharedPreferences.edit().putBoolean(Constants.SP_ADB_WIFI_ENABLED, enabled).apply();
    }
}
