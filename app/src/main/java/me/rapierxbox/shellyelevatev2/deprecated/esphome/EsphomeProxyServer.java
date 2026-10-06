package me.rapierxbox.shellyelevatev2.deprecated.esphome;

import static me.rapierxbox.shellyelevatev2.Constants.SP_BLUETOOTH_PROXY_NAME;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
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
import java.util.concurrent.atomic.AtomicReference;

import me.rapierxbox.shellyelevatev2.bluetooth.BleScanner;
import me.rapierxbox.shellyelevatev2.deprecated.DeprecatedFeatures;

// esphome bluetooth_proxy for home assistant
// passive mode forwards ble ads from BleScanner and active mode proxies gatt connections
// frames are [0x00][varint len][varint msg_type][payload] and only one ha client is served at a time
// built by ShellyElevateApplication only while bluetoothProxyEnabled is on and destroyed when it goes off
/**
 * @deprecated since 3.26279, replaced by the Shelly Elevate Home Assistant integration (bleScannerEnabled),
 * removal planned in a later release
 */
@Deprecated
public class EsphomeProxyServer {
    private static final String TAG = "BtProxy";
    private static final int PORT = 6053;
    private static final String DEFAULT_PROXY_NAME = "ShellyElevate";

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
    // batched raw ads which replace the deprecated message 67
    private static final int MSG_BLE_RAW_AD_RESPONSE               = 93;

    private static final int DEV_REQ_CONNECT                  = 0;
    private static final int DEV_REQ_DISCONNECT               = 1;
    private static final int DEV_REQ_PAIR                     = 2;
    private static final int DEV_REQ_UNPAIR                   = 3;
    private static final int DEV_REQ_CONNECT_V3_WITH_CACHE    = 4;
    private static final int DEV_REQ_CONNECT_V3_WITHOUT_CACHE = 5;
    private static final int DEV_REQ_CLEAR_CACHE              = 6;

    // local error codes sent back to ha where no gatt status exists
    private static final int ERR_SLOTS_FULL         = -1;
    private static final int ERR_NO_ADAPTER         = -2;
    private static final int ERR_BAD_ADDRESS        = -3;
    private static final int ERR_UNSUPPORTED        = -4;
    private static final int ERR_CONNECT_FAILED     = -5;
    private static final int ERR_NOT_CONNECTED      = -1;
    private static final int ERR_DISCOVERY_FAILED   = -2;
    private static final int ERR_CACHE_CLEAR_FAILED = -1;

    // PASSIVE_SCAN | ACTIVE_CONNECTIONS | CACHE_CLEARING | RAW_ADVERTISEMENTS
    // no REMOTE_CACHING since handles reset on reconnect and no PAIRING since it needs android ui
    private static final int BT_PROXY_FLAGS = 1 | 2 | 16 | 32;
    // flag ha sets in the subscribe request to ask for raw ads
    private static final int SUBSCRIPTION_RAW_ADVERTISEMENTS = 1;
    // old ha builds that ignore BT_PROXY_FLAGS check this version number instead
    private static final int BT_LEGACY_VERSION = 5;
    private static final String ESPHOME_VERSION = "2026.5.1";

    // android allows 4 to 7 connections depending on vendor and 3 is safe everywhere
    private static final int MAX_ACTIVE_CONNECTIONS = 3;

    // probe ha after this much silence
    private static final long PING_IDLE_THRESHOLD_MS     = 60_000;
    // give up when ha does not answer the probe
    private static final long PING_DEAD_THRESHOLD_MS     = 95_000;
    private static final long PING_CHECK_PERIOD_S        = 30;
    // keeps a persistent accept failure from spinning the server thread
    private static final long ACCEPT_RETRY_DELAY_MS = 500;

    // capped so a slow ha cannot back up into the ble scan callback on the system bt thread
    private static final int  OUT_QUEUE_CAPACITY = 2000;
    private static final long DROP_LOG_INTERVAL_MS = 10_000;
    // reject absurd frame lengths to avoid a heap blowup on the open socket
    private static final int  MAX_FRAME_SIZE = 256 * 1024;

    private static final byte[] WRITE_QUEUE_SENTINEL = new byte[0];
    // tells the writer to close the session once every frame queued before it is on the wire
    private static final byte[] WRITE_QUEUE_CLOSE_AFTER_FLUSH = new byte[0];

    private volatile boolean enabled = false;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile ServerSocket serverSocket;
    private final AtomicReference<ClientSession> activeSession = new AtomicReference<>();

    // the session that subscribed to ads. the scan stays warm across ha reconnects
    private final AtomicReference<ClientSession> scanTarget = new AtomicReference<>();
    private final BleScanner.Listener scanListener = ads -> {
        ClientSession target = scanTarget.get();
        if (target != null) target.forwardAdvertisements(ads);
    };

    public void start() {
        if (enabled) return;
        enabled = true;
        DeprecatedFeatures.warnOnce(DeprecatedFeatures.Feature.ESPHOME_PROXY);
        startServer();
    }

    public void onDestroy() {
        enabled = false;
        shutdown();
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    private void shutdown() {
        stopBleScanning();
        ClientSession s = activeSession.getAndSet(null);
        if (s != null) s.close("shutdown");
        closeQuietly(serverSocket);
        Log.i(TAG, "shutdown");
    }

    // ---- server ----

    private void startServer() {
        executor.execute(this::runServer);
    }

    // socket and mdns registration stay local so a quick disable enable toggle
    // cannot make this loop tear down what a newer server owns
    private void runServer() {
        ServerSocket ss = null;
        NsdManager.RegistrationListener nsdListener = null;
        try {
            ss = new ServerSocket(PORT, 1, InetAddress.getByName("0.0.0.0"));
            serverSocket = ss;
            Log.i(TAG, "listening on port " + PORT);
            nsdListener = registerNsd();

            while (enabled && !ss.isClosed()) {
                Socket client;
                try {
                    client = ss.accept();
                } catch (IOException e) {
                    // only bail on shutdown since transient accept errors should not kill the loop
                    if (!enabled || ss.isClosed()) break;
                    Log.w(TAG, "accept error, continuing: " + e.getMessage());
                    SystemClock.sleep(ACCEPT_RETRY_DELAY_MS);
                    continue;
                }
                startSession(client);
            }
        } catch (IOException e) {
            if (enabled) Log.e(TAG, "server error", e);
        } finally {
            unregisterNsd(nsdListener);
            closeQuietly(ss);
        }
    }

    private void startSession(Socket client) {
        Log.i(TAG, "HA connected from " + client.getInetAddress());
        try {
            // keepalive detects a dead ha connection without waiting hours
            client.setKeepAlive(true);
            // frames are small and latency matters
            client.setTcpNoDelay(true);
        } catch (IOException e) {
            Log.w(TAG, "socket options not applied: " + e.getMessage());
        }
        ClientSession session = new ClientSession(client);
        ClientSession prev = activeSession.getAndSet(session);
        if (prev != null) prev.close("new connection");
        executor.execute(session::run);
    }

    private static void closeQuietly(ServerSocket ss) {
        if (ss == null || ss.isClosed()) return;
        try {
            ss.close();
        } catch (IOException ignored) {
            // nothing left to release
        }
    }

    // returns the listener to unregister with or null when registration could not start
    private NsdManager.RegistrationListener registerNsd() {
        NsdManager nsd = (NsdManager) mApplicationContext.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            Log.w(TAG, "mDNS setup failed: NsdManager unavailable");
            return null;
        }
        NsdServiceInfo info = new NsdServiceInfo();
        try {
            info.setServiceName(proxyName());
            info.setServiceType("_esphomelib._tcp");
            info.setPort(PORT);
            info.setAttribute("version", "2024.11.0");
            info.setAttribute("mac", getWifiMac());
            info.setAttribute("board", "android");
        } catch (RuntimeException e) {
            // a failed mdns setup must not take the server loop down with it
            Log.w(TAG, "mDNS setup failed: " + e.getMessage());
            return null;
        }

        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo i, int e) {
                Log.w(TAG, "mDNS registration failed: " + e);
            }
            @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) {
                Log.w(TAG, "mDNS unregistration failed: " + e);
            }
            @Override public void onServiceRegistered(NsdServiceInfo i) {
                Log.i(TAG, "mDNS registered as \"" + i.getServiceName() + "\"");
            }
            @Override public void onServiceUnregistered(NsdServiceInfo i) {}
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
            return listener;
        } catch (RuntimeException e) {
            Log.w(TAG, "mDNS setup failed: " + e.getMessage());
            return null;
        }
    }

    private void unregisterNsd(NsdManager.RegistrationListener listener) {
        if (listener == null) return;
        NsdManager nsd = (NsdManager) mApplicationContext.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        try {
            nsd.unregisterService(listener);
        } catch (RuntimeException e) {
            // thrown when the registration had already failed
            Log.d(TAG, "mDNS unregister skipped: " + e.getMessage());
        }
    }

    // ---- scanning ----

    private void startBleScanning(ClientSession session) {
        scanTarget.set(session);
        BleScanner.get().addListener(scanListener);
    }

    private void stopBleScanning() {
        scanTarget.set(null);
        BleScanner.get().removeListener(scanListener);
    }

    // ---- client session ----

    private class ClientSession implements ActiveBleConnection.Callback {
        private final Socket socket;
        private final BlockingQueue<byte[]> outQueue = new LinkedBlockingQueue<>(OUT_QUEUE_CAPACITY);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        // open gatt connections keyed by mac
        private final Map<Long, ActiveBleConnection> connections = new ConcurrentHashMap<>();
        private volatile boolean subscribedConnFree = false;
        private volatile long lastClientActivityMs = System.currentTimeMillis();
        private volatile long pendingPingSentMs = 0;

        // throttles drop logs so logcat is not spammed
        private final Object dropLock = new Object();
        private long droppedSinceLastLog = 0;
        private long lastDropLogMs = 0;

        // set when ha subscribes with the raw flag
        private volatile boolean rawAds = false;

        // periodic tasks are guarded by this so close cannot miss one scheduled concurrently
        private ScheduledFuture<?> pingTask;

        ClientSession(Socket socket) { this.socket = socket; }

        void run() {
            executor.execute(this::writeLoop);
            try {
                // inside the try so a rejected schedule still closes the session
                startPingWatchdog();
                InputStream in = socket.getInputStream();
                int[] msgType = new int[1];
                while (!closed.get() && enabled) {
                    byte[] payload = readFrame(in, msgType);
                    if (payload == null) break;
                    lastClientActivityMs = System.currentTimeMillis();
                    pendingPingSentMs = 0;
                    dispatch(msgType[0], payload);
                }
            } catch (IOException e) {
                if (!closed.get()) Log.i(TAG, "client disconnected: " + e.getMessage());
            } finally {
                close("session ended");
                if (activeSession.compareAndSet(this, null)) {
                    // keep the scan warm since restarting on every ha reconnect trips the os throttle
                    // the scanner stops it eventually when no ha session comes back
                    if (scanTarget.compareAndSet(this, null)) {
                        Log.i(TAG, "session ended, scan kept running for next HA reconnect");
                        BleScanner.get().removeListenerKeepWarm(scanListener);
                    }
                    Log.i(TAG, "session cleaned up");
                }
            }
        }

        private synchronized void startPingWatchdog() {
            if (closed.get()) return;
            pingTask = scheduler.scheduleWithFixedDelay(this::runPingWatchdog,
                    PING_CHECK_PERIOD_S, PING_CHECK_PERIOD_S, TimeUnit.SECONDS);
        }

        private void runPingWatchdog() {
            if (closed.get()) return;
            long now = System.currentTimeMillis();
            long idle = now - lastClientActivityMs;
            // probe a quiet ha to catch wedged tcp half opens early
            if (idle > PING_IDLE_THRESHOLD_MS && pendingPingSentMs == 0) {
                pendingPingSentMs = now;
                sendEmpty(MSG_PING_REQUEST);
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
                    if (frame == WRITE_QUEUE_CLOSE_AFTER_FLUSH) {
                        close("disconnect requested");
                        break;
                    }
                    out.write(frame);
                    out.flush();
                }
            } catch (IOException e) {
                if (!closed.get()) Log.w(TAG, "write error: " + e.getMessage());
                // a dead writer would leave the reader serving a zombie session
                close("write error");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close("writer interrupted");
            }
        }

        private void dispatch(int type, byte[] payload) {
            try {
                handleMessage(type, payload);
            } catch (IOException e) {
                Log.w(TAG, "malformed message type " + type + ": " + e.getMessage());
            } catch (RuntimeException e) {
                // one bad request must not take down the session and its gatt connections
                Log.e(TAG, "failed to handle message type " + type, e);
            }
        }

        private void handleMessage(int type, byte[] payload) throws IOException {
            switch (type) {
                case MSG_HELLO_REQUEST:
                    enqueue(buildFrame(MSG_HELLO_RESPONSE, buildHelloResponse()));
                    break;
                case MSG_CONNECT_REQUEST:
                    // empty response means no password
                    sendEmpty(MSG_CONNECT_RESPONSE);
                    break;
                case MSG_LIST_ENTITIES_REQUEST:
                    sendEmpty(MSG_LIST_ENTITIES_DONE);
                    break;
                case MSG_SUBSCRIBE_STATES:
                case MSG_SUBSCRIBE_HA_STATES:
                case MSG_GET_TIME_RESPONSE:
                    break;
                case MSG_DEVICE_INFO_REQUEST:
                    enqueue(buildFrame(MSG_DEVICE_INFO_RESPONSE, buildDeviceInfoResponse()));
                    break;
                case MSG_SUBSCRIBE_BLE: {
                    long flags = ProtoFields.parse(payload).varint(1);
                    if ((flags & SUBSCRIPTION_RAW_ADVERTISEMENTS) != 0) rawAds = true;
                    startBleScanning(this);
                    break;
                }
                case MSG_UNSUBSCRIBE_BLE:
                    stopBleScanning();
                    break;
                case MSG_PING_REQUEST:
                    sendEmpty(MSG_PING_RESPONSE);
                    break;
                case MSG_PING_RESPONSE:
                    // tcp is alive
                    pendingPingSentMs = 0;
                    break;
                case MSG_DISCONNECT_REQUEST:
                    sendEmpty(MSG_DISCONNECT_RESPONSE);
                    // closing right away would drop the response before the writer sends it
                    if (!outQueue.offer(WRITE_QUEUE_CLOSE_AFTER_FLUSH)) close("disconnect requested");
                    break;
                case MSG_BLUETOOTH_DEVICE_REQUEST:
                    handleBluetoothDeviceRequest(ProtoFields.parse(payload));
                    break;
                case MSG_GATT_GET_SERVICES_REQUEST:
                    handleGattGetServicesRequest(ProtoFields.parse(payload));
                    break;
                case MSG_GATT_READ_REQUEST:
                    handleGattReadRequest(ProtoFields.parse(payload));
                    break;
                case MSG_GATT_WRITE_REQUEST:
                    handleGattWriteRequest(ProtoFields.parse(payload));
                    break;
                case MSG_GATT_READ_DESCRIPTOR_REQUEST:
                    handleGattReadDescriptorRequest(ProtoFields.parse(payload));
                    break;
                case MSG_GATT_WRITE_DESCRIPTOR_REQUEST:
                    handleGattWriteDescriptorRequest(ProtoFields.parse(payload));
                    break;
                case MSG_GATT_NOTIFY_REQUEST:
                    handleGattNotifyRequest(ProtoFields.parse(payload));
                    break;
                case MSG_SUBSCRIBE_BT_CONNECTIONS_FREE:
                    subscribedConnFree = true;
                    sendConnectionsFree();
                    break;
                default:
                    Log.d(TAG, "unhandled message type " + type);
            }
        }

        // raw mode sends each scanner batch as one frame and legacy mode one frame per ad
        void forwardAdvertisements(List<BleScanner.RawAd> ads) {
            if (closed.get()) return;
            if (rawAds) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                for (BleScanner.RawAd ad : ads) {
                    byte[] raw = buildRawAdvertisement(ad);
                    // one BluetoothLERawAdvertisementsResponse holds repeated entries in field 1
                    if (raw != null) encodeLenField(out, 1, raw);
                }
                if (out.size() > 0) enqueue(buildFrame(MSG_BLE_RAW_AD_RESPONSE, out.toByteArray()));
            } else {
                for (BleScanner.RawAd ad : ads) {
                    byte[] record = buildBleScanRecord(ad);
                    if (record != null) enqueue(buildFrame(MSG_BLE_AD_RESPONSE, record));
                }
            }
        }

        private void enqueue(byte[] frame) {
            if (closed.get()) return;
            if (!outQueue.offer(frame)) noteDroppedFrame();
        }

        private void noteDroppedFrame() {
            synchronized (dropLock) {
                droppedSinceLastLog++;
                long now = System.currentTimeMillis();
                if (now - lastDropLogMs > DROP_LOG_INTERVAL_MS) {
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
            try {
                socket.close();
            } catch (IOException ignored) {
                // the peer is gone either way
            }
            cancelPeriodicTasks();
            // snapshot first since closing fires callbacks that touch the map
            List<ActiveBleConnection> snapshot = new ArrayList<>(connections.values());
            connections.clear();
            for (ActiveBleConnection c : snapshot) c.close();
            Log.i(TAG, "session closed: " + reason);
        }

        private synchronized void cancelPeriodicTasks() {
            if (pingTask != null) pingTask.cancel(false);
        }

        // ---- active connections ----

        private void handleBluetoothDeviceRequest(ProtoFields req) {
            // proto3 omits zero values so a missing request_type means DEV_REQ_CONNECT
            long addr = req.varint(1);
            int requestType = req.int32(2);
            switch (requestType) {
                case DEV_REQ_CONNECT:
                case DEV_REQ_CONNECT_V3_WITH_CACHE:
                case DEV_REQ_CONNECT_V3_WITHOUT_CACHE:
                    connectDevice(addr, requestType == DEV_REQ_CONNECT_V3_WITHOUT_CACHE);
                    break;
                case DEV_REQ_DISCONNECT:
                    disconnectDevice(addr);
                    break;
                case DEV_REQ_CLEAR_CACHE: {
                    ActiveBleConnection c = connections.get(addr);
                    boolean ok = c != null && c.clearGattCache();
                    sendDeviceResult(MSG_BT_DEVICE_CLEAR_CACHE_RESPONSE, addr, ok, ok ? 0 : ERR_CACHE_CLEAR_FAILED);
                    break;
                }
                case DEV_REQ_PAIR:
                    // PAIRING is not advertised in the feature flags
                    sendDeviceResult(MSG_BT_DEVICE_PAIRING_RESPONSE, addr, false, ERR_UNSUPPORTED);
                    break;
                case DEV_REQ_UNPAIR:
                    sendDeviceResult(MSG_BT_DEVICE_UNPAIRING_RESPONSE, addr, false, ERR_UNSUPPORTED);
                    break;
                default:
                    Log.w(TAG, "BluetoothDeviceRequest: unknown request_type " + requestType);
            }
        }

        private void connectDevice(long addr, boolean clearCache) {
            ActiveBleConnection existing = connections.get(addr);
            if (existing != null) {
                // a pending connect answers through its own callback but a ready one must answer
                // here or ha waits for a response that never comes
                if (existing.isReady()) {
                    sendConnectionStatus(addr, true, existing.getMtu(), 0);
                } else {
                    Log.i(TAG, "connect for pending " + macStr(addr) + ", waiting on it");
                }
                return;
            }
            if (connections.size() >= MAX_ACTIVE_CONNECTIONS) {
                Log.w(TAG, "connect rejected, slots full (" + connections.size() + "/" + MAX_ACTIVE_CONNECTIONS + ")");
                sendConnectionStatus(addr, false, 0, ERR_SLOTS_FULL);
                return;
            }
            BluetoothAdapter adapter = getAdapter();
            if (adapter == null) {
                sendConnectionStatus(addr, false, 0, ERR_NO_ADAPTER);
                return;
            }
            BluetoothDevice device;
            try {
                device = adapter.getRemoteDevice(macStr(addr));
            } catch (IllegalArgumentException e) {
                sendConnectionStatus(addr, false, 0, ERR_BAD_ADDRESS);
                return;
            }
            Log.i(TAG, "active connect → " + macStr(addr) + (clearCache ? " (no-cache)" : ""));
            ActiveBleConnection conn = new ActiveBleConnection(mApplicationContext, device, this, clearCache);
            connections.put(addr, conn);
            // close() flags closed before it snapshots the map so checking after the put
            // guarantees either this path or close() releases the connection
            if (closed.get() || !conn.connect()) {
                // no gatt callback will ever fire so free the slot here
                connections.remove(addr, conn);
                conn.close();
                sendConnectionStatus(addr, false, 0, ERR_CONNECT_FAILED);
            }
            sendConnectionsFreeIfSubscribed();
        }

        private void disconnectDevice(long addr) {
            ActiveBleConnection c = connections.get(addr);
            if (c == null) {
                // ha waits for a disconnected status even when nothing is connected
                sendConnectionStatus(addr, false, 0, 0);
                return;
            }
            Log.i(TAG, "active disconnect → " + macStr(addr));
            if (c.isReady()) {
                // the gatt callback reports the disconnect and frees the slot
                c.requestDisconnect();
                return;
            }
            // cancelling a pending connect does not reliably fire a callback so free the slot now
            connections.remove(addr, c);
            c.close();
            sendConnectionStatus(addr, false, 0, 0);
            sendConnectionsFreeIfSubscribed();
        }

        private void handleGattGetServicesRequest(ProtoFields req) {
            long addr = req.varint(1);
            ActiveBleConnection c = connections.get(addr);
            if (c == null) { sendGattError(addr, 0, ERR_NOT_CONNECTED); return; }
            if (!c.discoverServices()) sendGattError(addr, 0, ERR_DISCOVERY_FAILED);
        }

        private void handleGattReadRequest(ProtoFields req) {
            long addr = req.varint(1);
            int handle = req.int32(2);
            ActiveBleConnection c = connections.get(addr);
            if (c == null) { sendGattError(addr, handle, ERR_NOT_CONNECTED); return; }
            c.readCharacteristic(handle);
        }

        private void handleGattWriteRequest(ProtoFields req) {
            long addr = req.varint(1);
            int handle = req.int32(2);
            ActiveBleConnection c = connections.get(addr);
            if (c == null) { sendGattError(addr, handle, ERR_NOT_CONNECTED); return; }
            c.writeCharacteristic(handle, req.bytes(4), req.bool(3));
        }

        private void handleGattReadDescriptorRequest(ProtoFields req) {
            long addr = req.varint(1);
            int handle = req.int32(2);
            ActiveBleConnection c = connections.get(addr);
            if (c == null) { sendGattError(addr, handle, ERR_NOT_CONNECTED); return; }
            c.readDescriptor(handle);
        }

        private void handleGattWriteDescriptorRequest(ProtoFields req) {
            long addr = req.varint(1);
            int handle = req.int32(2);
            ActiveBleConnection c = connections.get(addr);
            if (c == null) { sendGattError(addr, handle, ERR_NOT_CONNECTED); return; }
            c.writeDescriptor(handle, req.bytes(3));
        }

        private void handleGattNotifyRequest(ProtoFields req) {
            long addr = req.varint(1);
            int handle = req.int32(2);
            ActiveBleConnection c = connections.get(addr);
            if (c == null) { sendGattError(addr, handle, ERR_NOT_CONNECTED); return; }
            c.setNotify(handle, req.bool(3));
        }

        // ---- ActiveBleConnection.Callback ----

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
            // one response per service followed by a done marker
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
            sendReadResult(address, handle, data, gattStatus);
        }

        @Override public void onCharWrite(long address, int handle, int gattStatus) {
            sendAckOrError(MSG_GATT_WRITE_RESPONSE, address, handle, gattStatus);
        }

        @Override public void onDescRead(long address, int handle, byte[] data, int gattStatus) {
            sendReadResult(address, handle, data, gattStatus);
        }

        @Override public void onDescWrite(long address, int handle, int gattStatus) {
            sendAckOrError(MSG_GATT_WRITE_RESPONSE, address, handle, gattStatus);
        }

        @Override public void onNotifyResult(long address, int handle, int gattStatus) {
            sendAckOrError(MSG_GATT_NOTIFY_RESPONSE, address, handle, gattStatus);
        }

        @Override public void onNotifyData(long address, int handle, byte[] data) {
            sendHandleData(MSG_GATT_NOTIFY_DATA_RESPONSE, address, handle, data != null ? data : new byte[0]);
        }

        // ---- responses ----

        private void sendEmpty(int msgType) {
            enqueue(buildFrame(msgType, new byte[0]));
        }

        private void sendReadResult(long address, int handle, byte[] data, int gattStatus) {
            if (gattStatus == 0 && data != null) {
                sendHandleData(MSG_GATT_READ_RESPONSE, address, handle, data);
            } else {
                sendGattError(address, handle, gattStatus);
            }
        }

        private void sendAckOrError(int msgType, long address, int handle, int gattStatus) {
            if (gattStatus != 0) {
                sendGattError(address, handle, gattStatus);
                return;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encodeVarintField(out, 1, address);
            encodeVarintField(out, 2, handle);
            enqueue(buildFrame(msgType, out.toByteArray()));
        }

        private void sendHandleData(int msgType, long address, int handle, byte[] data) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encodeVarintField(out, 1, address);
            encodeVarintField(out, 2, handle);
            encodeLenField(out, 3, data);
            enqueue(buildFrame(msgType, out.toByteArray()));
        }

        private void sendConnectionStatus(long address, boolean connectedFlag, int mtu, int errorCode) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encodeVarintField(out, 1, address);
            encodeVarintField(out, 2, connectedFlag ? 1 : 0);
            encodeVarintField(out, 3, mtu);
            // error is int32 in api.proto so a plain varint and not zigzag
            encodeVarintField(out, 4, errorCode);
            enqueue(buildFrame(MSG_BLUETOOTH_DEVICE_CONNECTION_RSP, out.toByteArray()));
        }

        private void sendGattError(long address, int handle, int errorCode) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encodeVarintField(out, 1, address);
            encodeVarintField(out, 2, handle);
            encodeVarintField(out, 3, errorCode);
            enqueue(buildFrame(MSG_GATT_ERROR_RESPONSE, out.toByteArray()));
        }

        // shared layout of the clear cache pairing and unpairing responses
        private void sendDeviceResult(int msgType, long address, boolean success, int errorCode) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encodeVarintField(out, 1, address);
            encodeVarintField(out, 2, success ? 1 : 0);
            encodeVarintField(out, 3, errorCode);
            enqueue(buildFrame(msgType, out.toByteArray()));
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
            // repeated uint64 allocated is packed
            if (active > 0) {
                ByteArrayOutputStream packed = new ByteArrayOutputStream();
                for (Long a : connections.keySet()) writeVarint(packed, a);
                encodeLenField(out, 3, packed.toByteArray());
            }
            enqueue(buildFrame(MSG_BT_CONNECTIONS_FREE_RESPONSE, out.toByteArray()));
        }
    }

    // ---- message builders ----

    private static byte[] buildFrame(int msgType, byte[] payload) {
        ByteArrayOutputStream f = new ByteArrayOutputStream(8 + payload.length);
        // 0x00 is plaintext and 0x01 would be noise encrypted
        f.write(0x00);
        writeVarint(f, payload.length);
        writeVarint(f, msgType);
        f.write(payload, 0, payload.length);
        return f.toByteArray();
    }

    private static String proxyName() {
        return mSharedPreferences.getString(SP_BLUETOOTH_PROXY_NAME, DEFAULT_PROXY_NAME);
    }

    private static byte[] buildHelloResponse() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeVarintField(out, 1, 1);  // api_version_major
        encodeVarintField(out, 2, 10); // api_version_minor
        encodeBytesField(out, 3, "ShellyElevate 1.0.0");
        encodeBytesField(out, 4, proxyName());
        return out.toByteArray();
    }

    private static byte[] buildDeviceInfoResponse() {
        String name  = proxyName();
        String mac   = getWifiMac();
        String btMac = getBtMac();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // field numbers from the esphome api.proto DeviceInfoResponse
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

    private static byte[] buildBleScanRecord(BleScanner.RawAd ad) {
        ScanResult result = ad.source;
        if (result == null) return null;
        try {
            long address = ad.address;
            ScanRecord record = result.getScanRecord();
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            encodeVarintField(out, 1, address);

            String deviceName = record != null ? record.getDeviceName() : null;
            if (deviceName != null && !deviceName.isEmpty()) encodeBytesField(out, 2, deviceName);

            // rssi is sint32 and needs zigzag
            encodeZigzagField(out, 3, result.getRssi());

            if (record != null) {
                List<ParcelUuid> uuids = record.getServiceUuids();
                if (uuids != null) {
                    for (ParcelUuid u : uuids) encodeBytesField(out, 4, u.getUuid().toString());
                }

                Map<ParcelUuid, byte[]> svcData = record.getServiceData();
                if (svcData != null) {
                    for (Map.Entry<ParcelUuid, byte[]> e : svcData.entrySet()) {
                        encodeLenField(out, 5, buildServiceData(e.getKey().getUuid().toString(), e.getValue()));
                    }
                }

                SparseArray<byte[]> mfrData = record.getManufacturerSpecificData();
                if (mfrData != null) {
                    for (int i = 0; i < mfrData.size(); i++) {
                        encodeLenField(out, 6, buildServiceData(
                                String.format("0x%04X", mfrData.keyAt(i)), mfrData.valueAt(i)));
                    }
                }
            }

            if (ad.addressType != 0) encodeVarintField(out, 7, ad.addressType);

            return out.toByteArray();
        } catch (RuntimeException e) {
            Log.w(TAG, "encode scan result failed: " + e.getMessage());
            return null;
        }
    }

    // raw adv payload straight from the scan record matches BluetoothLERawAdvertisement
    private static byte[] buildRawAdvertisement(BleScanner.RawAd ad) {
        if (ad.data == null) return null;
        // only public and random are meaningful here
        int addrType = ad.addressType == 1 ? 1 : 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeVarintField(out, 1, ad.address);
        encodeZigzagField(out, 2, ad.rssi);
        encodeVarintField(out, 3, addrType);
        encodeLenField(out, 4, ad.data);
        return out.toByteArray();
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
        encodeUuid128(out, 1, s.getUuid());
        encodeVarintField(out, 2, orZero(c.handleOf(s)));
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

    // ---- protobuf encoding ----

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

    // zigzag sint32 which only rssi uses in the messages sent here
    private static void encodeZigzagField(ByteArrayOutputStream out, int field, int value) {
        encodeVarintField(out, field, ((long) value << 1) ^ ((long) (value >> 31)));
    }

    private static void encodeLenField(ByteArrayOutputStream out, int field, byte[] data) {
        writeVarint(out, ((long) field << 3) | 2L);
        writeVarint(out, data.length);
        out.write(data, 0, data.length);
    }

    private static void encodeBytesField(ByteArrayOutputStream out, int field, String value) {
        encodeLenField(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    // uuid as packed repeated uint64 with the high bits first
    private static void encodeUuid128(ByteArrayOutputStream out, int field, UUID uuid) {
        ByteArrayOutputStream packed = new ByteArrayOutputStream(20);
        writeVarint(packed, uuid.getMostSignificantBits());
        writeVarint(packed, uuid.getLeastSignificantBits());
        encodeLenField(out, field, packed.toByteArray());
    }

    // ---- protobuf decoding ----

    // null on a clean eof before or inside the payload
    private static byte[] readFrame(InputStream in, int[] outMsgType) throws IOException {
        int b = in.read();
        if (b == -1) return null;
        if (b != 0x00) throw new IOException("bad preamble: 0x" + Integer.toHexString(b));
        int payloadLen = readVarint(in);
        if (payloadLen < 0 || payloadLen > MAX_FRAME_SIZE)
            throw new IOException("frame too large: " + payloadLen);
        int msgType = readVarint(in);
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
        int result = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new IOException("eof in varint");
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 28) throw new IOException("varint overflow");
        }
    }

    // request payload indexed by field number where missing fields read as zero like proto3
    private static final class ProtoFields {
        // every request handled here only uses low field numbers
        private static final int MAX_FIELD = 8;
        private final long[] varints = new long[MAX_FIELD];
        private final byte[][] bytes = new byte[MAX_FIELD][];

        static ProtoFields parse(byte[] payload) throws IOException {
            ProtoFields f = new ProtoFields();
            ProtoReader r = new ProtoReader(payload);
            while (r.hasMore()) {
                long tag = r.readVarint();
                long field = tag >>> 3;
                int wire = (int) (tag & 7);
                if (field < MAX_FIELD && wire == 0) {
                    f.varints[(int) field] = r.readVarint();
                } else if (field < MAX_FIELD && wire == 2) {
                    f.bytes[(int) field] = r.readBytes();
                } else {
                    r.skip(wire);
                }
            }
            return f;
        }

        long varint(int field)   { return varints[field]; }
        int int32(int field)     { return (int) varints[field]; }
        boolean bool(int field)  { return varints[field] != 0; }
        byte[] bytes(int field)  { return bytes[field] != null ? bytes[field] : new byte[0]; }
    }

    private static final class ProtoReader {
        private final byte[] buf;
        private int pos = 0;

        ProtoReader(byte[] buf) { this.buf = buf; }

        boolean hasMore() { return pos < buf.length; }

        long readVarint() throws IOException {
            long result = 0;
            int shift = 0;
            while (true) {
                if (pos >= buf.length) throw new IOException("eof in varint");
                int b = buf[pos++] & 0xff;
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
                if (shift >= 64) throw new IOException("varint overflow");
            }
        }

        byte[] readBytes() throws IOException {
            int len = readLength();
            byte[] b = Arrays.copyOfRange(buf, pos, pos + len);
            pos += len;
            return b;
        }

        void skip(int wireType) throws IOException {
            switch (wireType) {
                case 0: readVarint(); return;
                case 1: advance(8); return;
                case 2: advance(readLength()); return;
                case 5: advance(4); return;
                default: throw new IOException("bad wire type " + wireType);
            }
        }

        // bounds checked so a hostile length cannot move pos backwards or past the end
        private int readLength() throws IOException {
            long len = readVarint();
            if (len < 0 || len > buf.length - pos) throw new IOException("bad len-delimited size");
            return (int) len;
        }

        private void advance(int n) throws IOException {
            if (n > buf.length - pos) throw new IOException("truncated field");
            pos += n;
        }
    }

    // ---- helpers ----

    private static String macStr(long addr) {
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                (addr >> 40) & 0xff, (addr >> 32) & 0xff, (addr >> 24) & 0xff,
                (addr >> 16) & 0xff, (addr >>  8) & 0xff, addr & 0xff);
    }

    private static int orZero(Integer v) { return v != null ? v : 0; }

    private static BluetoothAdapter getAdapter() {
        return BleScanner.getAdapter();
    }

    private static String getWifiMac() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.getName().startsWith("wlan")) continue;
                byte[] hw = ni.getHardwareAddress();
                if (hw != null && hw.length == 6) {
                    return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                            hw[0], hw[1], hw[2], hw[3], hw[4], hw[5]);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "wifi mac lookup failed: " + e.getMessage());
        }
        return "00:00:00:00:00:00";
    }

    // a denied permission surfaces as a SecurityException which the catch turns into an empty mac
    @SuppressLint({"HardwareIds", "MissingPermission"})
    private static String getBtMac() {
        try {
            BluetoothAdapter a = getAdapter();
            String mac = a != null ? a.getAddress() : null;
            return mac != null ? mac : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
