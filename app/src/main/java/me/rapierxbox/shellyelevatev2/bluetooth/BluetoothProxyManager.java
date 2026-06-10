package me.rapierxbox.shellyelevatev2.bluetooth;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.*;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
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
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.SparseArray;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

// ESPHome bluetooth_proxy for HA. Passive: forwards BLE ads. Active: proxies GATT connections.
// Frame format: [0x00][varint len][varint msg_type][payload]. One HA client at a time.
public class BluetoothProxyManager {
    private static final String TAG = "BtProxy";
    private static final int PORT = 6053;

    private static final int MSG_HELLO_REQUEST                     = 1;
    private static final int MSG_HELLO_RESPONSE                    = 2;
    private static final int MSG_CONNECT_REQUEST                   = 3;
    private static final int MSG_CONNECT_RESPONSE                  = 4;
    private static final int MSG_DISCONNECT_REQUEST                = 5;
    private static final int MSG_DISCONNECT_RESPONSE               = 6;
    private static final int MSG_PING_REQUEST                      = 7;
    private static final int MSG_PING_RESPONSE                     = 8;
    private static final int MSG_DEVICE_INFO_REQUEST               = 9;
    private static final int MSG_DEVICE_INFO_RESPONSE              = 10;
    private static final int MSG_LIST_ENTITIES_REQUEST             = 11;
    private static final int MSG_LIST_ENTITIES_DONE                = 19;
    private static final int MSG_SUBSCRIBE_STATES                  = 20;
    private static final int MSG_SUBSCRIBE_HA_STATES               = 34;
    private static final int MSG_GET_TIME_RESPONSE                 = 38;
    private static final int MSG_SUBSCRIBE_BLE                     = 66;
    private static final int MSG_BLE_AD_RESPONSE                   = 67;
    private static final int MSG_BLUETOOTH_DEVICE_REQUEST          = 68;
    private static final int MSG_BLUETOOTH_DEVICE_CONNECTION_RSP   = 69;
    private static final int MSG_GATT_GET_SERVICES_REQUEST         = 70;
    private static final int MSG_GATT_GET_SERVICES_RESPONSE        = 71;
    private static final int MSG_GATT_GET_SERVICES_DONE_RESPONSE   = 72;
    private static final int MSG_GATT_READ_REQUEST                 = 73;
    private static final int MSG_GATT_READ_RESPONSE                = 74;
    private static final int MSG_GATT_WRITE_REQUEST                = 75;
    private static final int MSG_GATT_READ_DESCRIPTOR_REQUEST      = 76;
    private static final int MSG_GATT_WRITE_DESCRIPTOR_REQUEST     = 77;
    private static final int MSG_GATT_NOTIFY_REQUEST               = 78;
    private static final int MSG_GATT_NOTIFY_DATA_RESPONSE         = 79;
    private static final int MSG_SUBSCRIBE_BT_CONNECTIONS_FREE     = 80;
    private static final int MSG_BT_CONNECTIONS_FREE_RESPONSE      = 81;
    private static final int MSG_GATT_ERROR_RESPONSE               = 82;
    private static final int MSG_GATT_WRITE_RESPONSE               = 83;
    private static final int MSG_GATT_NOTIFY_RESPONSE              = 84;
    private static final int MSG_BT_DEVICE_PAIRING_RESPONSE        = 85;
    private static final int MSG_BT_DEVICE_UNPAIRING_RESPONSE      = 86;
    private static final int MSG_UNSUBSCRIBE_BLE                   = 87;
    private static final int MSG_BT_DEVICE_CLEAR_CACHE_RESPONSE    = 88;
    // batched raw ads modern path replacing deprecated 67
    private static final int MSG_BLE_RAW_AD_RESPONSE              = 93;

    private static final int DEV_REQ_CONNECT                  = 0;
    private static final int DEV_REQ_DISCONNECT               = 1;
    private static final int DEV_REQ_PAIR                     = 2;
    private static final int DEV_REQ_UNPAIR                   = 3;
    private static final int DEV_REQ_CONNECT_V3_WITH_CACHE    = 4;
    private static final int DEV_REQ_CONNECT_V3_WITHOUT_CACHE = 5;
    private static final int DEV_REQ_CLEAR_CACHE              = 6;

    // PASSIVE_SCAN | ACTIVE_CONNECTIONS | CACHE_CLEARING | RAW_ADVERTISEMENTS.
    // No REMOTE_CACHING (handles reset on reconnect) or PAIRING (needs Android UI).
    private static final int BT_PROXY_FLAGS = 1 | 2 | 16 | 32;
    // flag ha sets in the subscribe request to ask for raw ads
    private static final int SUBSCRIPTION_RAW_ADVERTISEMENTS = 1;
    // old HA builds that don't read BT_PROXY_FLAGS check this version number instead
    private static final int BT_LEGACY_VERSION = 5;
    private static final String ESPHOME_VERSION = "2026.5.1";

    // Android allows 4-7 connections depending on vendor; 3 is safe across the board
    private static final int MAX_ACTIVE_CONNECTIONS = 3;

    private static final long SCAN_WATCHDOG_PERIOD_MS    = 15_000;
    private static final long SCAN_SILENT_RESTART_MS     = 45_000;        // no ads this long → scan is dead
    private static final long SCAN_PREEMPTIVE_RESTART_MS = 15 * 60 * 1000; // avoid silent OS throttle on long runs
    private static final long PING_IDLE_THRESHOLD_MS     = 60_000;        // probe HA after this much silence
    private static final long PING_DEAD_THRESHOLD_MS     = 95_000;        // give up if HA doesn't respond
    // Android throttles at 5 scan starts/30s; stay one under
    private static final int  SCAN_START_BUDGET = 4;
    private static final long SCAN_START_WINDOW_MS = 30_000;

    // batch raw ads to cut frame count and queue pressure
    private static final int  RAW_AD_BATCH_MAX = 16;
    private static final long RAW_AD_FLUSH_MS  = 100;
    // reject absurd frame lengths to avoid heap blowup on the open socket
    private static final int  MAX_FRAME_SIZE = 256 * 1024;

    private static final byte[] WRITE_QUEUE_SENTINEL = new byte[0];

    private volatile boolean      enabled = false;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile ServerSocket serverSocket;
    private final AtomicReference<ClientSession> activeSession = new AtomicReference<>();
    private volatile BluetoothLeScanner bleScanner;
    private volatile ScanCallback        activeScanCb;
    private final AtomicLong lastScanResultMs = new AtomicLong(0);
    private final AtomicLong lastScanStartedMs = new AtomicLong(0);
    // tracks recent scan start times to avoid the 5/30s OS throttle
    private final long[] recentScanStarts = new long[SCAN_START_BUDGET + 1];
    private int recentScanStartsIdx = 0;
    private final Object scanStartLock = new Object();

    // current scan target; survives HA reconnects so we don't restart the scan each time
    private final AtomicReference<ClientSession> scanTarget = new AtomicReference<>();
    private NsdManager                   nsdManager;
    private NsdManager.RegistrationListener nsdListener;
    private final BroadcastReceiver settingsReceiver;
    private final BroadcastReceiver btStateReceiver;

    private ScheduledFuture<?> scanWatchdogTask;

    private volatile int activeScanMode = ScanSettings.SCAN_MODE_LOW_LATENCY;


    public BluetoothProxyManager() {
        settingsReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { checkAndApplySettings(); }
        };
        LocalBroadcastManager.getInstance(mApplicationContext)
                .registerReceiver(settingsReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));

        // BT daemon occasionally bounces without triggering onScanFailed; re-arm on STATE_ON
        btStateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                int state = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    Log.w(TAG, "BT adapter going down, dropping scanner state");
                    activeScanCb = null;
                    bleScanner = null;
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "BT adapter back ON, re-arming scan if a session needs it");
                    ClientSession s = scanTarget.get();
                    if (s != null) startBleScanning(s);
                }
            }
        };
        mApplicationContext.registerReceiver(btStateReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        checkAndApplySettings();
    }

    public void checkAndApplySettings() {
        boolean want = mSharedPreferences.getBoolean(SP_BLUETOOTH_PROXY_ENABLED, false);
        if (want && !enabled) {
            enabled = true;
            startServer();
            startScanWatchdog();
        } else if (!want && enabled) {
            enabled = false;
            shutdown();
        }
    }

    public void setLowPowerMode(boolean low) {
        int target = low ? ScanSettings.SCAN_MODE_LOW_POWER : ScanSettings.SCAN_MODE_LOW_LATENCY;
        if (target == activeScanMode) return;
        activeScanMode = target;
        Log.i(TAG, "Scan mode -> " + (low ? "LOW_POWER" : "LOW_LATENCY"));
        ClientSession s = scanTarget.get();
        if (s != null && activeScanCb != null) {
            restartScan(s);
        }
    }

    private void startServer() {
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT, 1, InetAddress.getByName("0.0.0.0"));
                Log.i(TAG, "listening on port " + PORT);
                registerNsd();

                while (enabled && !serverSocket.isClosed()) {
                    Socket client;
                    try {
                        client = serverSocket.accept();
                    } catch (IOException e) {
                        // only bail on shutdown; transient accept() errors shouldn't kill the loop
                        if (!enabled || serverSocket.isClosed()) break;
                        Log.w(TAG, "accept error, continuing: " + e.getMessage());
                        continue;
                    }
                    Log.i(TAG, "HA connected from " + client.getInetAddress());
                    try {
                        client.setKeepAlive(true);    // detect dead HA conn without waiting hours
                        client.setTcpNoDelay(true);   // small frames, latency matters
                    } catch (Exception ignored) {}
                    ClientSession prev = activeSession.getAndSet(null);
                    if (prev != null) prev.close("new connection");
                    ClientSession session = new ClientSession(client);
                    activeSession.set(session);
                    executor.execute(session::run);
                }
            } catch (IOException e) {
                if (enabled) Log.e(TAG, "server error", e);
            } finally {
                unregisterNsd();
                closeServerSocket();
            }
        });
    }

    private void startScanWatchdog() {
        if (scanWatchdogTask != null && !scanWatchdogTask.isDone()) return;
        scanWatchdogTask = scheduler.scheduleWithFixedDelay(this::runScanWatchdog,
                SCAN_WATCHDOG_PERIOD_MS, SCAN_WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    // LOW_LATENCY scans silently die on some devices; restart if quiet or running too long
    private void runScanWatchdog() {
        ClientSession s = scanTarget.get();
        if (s == null || s.closed.get()) return;
        if (activeScanCb == null) {
            // onScanFailed probably cleared the callback, retry
            Log.w(TAG, "watchdog: scan not running while subscribed, attempting restart");
            startBleScanning(s);
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
            restartScan(s);
        }
    }

    @SuppressLint("MissingPermission")
    private void restartScan(ClientSession session) {
        ScanCallback cb = activeScanCb;
        if (cb != null && bleScanner != null) {
            try { bleScanner.stopScan(cb); } catch (Exception ignored) {}
        }
        activeScanCb = null;
        startBleScanning(session);
    }

    @SuppressLint("MissingPermission")
    private void stopBleScanning() {
        scanTarget.set(null);
        ScanCallback cb = activeScanCb;
        if (cb != null && bleScanner != null) {
            try { bleScanner.stopScan(cb); } catch (Exception ignored) {}
            activeScanCb = null;
            Log.i(TAG, "BLE scan stopped");
        }
    }

    private void closeServerSocket() {
        ServerSocket ss = serverSocket;
        if (ss != null && !ss.isClosed()) { try { ss.close(); } catch (IOException ignored) {} }
    }


    private void shutdown() {
        if (scanWatchdogTask != null) { scanWatchdogTask.cancel(false); scanWatchdogTask = null; }
        stopBleScanning();
        ClientSession s = activeSession.getAndSet(null);
        if (s != null) s.close("shutdown");
        closeServerSocket();
        Log.i(TAG, "shutdown");
    }

    public void onDestroy() {
        enabled = false;
        shutdown();
        try { mApplicationContext.unregisterReceiver(btStateReceiver); } catch (Exception ignored) {}
        LocalBroadcastManager.getInstance(mApplicationContext).unregisterReceiver(settingsReceiver);
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    private void registerNsd() {
        try {
            String name = mSharedPreferences.getString(SP_BLUETOOTH_PROXY_NAME, "ShellyElevate");
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(name);
            info.setServiceType("_esphomelib._tcp");
            info.setPort(PORT);
            info.setAttribute("version", "2024.11.0");
            info.setAttribute("mac", getWifiMac());
            info.setAttribute("board", "android");

            nsdManager = (NsdManager) mApplicationContext.getSystemService(Context.NSD_SERVICE);
            nsdListener = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo i, int e) {
                    Log.w(TAG, "mDNS registration failed: " + e);
                }
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) {}
                @Override public void onServiceRegistered(NsdServiceInfo i) {
                    Log.i(TAG, "mDNS registered as \"" + i.getServiceName() + "\"");
                }
                @Override public void onServiceUnregistered(NsdServiceInfo i) {}
            };
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception e) {
            Log.w(TAG, "mDNS setup failed: " + e.getMessage());
        }
    }

    private void unregisterNsd() {
        if (nsdManager != null && nsdListener != null) {
            try { nsdManager.unregisterService(nsdListener); } catch (Exception ignored) {}
            nsdListener = null;
        }
    }

    private class ClientSession implements ActiveBleConnection.Callback {
        private final Socket socket;
        // capped so a slow HA can't back up into the BLE scan callback (system BT thread)
        private final BlockingQueue<byte[]> outQueue = new LinkedBlockingQueue<>(2000);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        // open GATT connections, keyed by MAC
        private final Map<Long, ActiveBleConnection> connections = new ConcurrentHashMap<>();
        private volatile boolean subscribedConnFree = false;
        private volatile long lastClientActivityMs = System.currentTimeMillis();
        private volatile long pendingPingSentMs = 0;
        private ScheduledFuture<?> pingTask;
        // throttle drop logs so we don't spam logcat
        private long droppedSinceLastLog = 0;
        private long lastDropLogMs = 0;
        // set when ha subscribes with the raw flag
        private volatile boolean rawAds = false;
        private final List<byte[]> rawAdBatch = new ArrayList<>();
        private ScheduledFuture<?> rawFlushTask;

        ClientSession(Socket socket) { this.socket = socket; }


        void run() {
            executor.execute(this::writeLoop);
            pingTask = scheduler.scheduleWithFixedDelay(this::runPingWatchdog, 30, 30, TimeUnit.SECONDS);
            try {
                InputStream in = socket.getInputStream();
                while (!closed.get() && enabled) {
                    int[] msgType = new int[1];
                    byte[] payload = readFrame(in, msgType);
                    if (payload == null) break;
                    lastClientActivityMs = System.currentTimeMillis();
                    pendingPingSentMs = 0;
                    handleMessage(msgType[0], payload);
                }
            } catch (IOException e) {
                if (!closed.get()) Log.i(TAG, "client disconnected: " + e.getMessage());
            } finally {
                close("session ended");
                if (activeSession.compareAndSet(this, null)) {
                    // keep scan running; restarting on every HA reconnect trips the 5/30s throttle
                    if (scanTarget.compareAndSet(this, null))
                        Log.i(TAG, "session ended, scan kept running for next HA reconnect");
                    Log.i(TAG, "session cleaned up");
                }
            }
        }

        private void runPingWatchdog() {
            if (closed.get()) return;
            long now = System.currentTimeMillis();
            long idle = now - lastClientActivityMs;
            // probe HA if it's been quiet — catches wedged TCP half-opens early
            if (idle > PING_IDLE_THRESHOLD_MS && pendingPingSentMs == 0) {
                pendingPingSentMs = now;
                enqueue(buildFrame(MSG_PING_REQUEST, new byte[0]));
            }
            if (idle > PING_DEAD_THRESHOLD_MS) {
                Log.w(TAG, "ping watchdog: HA silent for " + idle + "ms, closing session");
                close("ping timeout");
            }
        }

        private void writeLoop() {
            try {
                OutputStream out = socket.getOutputStream();
                while (!closed.get()) {
                    byte[] frame = outQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (frame == null) continue;
                    if (frame == WRITE_QUEUE_SENTINEL) break;
                    out.write(frame);
                    out.flush();
                }
            } catch (Exception e) {
                if (!closed.get()) Log.w(TAG, "write error: " + e.getMessage());
            }
        }


        private void handleMessage(int type, byte[] payload) {
            switch (type) {
                case MSG_HELLO_REQUEST:
                    enqueue(buildFrame(MSG_HELLO_RESPONSE, buildHelloResponse()));
                    break;
                case MSG_CONNECT_REQUEST:
                    enqueue(buildFrame(MSG_CONNECT_RESPONSE, new byte[0])); // empty = no password
                    break;
                case MSG_LIST_ENTITIES_REQUEST:
                    enqueue(buildFrame(MSG_LIST_ENTITIES_DONE, new byte[0]));
                    break;
                case MSG_SUBSCRIBE_STATES:
                case MSG_SUBSCRIBE_HA_STATES:
                case MSG_GET_TIME_RESPONSE:
                    break;
                case MSG_DEVICE_INFO_REQUEST:
                    enqueue(buildFrame(MSG_DEVICE_INFO_RESPONSE, buildDeviceInfoResponse()));
                    break;
                case MSG_SUBSCRIBE_BLE:
                    if ((readSingleVarintField(payload, 1) & SUBSCRIPTION_RAW_ADVERTISEMENTS) != 0) enableRawAds();
                    startBleScanning(this);
                    break;
                case MSG_UNSUBSCRIBE_BLE:
                    stopBleScanning();
                    break;
                case MSG_PING_REQUEST:
                    enqueue(buildFrame(MSG_PING_RESPONSE, new byte[0]));
                    break;
                case MSG_PING_RESPONSE:
                    pendingPingSentMs = 0; // TCP alive
                    break;
                case MSG_DISCONNECT_REQUEST:
                    enqueue(buildFrame(MSG_DISCONNECT_RESPONSE, new byte[0]));
                    close("disconnect requested");
                    break;
                case MSG_BLUETOOTH_DEVICE_REQUEST:
                    handleBluetoothDeviceRequest(payload);
                    break;
                case MSG_GATT_GET_SERVICES_REQUEST:
                    handleGattGetServicesRequest(payload);
                    break;
                case MSG_GATT_READ_REQUEST:
                    handleGattReadRequest(payload);
                    break;
                case MSG_GATT_WRITE_REQUEST:
                    handleGattWriteRequest(payload);
                    break;
                case MSG_GATT_READ_DESCRIPTOR_REQUEST:
                    handleGattReadDescriptorRequest(payload);
                    break;
                case MSG_GATT_WRITE_DESCRIPTOR_REQUEST:
                    handleGattWriteDescriptorRequest(payload);
                    break;
                case MSG_GATT_NOTIFY_REQUEST:
                    handleGattNotifyRequest(payload);
                    break;
                case MSG_SUBSCRIBE_BT_CONNECTIONS_FREE:
                    subscribedConnFree = true;
                    sendConnectionsFree();
                    break;
                default:
                    Log.d(TAG, "unhandled message type " + type);
            }
        }

        void sendAdvertisement(byte[] payload) {
            enqueue(buildFrame(MSG_BLE_AD_RESPONSE, payload));
        }

        void enableRawAds() {
            rawAds = true;
            if (rawFlushTask == null)
                rawFlushTask = scheduler.scheduleWithFixedDelay(
                        this::flushRawAds, RAW_AD_FLUSH_MS, RAW_AD_FLUSH_MS, TimeUnit.MILLISECONDS);
        }

        void sendRawAdvertisement(byte[] entry) {
            List<byte[]> toFlush = null;
            synchronized (rawAdBatch) {
                rawAdBatch.add(entry);
                if (rawAdBatch.size() >= RAW_AD_BATCH_MAX) {
                    toFlush = new ArrayList<>(rawAdBatch);
                    rawAdBatch.clear();
                }
            }
            if (toFlush != null) flushBatch(toFlush);
        }

        private void flushRawAds() {
            if (closed.get()) return;
            List<byte[]> toFlush;
            synchronized (rawAdBatch) {
                if (rawAdBatch.isEmpty()) return;
                toFlush = new ArrayList<>(rawAdBatch);
                rawAdBatch.clear();
            }
            flushBatch(toFlush);
        }

        // one BluetoothLERawAdvertisementsResponse holds repeated entries in field 1
        private void flushBatch(List<byte[]> entries) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] e : entries) encodeLenField(out, 1, e);
            enqueue(buildFrame(MSG_BLE_RAW_AD_RESPONSE, out.toByteArray()));
        }

        private void enqueue(byte[] frame) {
            if (closed.get()) return;
            if (!outQueue.offer(frame)) {
                droppedSinceLastLog++;
                long now = System.currentTimeMillis();
                if (now - lastDropLogMs > 10_000) {
                    Log.w(TAG, "write queue full, dropped " + droppedSinceLastLog + " frames in last "
                            + (now - lastDropLogMs) + "ms");
                    droppedSinceLastLog = 0;
                    lastDropLogMs = now;
                }
            }
        }

        void close(String reason) {
            if (closed.getAndSet(true)) return;
            outQueue.offer(WRITE_QUEUE_SENTINEL);
            try { socket.close(); } catch (IOException ignored) {}
            if (pingTask != null) pingTask.cancel(false);
            if (rawFlushTask != null) rawFlushTask.cancel(false);
            // snapshot first — close() fires onConnectionStateChanged which removes from the map
            List<ActiveBleConnection> snapshot = new ArrayList<>(connections.values());
            connections.clear();
            for (ActiveBleConnection c : snapshot) c.close();
            Log.i(TAG, "session closed: " + reason);
        }

        private void handleBluetoothDeviceRequest(byte[] payload) {
            ProtoReader r = new ProtoReader(payload);
            long address = 0;
            int requestType = -1;
            int addressType = 0;
            boolean hasAddressType = false;
            try {
                while (r.hasMore()) {
                    int tag = (int) r.readVarint();
                    int field = tag >>> 3;
                    int wire  = tag & 7;
                    switch (field) {
                        case 1: address     = r.readVarint();         break;
                        case 2: requestType = (int) r.readVarint();   break;
                        case 3: hasAddressType = r.readVarint() != 0; break;
                        case 4: addressType = (int) r.readVarint();   break;
                        default: r.skip(wire);
                    }
                }
            } catch (IOException e) { Log.w(TAG, "bad BluetoothDeviceRequest: " + e.getMessage()); return; }
            final long addr = address;

            switch (requestType) {
                case DEV_REQ_CONNECT:
                case DEV_REQ_CONNECT_V3_WITH_CACHE:
                case DEV_REQ_CONNECT_V3_WITHOUT_CACHE:
                    if (connections.size() >= MAX_ACTIVE_CONNECTIONS) {
                        Log.w(TAG, "connect rejected, slots full (" + connections.size() + "/" + MAX_ACTIVE_CONNECTIONS + ")");
                        sendConnectionStatus(addr, false, 0, /*errBusy*/ -1);
                        return;
                    }
                    if (connections.containsKey(addr)) {
                        Log.i(TAG, "connect for already-connected " + macStr(addr) + ", ignoring");
                        return;
                    }
                    BluetoothManager bm = (BluetoothManager) mApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE);
                    BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
                    if (adapter == null) { sendConnectionStatus(addr, false, 0, -2); return; }
                    BluetoothDevice device;
                    try {
                        device = adapter.getRemoteDevice(macStr(addr));
                    } catch (IllegalArgumentException e) {
                        sendConnectionStatus(addr, false, 0, -3); return;
                    }
                    Log.i(TAG, "active connect → " + macStr(addr)
                            + (requestType == DEV_REQ_CONNECT_V3_WITHOUT_CACHE ? " (no-cache)" : ""));
                    boolean clearBeforeDiscovery = (requestType == DEV_REQ_CONNECT_V3_WITHOUT_CACHE);
                    ActiveBleConnection conn = new ActiveBleConnection(
                            mApplicationContext, device, this, clearBeforeDiscovery);
                    connections.put(addr, conn);
                    conn.connect();
                    sendConnectionsFreeIfSubscribed();
                    break;

                case DEV_REQ_DISCONNECT: {
                    ActiveBleConnection c = connections.get(addr);
                    if (c != null) {
                        Log.i(TAG, "active disconnect → " + macStr(addr));
                        c.requestDisconnect();
                    }
                    break;
                }

                case DEV_REQ_CLEAR_CACHE: {
                    ActiveBleConnection c = connections.get(addr);
                    boolean ok = c != null && c.clearGattCache();
                    sendClearCacheResponse(addr, ok, ok ? 0 : -1);
                    break;
                }

                case DEV_REQ_PAIR:
                    // not supported — PAIRING flag not advertised
                    sendPairingResponse(addr, false, -4);
                    break;
                case DEV_REQ_UNPAIR:
                    sendUnpairingResponse(addr, false, -4);
                    break;
                default:
                    Log.w(TAG, "BluetoothDeviceRequest: unknown request_type " + requestType);
            }
        }

        private void handleGattGetServicesRequest(byte[] payload) {
            long addr = readSingleVarintField(payload, 1);
            ActiveBleConnection c = connections.get(addr);
            if (c == null) { sendGattError(addr, 0, -1); return; }
            if (!c.discoverServices()) sendGattError(addr, 0, -2);
        }

        private void handleGattReadRequest(byte[] payload) {
            long[] addrHandle = readAddrHandle(payload);
            ActiveBleConnection c = connections.get(addrHandle[0]);
            if (c == null) { sendGattError(addrHandle[0], (int) addrHandle[1], -1); return; }
            c.readCharacteristic((int) addrHandle[1]);
        }

        private void handleGattWriteRequest(byte[] payload) {
            ProtoReader r = new ProtoReader(payload);
            long addr = 0; int handle = 0; boolean response = false; byte[] data = new byte[0];
            try {
                while (r.hasMore()) {
                    int tag = (int) r.readVarint();
                    switch (tag >>> 3) {
                        case 1: addr     = r.readVarint(); break;
                        case 2: handle   = (int) r.readVarint(); break;
                        case 3: response = r.readVarint() != 0; break;
                        case 4: data     = r.readBytes(); break;
                        default: r.skip(tag & 7);
                    }
                }
            } catch (IOException e) { Log.w(TAG, "bad write req: " + e.getMessage()); return; }
            ActiveBleConnection c = connections.get(addr);
            if (c == null) { sendGattError(addr, handle, -1); return; }
            c.writeCharacteristic(handle, data, response);
        }

        private void handleGattReadDescriptorRequest(byte[] payload) {
            long[] ah = readAddrHandle(payload);
            ActiveBleConnection c = connections.get(ah[0]);
            if (c == null) { sendGattError(ah[0], (int) ah[1], -1); return; }
            c.readDescriptor((int) ah[1]);
        }

        private void handleGattWriteDescriptorRequest(byte[] payload) {
            ProtoReader r = new ProtoReader(payload);
            long addr = 0; int handle = 0; byte[] data = new byte[0];
            try {
                while (r.hasMore()) {
                    int tag = (int) r.readVarint();
                    switch (tag >>> 3) {
                        case 1: addr   = r.readVarint(); break;
                        case 2: handle = (int) r.readVarint(); break;
                        case 3: data   = r.readBytes(); break;
                        default: r.skip(tag & 7);
                    }
                }
            } catch (IOException e) { Log.w(TAG, "bad write desc req: " + e.getMessage()); return; }
            ActiveBleConnection c = connections.get(addr);
            if (c == null) { sendGattError(addr, handle, -1); return; }
            c.writeDescriptor(handle, data);
        }

        private void handleGattNotifyRequest(byte[] payload) {
            ProtoReader r = new ProtoReader(payload);
            long addr = 0; int handle = 0; boolean enable = false;
            try {
                while (r.hasMore()) {
                    int tag = (int) r.readVarint();
                    switch (tag >>> 3) {
                        case 1: addr   = r.readVarint(); break;
                        case 2: handle = (int) r.readVarint(); break;
                        case 3: enable = r.readVarint() != 0; break;
                        default: r.skip(tag & 7);
                    }
                }
            } catch (IOException e) { Log.w(TAG, "bad notify req: " + e.getMessage()); return; }
            ActiveBleConnection c = connections.get(addr);
            if (c == null) { sendGattError(addr, handle, -1); return; }
            c.setNotify(handle, enable);
        }

        @Override public void onConnectionStateChanged(long address, boolean connectedFlag, int mtu, int errorCode) {
            sendConnectionStatus(address, connectedFlag, mtu, errorCode);
            if (!connectedFlag) {
                ActiveBleConnection c = connections.remove(address);
                if (c != null) c.close();
                sendConnectionsFreeIfSubscribed();
            }
        }
        @Override public void onServicesReady(long address) {
            ActiveBleConnection c = connections.get(address);
            if (c == null) return;
            // one response per service, then a Done
            for (BluetoothGattService s : c.getServices()) {
                enqueue(buildFrame(MSG_GATT_GET_SERVICES_RESPONSE, buildServicesResponse(c, address, s)));
            }
            ByteArrayOutputStream done = new ByteArrayOutputStream();
            encodeVarintField(done, 1, address);
            enqueue(buildFrame(MSG_GATT_GET_SERVICES_DONE_RESPONSE, done.toByteArray()));
        }
        @Override public void onServicesError(long address, int gattStatus) {
            sendGattError(address, 0, gattStatus);
        }
        @Override public void onCharRead(long address, int handle, byte[] data, int gattStatus) {
            if (gattStatus == 0 && data != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                encodeVarintField(out, 1, address);
                encodeVarintField(out, 2, handle);
                encodeLenField(out, 3, data);
                enqueue(buildFrame(MSG_GATT_READ_RESPONSE, out.toByteArray()));
            } else {
                sendGattError(address, handle, gattStatus);
            }
        }
        @Override public void onCharWrite(long address, int handle, int gattStatus) {
            if (gattStatus == 0) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                encodeVarintField(out, 1, address);
                encodeVarintField(out, 2, handle);
                enqueue(buildFrame(MSG_GATT_WRITE_RESPONSE, out.toByteArray()));
            } else {
                sendGattError(address, handle, gattStatus);
            }
        }
        @Override public void onDescRead(long address, int handle, byte[] data, int gattStatus) {
            if (gattStatus == 0 && data != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                encodeVarintField(out, 1, address);
                encodeVarintField(out, 2, handle);
                encodeLenField(out, 3, data);
                enqueue(buildFrame(MSG_GATT_READ_RESPONSE, out.toByteArray()));
            } else {
                sendGattError(address, handle, gattStatus);
            }
        }
        @Override public void onDescWrite(long address, int handle, int gattStatus) {
            if (gattStatus == 0) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                encodeVarintField(out, 1, address);
                encodeVarintField(out, 2, handle);
                enqueue(buildFrame(MSG_GATT_WRITE_RESPONSE, out.toByteArray()));
            } else {
                sendGattError(address, handle, gattStatus);
            }
        }
        @Override public void onNotifyResult(long address, int handle, int gattStatus) {
            if (gattStatus == 0) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                encodeVarintField(out, 1, address);
                encodeVarintField(out, 2, handle);
                enqueue(buildFrame(MSG_GATT_NOTIFY_RESPONSE, out.toByteArray()));
            } else {
                sendGattError(address, handle, gattStatus);
            }
        }
        @Override public void onNotifyData(long address, int handle, byte[] data) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encodeVarintField(out, 1, address);
            encodeVarintField(out, 2, handle);
            encodeLenField(out, 3, data != null ? data : new byte[0]);
            enqueue(buildFrame(MSG_GATT_NOTIFY_DATA_RESPONSE, out.toByteArray()));
        }

        private void sendConnectionStatus(long address, boolean connectedFlag, int mtu, int errorCode) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encodeVarintField(out, 1, address);
            encodeVarintField(out, 2, connectedFlag ? 1 : 0);
            encodeVarintField(out, 3, mtu);
            encodeZigzagField(out, 4, errorCode);
            enqueue(buildFrame(MSG_BLUETOOTH_DEVICE_CONNECTION_RSP, out.toByteArray()));
        }
        private void sendGattError(long address, int handle, int errorCode) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encodeVarintField(out, 1, address);
            encodeVarintField(out, 2, handle);
            encodeZigzagField(out, 3, errorCode);
            enqueue(buildFrame(MSG_GATT_ERROR_RESPONSE, out.toByteArray()));
        }
        private void sendClearCacheResponse(long address, boolean success, int errorCode) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encodeVarintField(out, 1, address);
            encodeVarintField(out, 2, success ? 1 : 0);
            encodeZigzagField(out, 3, errorCode);
            enqueue(buildFrame(MSG_BT_DEVICE_CLEAR_CACHE_RESPONSE, out.toByteArray()));
        }
        private void sendPairingResponse(long address, boolean paired, int errorCode) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encodeVarintField(out, 1, address);
            encodeVarintField(out, 2, paired ? 1 : 0);
            encodeZigzagField(out, 3, errorCode);
            enqueue(buildFrame(MSG_BT_DEVICE_PAIRING_RESPONSE, out.toByteArray()));
        }
        private void sendUnpairingResponse(long address, boolean success, int errorCode) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encodeVarintField(out, 1, address);
            encodeVarintField(out, 2, success ? 1 : 0);
            encodeZigzagField(out, 3, errorCode);
            enqueue(buildFrame(MSG_BT_DEVICE_UNPAIRING_RESPONSE, out.toByteArray()));
        }
        private void sendConnectionsFreeIfSubscribed() {
            if (subscribedConnFree) sendConnectionsFree();
        }
        private void sendConnectionsFree() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int active = connections.size();
            int free = Math.max(0, MAX_ACTIVE_CONNECTIONS - active);
            encodeVarintField(out, 1, free);
            encodeVarintField(out, 2, MAX_ACTIVE_CONNECTIONS);
            // repeated uint64 allocated — packed
            if (active > 0) {
                ByteArrayOutputStream packed = new ByteArrayOutputStream();
                for (Long a : connections.keySet()) writeVarint(packed, a);
                encodeLenField(out, 3, packed.toByteArray());
            }
            enqueue(buildFrame(MSG_BT_CONNECTIONS_FREE_RESPONSE, out.toByteArray()));
        }
    }

    private void startBleScanning(ClientSession session) {
        scanTarget.set(session);

        if (activeScanCb != null) {
            Log.i(TAG, "BLE scan already running, redirected to new session");
            return;
        }

        if (!canStartScanNow()) {
            Log.w(TAG, "scan start rate-limited under OS 5/30s throttle, will retry on next watchdog tick");
            return;
        }

        BluetoothManager bm = (BluetoothManager) mApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "bluetooth unavailable"); return;
        }
        bleScanner = adapter.getBluetoothLeScanner();
        if (bleScanner == null) { Log.w(TAG, "LE scanner unavailable"); return; }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(activeScanMode)
                .setReportDelay(0)
                .build();

        activeScanCb = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                lastScanResultMs.set(System.currentTimeMillis());
                ClientSession target = scanTarget.get();
                if (target == null) return;
                if (target.rawAds) {
                    byte[] raw = buildRawAdvertisement(result);
                    if (raw != null) target.sendRawAdvertisement(raw);
                } else {
                    byte[] ad = buildBleScanRecord(result);
                    if (ad != null) target.sendAdvertisement(ad);
                }
            }
            @Override public void onScanFailed(int errorCode) {
                // must clear this or startBleScanning() thinks it's still running; error 2 = OS throttle
                Log.w(TAG, "BLE scan failed: " + errorCode + " (clearing callback so we can retry)");
                if (activeScanCb == this) activeScanCb = null;
            }
        };

        try {
            recordScanStart();
            bleScanner.startScan(null, settings, activeScanCb);
            lastScanStartedMs.set(System.currentTimeMillis());
            lastScanResultMs.set(System.currentTimeMillis()); // grace period before watchdog flags silence
            Log.i(TAG, "BLE scan started");
        } catch (SecurityException e) {
            Log.e(TAG, "BLE scan permission denied, grant BLUETOOTH_SCAN / ACCESS_FINE_LOCATION");
            activeScanCb = null;
        }
    }

    private boolean canStartScanNow() {
        synchronized (scanStartLock) {
            long now = System.currentTimeMillis();
            int recent = 0;
            for (long t : recentScanStarts) {
                if (t > now - SCAN_START_WINDOW_MS) recent++;
            }
            return recent < SCAN_START_BUDGET;
        }
    }

    private void recordScanStart() {
        synchronized (scanStartLock) {
            recentScanStarts[recentScanStartsIdx] = System.currentTimeMillis();
            recentScanStartsIdx = (recentScanStartsIdx + 1) % recentScanStarts.length;
        }
    }

    private static byte[] buildFrame(int msgType, byte[] payload) {
        ByteArrayOutputStream f = new ByteArrayOutputStream(8 + payload.length);
        f.write(0x00); // 0x01 = noise-encrypted
        writeVarint(f, payload.length);
        writeVarint(f, msgType);
        f.write(payload, 0, payload.length);
        return f.toByteArray();
    }

    private byte[] buildHelloResponse() {
        String name = mSharedPreferences.getString(SP_BLUETOOTH_PROXY_NAME, "ShellyElevate");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeVarintField(out, 1, 1);  // api_version_major
        encodeVarintField(out, 2, 10); // api_version_minor
        encodeBytesField(out, 3, "ShellyElevate 1.0.0");
        encodeBytesField(out, 4, name);
        return out.toByteArray();
    }

    private byte[] buildDeviceInfoResponse() {
        String name  = mSharedPreferences.getString(SP_BLUETOOTH_PROXY_NAME, "ShellyElevate");
        String mac   = getWifiMac();
        String btMac = getBtMac();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // field numbers from ESPHome's api.proto DeviceInfoResponse
        encodeBytesField(out, 2,  name);
        encodeBytesField(out, 3,  mac);
        encodeBytesField(out, 4,  ESPHOME_VERSION);
        encodeBytesField(out, 6,  "android-bt-proxy");
        encodeVarintField(out, 11, BT_LEGACY_VERSION);
        encodeBytesField(out, 12, "Android");
        encodeBytesField(out, 13, name);
        encodeVarintField(out, 15, BT_PROXY_FLAGS);
        if (!btMac.isEmpty()) encodeBytesField(out, 18, btMac);
        Log.i(TAG, "DeviceInfoResponse: flags=" + BT_PROXY_FLAGS + " legacyVer=" + BT_LEGACY_VERSION
                + " btMac=" + (btMac.isEmpty() ? "(none)" : btMac));
        return out.toByteArray();
    }

    private static byte[] buildBleScanRecord(ScanResult result) {
        try {
            long address   = parseMacToLong(result.getDevice().getAddress());
            ScanRecord record = result.getScanRecord();
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            encodeVarintField(out, 1, address);

            if (record != null && record.getDeviceName() != null && !record.getDeviceName().isEmpty())
                encodeBytesField(out, 2, record.getDeviceName());

            // rssi is sint32, needs zigzag
            encodeZigzagField(out, 3, result.getRssi());

            if (record != null) {
                List<ParcelUuid> uuids = record.getServiceUuids();
                if (uuids != null)
                    for (ParcelUuid u : uuids)
                        encodeBytesField(out, 4, u.getUuid().toString());

                Map<ParcelUuid, byte[]> svcData = record.getServiceData();
                if (svcData != null)
                    for (Map.Entry<ParcelUuid, byte[]> e : svcData.entrySet())
                        encodeLenField(out, 5, buildServiceData(e.getKey().getUuid().toString(), e.getValue()));

                SparseArray<byte[]> mfrData = record.getManufacturerSpecificData();
                if (mfrData != null)
                    for (int i = 0; i < mfrData.size(); i++)
                        encodeLenField(out, 6, buildServiceData(
                                String.format("0x%04X", mfrData.keyAt(i)), mfrData.valueAt(i)));
            }

            int addrType = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                addrType = result.getDevice().getAddressType();
            }
            if (addrType != 0) encodeVarintField(out, 7, addrType);

            return out.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "encode scan result failed: " + e.getMessage());
            return null;
        }
    }

    // raw adv payload straight from the scan record matches BluetoothLERawAdvertisement
    private static byte[] buildRawAdvertisement(ScanResult result) {
        try {
            ScanRecord record = result.getScanRecord();
            byte[] data = record != null ? record.getBytes() : null;
            if (data == null) return null;
            long address = parseMacToLong(result.getDevice().getAddress());
            int addrType = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                    && result.getDevice().getAddressType() == 1) {
                addrType = 1; // random
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encodeVarintField(out, 1, address);
            encodeZigzagField(out, 2, result.getRssi());
            encodeVarintField(out, 3, addrType);
            encodeLenField(out, 4, data);
            return out.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "encode raw scan result failed: " + e.getMessage());
            return null;
        }
    }

    private static byte[] buildServiceData(String uuid, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeBytesField(out, 1, uuid);
        if (data != null && data.length > 0) encodeLenField(out, 3, data);
        return out.toByteArray();
    }

    private static byte[] buildServicesResponse(ActiveBleConnection c, long address, BluetoothGattService s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeVarintField(out, 1, address);
        encodeLenField(out, 2, buildServiceEntry(c, s));
        return out.toByteArray();
    }

    private static byte[] buildServiceEntry(ActiveBleConnection c, BluetoothGattService s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeUuid128(out, 1, s.getUuid());                       // repeated uint64 uuid (packed)
        encodeVarintField(out, 2, orZero(c.handleOf(s)));         // handle
        for (BluetoothGattCharacteristic ch : s.getCharacteristics()) {
            encodeLenField(out, 3, buildCharEntry(c, ch));
        }
        return out.toByteArray();
    }

    private static byte[] buildCharEntry(ActiveBleConnection c, BluetoothGattCharacteristic ch) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeUuid128(out, 1, ch.getUuid());
        encodeVarintField(out, 2, orZero(c.handleOf(ch)));
        encodeVarintField(out, 3, ch.getProperties());
        for (BluetoothGattDescriptor d : ch.getDescriptors()) {
            encodeLenField(out, 4, buildDescEntry(c, d));
        }
        return out.toByteArray();
    }

    private static byte[] buildDescEntry(ActiveBleConnection c, BluetoothGattDescriptor d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeUuid128(out, 1, d.getUuid());
        encodeVarintField(out, 2, orZero(c.handleOf(d)));
        return out.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0L) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void encodeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, (long) field << 3);
        writeVarint(out, value);
    }

    // zigzag-encode sint32 (needed for negative RSSI and error codes)
    private static void encodeZigzagField(ByteArrayOutputStream out, int field, int value) {
        encodeVarintField(out, field, (((long) value << 1)) ^ ((long) (value >> 31)));
    }

    private static void encodeLenField(ByteArrayOutputStream out, int field, byte[] data) {
        writeVarint(out, ((long) field << 3) | 2L);
        writeVarint(out, data.length);
        out.write(data, 0, data.length);
    }

    private static void encodeBytesField(ByteArrayOutputStream out, int field, String value) {
        encodeLenField(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    // UUID as packed repeated uint64 (high bits, then low bits)
    private static void encodeUuid128(ByteArrayOutputStream out, int field, UUID uuid) {
        ByteArrayOutputStream packed = new ByteArrayOutputStream(20);
        writeVarint(packed, uuid.getMostSignificantBits());
        writeVarint(packed, uuid.getLeastSignificantBits());
        encodeLenField(out, field, packed.toByteArray());
    }

    // reads address (field 1) and handle (field 2) from a proto payload
    private static long[] readAddrHandle(byte[] payload) {
        long addr = 0; long handle = 0;
        ProtoReader r = new ProtoReader(payload);
        try {
            while (r.hasMore()) {
                int tag = (int) r.readVarint();
                switch (tag >>> 3) {
                    case 1: addr   = r.readVarint(); break;
                    case 2: handle = r.readVarint(); break;
                    default: r.skip(tag & 7);
                }
            }
        } catch (IOException ignored) {}
        return new long[] { addr, handle };
    }

    private static long readSingleVarintField(byte[] payload, int wantField) {
        ProtoReader r = new ProtoReader(payload);
        try {
            while (r.hasMore()) {
                int tag = (int) r.readVarint();
                if ((tag >>> 3) == wantField && (tag & 7) == 0) return r.readVarint();
                r.skip(tag & 7);
            }
        } catch (IOException ignored) {}
        return 0;
    }

    private static byte[] readFrame(InputStream in, int[] outMsgType) throws IOException {
        int b = in.read();
        if (b == -1) return null;
        if (b != 0x00) throw new IOException("bad preamble: 0x" + Integer.toHexString(b));
        int payloadLen = readVarint(in);
        if (payloadLen < 0 || payloadLen > MAX_FRAME_SIZE)
            throw new IOException("frame too large: " + payloadLen);
        int msgType    = readVarint(in);
        byte[] payload = new byte[payloadLen];
        int off = 0;
        while (off < payloadLen) {
            int n = in.read(payload, off, payloadLen - off);
            if (n == -1) return null;
            off += n;
        }
        outMsgType[0] = msgType;
        return payload;
    }

    private static int readVarint(InputStream in) throws IOException {
        int result = 0, shift = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new IOException("eof in varint");
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 28) throw new IOException("varint overflow");
        }
    }

    private static class ProtoReader {
        private final byte[] buf;
        private int pos;
        private final int end;
        ProtoReader(byte[] buf) { this.buf = buf; this.pos = 0; this.end = buf.length; }
        boolean hasMore() { return pos < end; }
        long readVarint() throws IOException {
            long result = 0; int shift = 0;
            while (true) {
                if (pos >= end) throw new IOException("eof in varint");
                int b = buf[pos++] & 0xff;
                result |= (long)(b & 0x7f) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
                if (shift >= 64) throw new IOException("varint overflow");
            }
        }
        byte[] readBytes() throws IOException {
            int len = (int) readVarint();
            if (len < 0 || pos + len > end) throw new IOException("bad len-delimited size");
            byte[] b = new byte[len];
            System.arraycopy(buf, pos, b, 0, len);
            pos += len;
            return b;
        }
        void skip(int wireType) throws IOException {
            switch (wireType) {
                case 0: readVarint(); return;
                case 1: pos += 8; return;
                case 2: int len = (int) readVarint(); pos += len; return;
                case 5: pos += 4; return;
                default: throw new IOException("bad wire type " + wireType);
            }
        }
    }

    // "AA:BB:CC:DD:EE:FF" → 0x0000AABBCCDDEEFF
    private static long parseMacToLong(String mac) {
        String[] parts = mac.split(":");
        long addr = 0;
        for (String p : parts) addr = (addr << 8) | Integer.parseInt(p, 16);
        return addr;
    }

    private static String macStr(long addr) {
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                (addr >> 40) & 0xff, (addr >> 32) & 0xff, (addr >> 24) & 0xff,
                (addr >> 16) & 0xff, (addr >>  8) & 0xff, addr & 0xff);
    }

    private static int orZero(Integer v) { return v != null ? v : 0; }

    private static String getWifiMac() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface ni = ifaces.nextElement();
                if (ni.getName().startsWith("wlan")) {
                    byte[] hw = ni.getHardwareAddress();
                    if (hw != null && hw.length == 6)
                        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                                hw[0], hw[1], hw[2], hw[3], hw[4], hw[5]);
                }
            }
        } catch (Exception ignored) {}
        return "00:00:00:00:00:00";
    }

    @SuppressLint("HardwareIds")
    private static String getBtMac() {
        try {
            BluetoothManager bm = (BluetoothManager) mApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
            return a != null ? a.getAddress() : "";
        } catch (Exception e) { return ""; }
    }
}
