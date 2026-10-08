package me.rapierxbox.shellyelevatev2.bluetooth;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import me.rapierxbox.shellyelevatev2.api.ApiHub;

// shared ble scan for every consumer of advertisements
// the v1 api channel is a listener and the scan only runs while one exists
// one process wide instance from get() which is cheap since nothing starts before the first listener
// listeners get batches of at most RAW_AD_BATCH_MAX ads on the scan callback or scanner thread
// and must not block
// every public method is thread safe
public final class BleScanner {
    private static final String TAG = "BleScanner";

    private static final long SCAN_WATCHDOG_PERIOD_MS    = 15_000;
    // no ads for this long means the scan is dead
    private static final long SCAN_SILENT_RESTART_MS     = 45_000;
    // cycle long running scans before the os silently throttles them
    private static final long SCAN_PREEMPTIVE_RESTART_MS = 15 * 60 * 1000;
    // android throttles at 5 scan starts per 30s so stay one under
    private static final int  SCAN_START_BUDGET = 4;
    private static final long SCAN_START_WINDOW_MS = 30_000;
    // a scan kept warm without listeners stops after this window
    private static final long SCAN_IDLE_STOP_MS = 120_000;

    // batch ads to cut frame count and queue pressure
    private static final int  RAW_AD_BATCH_MAX = 16;
    private static final long RAW_AD_FLUSH_MS  = 100;

    // one advertisement as the consumers need it
    public static final class RawAd {
        // 0x0000AABBCCDDEEFF for AA:BB:CC:DD:EE:FF
        public final long address;
        // 0 public 1 random and other values only on api 35 and later
        public final int addressType;
        public final int rssi;
        // raw ad structures of adv and scan response or null without a scan record
        public final byte[] data;
        // the os result for consumers that need parsed fields. null in tests
        public final ScanResult source;

        public RawAd(long address, int addressType, int rssi, byte[] data, ScanResult source) {
            this.address = address;
            this.addressType = addressType;
            this.rssi = rssi;
            this.data = data;
            this.source = source;
        }
    }

    public interface Listener {
        void onAdvertisementBatch(List<RawAd> ads);
    }

    private static volatile BleScanner instance;

    public static BleScanner get() {
        BleScanner s = instance;
        if (s == null) {
            synchronized (BleScanner.class) {
                s = instance;
                if (s == null) instance = s = new BleScanner();
            }
        }
        return s;
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    // guards the scanner state below since the watchdog listeners and bt broadcasts all touch it
    private final Object scanLock = new Object();
    private ScheduledExecutorService scheduler;
    private BroadcastReceiver btStateReceiver;
    private BluetoothLeScanner bleScanner;
    private ScanCallback activeScanCb;
    private int activeScanMode = ScanSettings.SCAN_MODE_LOW_LATENCY;
    // ring of recent scan start times to stay under the os throttle
    private final long[] recentScanStarts = new long[SCAN_START_BUDGET + 1];
    private int recentScanStartsIdx = 0;
    private ScheduledFuture<?> watchdogTask;
    private ScheduledFuture<?> flushTask;
    private ScheduledFuture<?> idleStopTask;

    private final AtomicLong lastScanResultMs = new AtomicLong(0);
    private final AtomicLong lastScanStartedMs = new AtomicLong(0);

    private final List<RawAd> batch = new ArrayList<>();

    // why a running scan cannot find anything or null
    private volatile String scanBlockedReason;

    private BleScanner() {}

    public String getScanBlockedReason() {
        return scanBlockedReason;
    }

    public boolean isScanning() {
        synchronized (scanLock) {
            return activeScanCb != null;
        }
    }

    public void addListener(Listener listener) {
        synchronized (scanLock) {
            if (!listeners.addIfAbsent(listener)) return;
            cancelIdleStopLocked();
            ensureRunningLocked();
            startBleScanningLocked();
        }
    }

    // stops the scan right away once the last listener is gone
    public void removeListener(Listener listener) {
        synchronized (scanLock) {
            if (!listeners.remove(listener)) return;
            if (listeners.isEmpty()) stopAllLocked();
        }
    }

    // keeps the scan warm for a while since a quick reconnect would otherwise burn a start under the os throttle
    public void removeListenerKeepWarm(Listener listener) {
        synchronized (scanLock) {
            if (!listeners.remove(listener)) return;
            if (!listeners.isEmpty()) return;
            if (activeScanCb == null) {
                stopAllLocked();
                return;
            }
            Log.i(TAG, "no listener left, scan kept running for " + SCAN_IDLE_STOP_MS + "ms");
            scheduleIdleStopLocked();
        }
    }

    public void setLowPowerMode(boolean low) {
        int target = low ? ScanSettings.SCAN_MODE_LOW_POWER : ScanSettings.SCAN_MODE_LOW_LATENCY;
        synchronized (scanLock) {
            if (target == activeScanMode) return;
            activeScanMode = target;
            Log.i(TAG, "Scan mode -> " + (low ? "LOW_POWER" : "LOW_LATENCY"));
            if (!listeners.isEmpty() && activeScanCb != null) restartScanLocked();
        }
    }

    // ---- lifecycle ----

    // must hold scanLock
    private void ensureRunningLocked() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "BleScanner"));
        }
        if (btStateReceiver == null) {
            // the bt daemon sometimes bounces without onScanFailed so re-arm on STATE_ON
            btStateReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent i) {
                    onBluetoothStateChanged(i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR));
                }
            };
            mApplicationContext.registerReceiver(btStateReceiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        }
        try {
            if (watchdogTask == null || watchdogTask.isDone()) {
                watchdogTask = scheduler.scheduleWithFixedDelay(this::runScanWatchdog,
                        SCAN_WATCHDOG_PERIOD_MS, SCAN_WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
            }
            if (flushTask == null || flushTask.isDone()) {
                flushTask = scheduler.scheduleWithFixedDelay(this::flushBatch,
                        RAW_AD_FLUSH_MS, RAW_AD_FLUSH_MS, TimeUnit.MILLISECONDS);
            }
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "scanner tasks not scheduled: " + e.getMessage());
        }
    }

    // must hold scanLock. ends the scan and every thread and receiver it owned
    private void stopAllLocked() {
        cancelIdleStopLocked();
        boolean wasScanning = activeScanCb != null;
        stopScanLocked();
        if (wasScanning) Log.i(TAG, "BLE scan stopped");
        if (watchdogTask != null) { watchdogTask.cancel(false); watchdogTask = null; }
        if (flushTask != null) { flushTask.cancel(false); flushTask = null; }
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        if (btStateReceiver != null) {
            try {
                mApplicationContext.unregisterReceiver(btStateReceiver);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
            btStateReceiver = null;
        }
        bleScanner = null;
        synchronized (batch) {
            batch.clear();
        }
    }

    private void onBluetoothStateChanged(int state) {
        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
            Log.w(TAG, "BT adapter going down, dropping scanner state");
            synchronized (scanLock) {
                activeScanCb = null;
                bleScanner = null;
            }
        } else if (state == BluetoothAdapter.STATE_ON) {
            Log.i(TAG, "BT adapter back ON, re-arming scan if a listener needs it");
            synchronized (scanLock) {
                if (!listeners.isEmpty()) startBleScanningLocked();
            }
        }
    }

    // api 23 to 30 deliver no scan results without location permission and location mode on
    // the scan still starts and stays silent so the reason is logged and reported to the controller
    private void updateScanBlockedReason() {
        String reason = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            Context ctx = mApplicationContext;
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                reason = "location permission missing";
            } else if (Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF) == Settings.Secure.LOCATION_MODE_OFF) {
                reason = "location is off";
            }
        }
        if (Objects.equals(reason, scanBlockedReason)) return;
        scanBlockedReason = reason;
        if (reason != null) {
            Log.w(TAG, "BLE scan will find nothing: " + reason
                    + ". grant ACCESS_FINE_LOCATION and set location_mode 3 (tools/install-privapp does both)");
        }
        ApiHub.stateChanged();
    }

    // must hold scanLock
    private void scheduleIdleStopLocked() {
        cancelIdleStopLocked();
        if (scheduler == null) return;
        try {
            idleStopTask = scheduler.schedule(() -> {
                synchronized (scanLock) {
                    idleStopTask = null;
                    if (listeners.isEmpty()) {
                        Log.i(TAG, "no listener for " + SCAN_IDLE_STOP_MS + "ms, stopping idle BLE scan");
                        stopAllLocked();
                    }
                }
            }, SCAN_IDLE_STOP_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            idleStopTask = null;
        }
    }

    // must hold scanLock
    private void cancelIdleStopLocked() {
        if (idleStopTask != null) {
            idleStopTask.cancel(false);
            idleStopTask = null;
        }
    }

    // ---- scanning ----

    // low latency scans silently die on some devices so restart when quiet or running too long
    private void runScanWatchdog() {
        // an escaping exception would cancel the periodic task for good
        try {
            synchronized (scanLock) {
                checkScanHealthLocked();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "scan watchdog failed", e);
        }
    }

    // must hold scanLock
    private void checkScanHealthLocked() {
        if (listeners.isEmpty()) return;
        if (activeScanCb == null) {
            // onScanFailed probably cleared the callback so retry
            Log.w(TAG, "watchdog: scan not running while subscribed, attempting restart");
            startBleScanningLocked();
            return;
        }
        long now = System.currentTimeMillis();
        long lastResult = lastScanResultMs.get();
        long started    = lastScanStartedMs.get();
        boolean silent  = lastResult > 0 && (now - lastResult) > SCAN_SILENT_RESTART_MS;
        boolean stale   = started > 0    && (now - started)    > SCAN_PREEMPTIVE_RESTART_MS;
        if (silent || stale) {
            Log.w(TAG, "watchdog: " + (silent ? "scan silent for " + (now - lastResult) + "ms"
                                              : "scan running " + (now - started) + "ms, preemptive cycle"));
            restartScanLocked();
        }
    }

    // must hold scanLock
    @SuppressLint("MissingPermission")
    private void startBleScanningLocked() {
        if (activeScanCb != null) {
            Log.i(TAG, "BLE scan already running");
            return;
        }
        if (!canStartScanNowLocked()) {
            Log.w(TAG, "scan start rate-limited under OS 5/30s throttle, will retry on next watchdog tick");
            return;
        }

        BluetoothAdapter adapter = getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "bluetooth unavailable");
            return;
        }
        updateScanBlockedReason();
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        bleScanner = scanner;
        if (scanner == null) {
            Log.w(TAG, "LE scanner unavailable");
            return;
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(activeScanMode)
                .setReportDelay(0)
                .build();
        // set before starting so an immediate onScanFailed can clear it
        ScanCallback callback = newScanCallback();
        activeScanCb = callback;
        try {
            recordScanStartLocked();
            scanner.startScan(null, settings, callback);
        } catch (SecurityException e) {
            Log.e(TAG, "BLE scan permission denied, grant BLUETOOTH_SCAN / ACCESS_FINE_LOCATION");
            activeScanCb = null;
            return;
        } catch (RuntimeException e) {
            // the adapter can go down between the checks above and the start
            Log.w(TAG, "BLE scan start failed: " + e.getMessage());
            activeScanCb = null;
            return;
        }
        long now = System.currentTimeMillis();
        lastScanStartedMs.set(now);
        // grace period before the watchdog flags silence
        lastScanResultMs.set(now);
        Log.i(TAG, "BLE scan started");
    }

    private ScanCallback newScanCallback() {
        return new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                lastScanResultMs.set(System.currentTimeMillis());
                if (listeners.isEmpty()) return;
                RawAd ad = toRawAd(result);
                if (ad != null) queue(ad);
            }

            // must clear the callback or the start path thinks the scan still runs
            // error 2 is the os throttle
            @Override public void onScanFailed(int errorCode) {
                Log.w(TAG, "BLE scan failed: " + errorCode + " (clearing callback so we can retry)");
                synchronized (scanLock) {
                    if (activeScanCb == this) activeScanCb = null;
                }
            }
        };
    }

    // must hold scanLock
    private void restartScanLocked() {
        stopScanLocked();
        startBleScanningLocked();
    }

    // must hold scanLock
    @SuppressLint("MissingPermission")
    private void stopScanLocked() {
        ScanCallback cb = activeScanCb;
        BluetoothLeScanner scanner = bleScanner;
        activeScanCb = null;
        if (cb == null || scanner == null) return;
        try {
            scanner.stopScan(cb);
        } catch (RuntimeException e) {
            Log.w(TAG, "BLE scan stop failed: " + e.getMessage());
        }
    }

    // must hold scanLock
    private boolean canStartScanNowLocked() {
        long cutoff = System.currentTimeMillis() - SCAN_START_WINDOW_MS;
        int recent = 0;
        for (long t : recentScanStarts) {
            if (t > cutoff) recent++;
        }
        return recent < SCAN_START_BUDGET;
    }

    // must hold scanLock
    private void recordScanStartLocked() {
        recentScanStarts[recentScanStartsIdx] = System.currentTimeMillis();
        recentScanStartsIdx = (recentScanStartsIdx + 1) % recentScanStarts.length;
    }

    // ---- batching ----

    private void queue(RawAd ad) {
        List<RawAd> toFlush = null;
        synchronized (batch) {
            batch.add(ad);
            if (batch.size() >= RAW_AD_BATCH_MAX) {
                toFlush = new ArrayList<>(batch);
                batch.clear();
            }
        }
        if (toFlush != null) deliver(toFlush);
    }

    private void flushBatch() {
        List<RawAd> toFlush;
        synchronized (batch) {
            if (batch.isEmpty()) return;
            toFlush = new ArrayList<>(batch);
            batch.clear();
        }
        deliver(toFlush);
    }

    private void deliver(List<RawAd> ads) {
        List<RawAd> view = Collections.unmodifiableList(ads);
        for (Listener l : listeners) {
            try {
                l.onAdvertisementBatch(view);
            } catch (RuntimeException e) {
                // one broken consumer must not starve the others
                Log.e(TAG, "listener failed", e);
            }
        }
    }

    private static RawAd toRawAd(ScanResult result) {
        try {
            ScanRecord record = result.getScanRecord();
            byte[] data = record != null ? record.getBytes() : null;
            return new RawAd(parseMacToLong(result.getDevice().getAddress()), addressType(result),
                    result.getRssi(), data, result);
        } catch (RuntimeException e) {
            Log.w(TAG, "scan result skipped: " + e.getMessage());
            return null;
        }
    }

    // the address type is only exposed from api 35 and reads as public before that
    private static int addressType(ScanResult result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return result.getDevice().getAddressType();
        }
        return 0;
    }

    // "AA:BB:CC:DD:EE:FF" to 0x0000AABBCCDDEEFF
    public static long parseMacToLong(String mac) {
        long addr = 0;
        for (String p : mac.split(":")) addr = (addr << 8) | Integer.parseInt(p, 16);
        return addr;
    }

    public static BluetoothAdapter getAdapter() {
        BluetoothManager bm = (BluetoothManager) mApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }
}
