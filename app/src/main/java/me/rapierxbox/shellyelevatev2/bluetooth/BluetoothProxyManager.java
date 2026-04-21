package me.rapierxbox.shellyelevatev2.bluetooth;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.*;

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
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.RequiresPermission;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// esphome server api
// frame format: 0x00 payload_size msg_type payload
// only one ha client
public class BluetoothProxyManager {
    private static final String TAG = "BtProxy";
    private static final int PORT = 6053;

    // esphome message type ids
    private static final int MSG_HELLO_REQUEST        = 1;
    private static final int MSG_HELLO_RESPONSE       = 2;
    private static final int MSG_CONNECT_REQUEST      = 3;
    private static final int MSG_CONNECT_RESPONSE     = 4;
    private static final int MSG_DISCONNECT_REQUEST   = 5;
    private static final int MSG_DISCONNECT_RESPONSE  = 6;
    private static final int MSG_PING_REQUEST         = 7;
    private static final int MSG_PING_RESPONSE        = 8;
    private static final int MSG_DEVICE_INFO_REQUEST  = 9;
    private static final int MSG_DEVICE_INFO_RESPONSE = 10;
    private static final int MSG_LIST_ENTITIES_REQUEST = 11;
    private static final int MSG_LIST_ENTITIES_DONE         = 19;
    private static final int MSG_SUBSCRIBE_STATES           = 20; // no response (we dont have entities)
    private static final int MSG_SUBSCRIBE_HA_STATES        = 34; // no response (we dont need ha states)
    private static final int MSG_GET_TIME_RESPONSE          = 38; // ha pushes time.. ignore
    private static final int MSG_SUBSCRIBE_BLE              = 66;
    private static final int MSG_BLE_AD_RESPONSE      = 67;
    private static final int MSG_UNSUBSCRIBE_BLE      = 87;

    // bit 0 = FEATURE_PASSIVE_SCAN
    private static final int BT_PROXY_FLAGS = 1;
    // legacy version 2 = passive scan (no active connections), ha falls back if flags=0
    private static final int BT_LEGACY_VERSION = 2;


    private static final byte[] WRITE_QUEUE_SENTINEL = new byte[0]; // writeloop exit

    private volatile boolean      enabled = false;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private volatile ServerSocket serverSocket;
    private final AtomicReference<ClientSession> activeSession = new AtomicReference<>();
    private volatile BluetoothLeScanner bleScanner;
    private volatile ScanCallback        activeScanCb;

    private final AtomicReference<ClientSession> scanTarget = new AtomicReference<>(); // routes ads to current session
    private NsdManager                   nsdManager;
    private NsdManager.RegistrationListener nsdListener;
    private final BroadcastReceiver settingsReceiver;

    
    public BluetoothProxyManager() {
        settingsReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { checkAndApplySettings(); }
        };
        LocalBroadcastManager.getInstance(mApplicationContext)
                .registerReceiver(settingsReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));
        checkAndApplySettings();
    }

    public void checkAndApplySettings() {
        boolean want = mSharedPreferences.getBoolean(SP_BLUETOOTH_PROXY_ENABLED, false);
        if (want && !enabled) {
            enabled = true;
            startServer();
        } else if (!want && enabled) {
            enabled = false;
            shutdown();
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
                        if (enabled) Log.e(TAG, "accept error", e);
                        break;
                    }
                    Log.i(TAG, "HA connected from " + client.getInetAddress());
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

    
    @SuppressLint("MissingPermission")
    private void stopBleScanning() {
        scanTarget.set(null);
        ScanCallback cb = activeScanCb;
        if (cb != null && bleScanner != null) {
            try { bleScanner.stopScan(cb); } catch (Exception ignored) {}
            activeScanCb = null;
            Log.d(TAG, "BLE scan stopped");
        }
    }

    private void closeServerSocket() {
        ServerSocket ss = serverSocket;
        if (ss != null && !ss.isClosed()) { try { ss.close(); } catch (IOException ignored) {} }
    }

    
    private void shutdown() {
        stopBleScanning();
        ClientSession s = activeSession.getAndSet(null);
        if (s != null) s.close("shutdown");
        closeServerSocket();
        Log.i(TAG, "shutdown");
    }

    public void onDestroy() {
        enabled = false;
        shutdown();
        LocalBroadcastManager.getInstance(mApplicationContext).unregisterReceiver(settingsReceiver);
        executor.shutdownNow();
    }

    private void registerNsd() { // should work... but not tested
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

    private class ClientSession {
        private final Socket socket;
        private final BlockingQueue<byte[]> outQueue = new LinkedBlockingQueue<>(2000); // write que so scan callbacks dont block on tcp writes
        private final AtomicBoolean closed = new AtomicBoolean(false);

        ClientSession(Socket socket) { this.socket = socket; }

        
        void run() {
            executor.execute(this::writeLoop);
            try {
                InputStream in = socket.getInputStream();
                while (!closed.get() && enabled) {
                    int[] msgType = new int[1];
                    byte[] payload = readFrame(in, msgType);
                    if (payload == null) break;
                    handleMessage(msgType[0], payload);
                }
            } catch (IOException e) {
                if (!closed.get()) Log.d(TAG, "client disconnected: " + e.getMessage());
            } finally {
                close("session ended");
                if (activeSession.compareAndSet(this, null)) {
                    stopBleScanning();
                    Log.i(TAG, "session cleaned up");
                }
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
                if (!closed.get()) Log.d(TAG, "write error: " + e.getMessage());
            }
        }

        
        private void handleMessage(int type, byte[] payload) {
            switch (type) {
                case MSG_HELLO_REQUEST:
                    enqueue(buildFrame(MSG_HELLO_RESPONSE, buildHelloResponse()));
                    break;
                case MSG_CONNECT_REQUEST:
                    enqueue(buildFrame(MSG_CONNECT_RESPONSE, new byte[0])); // empty payload -> no auth
                    break;
                case MSG_LIST_ENTITIES_REQUEST:
                    enqueue(buildFrame(MSG_LIST_ENTITIES_DONE, new byte[0]));
                    break;
                case MSG_SUBSCRIBE_STATES:
                case MSG_SUBSCRIBE_HA_STATES:
                case MSG_GET_TIME_RESPONSE:
                    break; // dont need those
                case MSG_DEVICE_INFO_REQUEST:
                    enqueue(buildFrame(MSG_DEVICE_INFO_RESPONSE, buildDeviceInfoResponse()));
                    break;
                case MSG_SUBSCRIBE_BLE:
                    startBleScanning(this);
                    break;
                case MSG_UNSUBSCRIBE_BLE:
                    stopBleScanning();
                    break;
                case MSG_PING_REQUEST:
                    enqueue(buildFrame(MSG_PING_RESPONSE, new byte[0]));
                    break;
                case MSG_DISCONNECT_REQUEST:
                    enqueue(buildFrame(MSG_DISCONNECT_RESPONSE, new byte[0]));
                    close("disconnect requested");
                    break;
                default:
                    Log.d(TAG, "unhandled message type " + type);
            }
        }

        void sendAdvertisement(byte[] payload) {
            enqueue(buildFrame(MSG_BLE_AD_RESPONSE, payload));
        }

        private void enqueue(byte[] frame) {
            if (!outQueue.offer(frame)) Log.d(TAG, "write queue full, dropping ad");
        }

        void close(String reason) {
            if (closed.getAndSet(true)) return;
            outQueue.offer(WRITE_QUEUE_SENTINEL);
            try { socket.close(); } catch (IOException ignored) {}
            Log.d(TAG, "closed: " + reason);
        }
    }

    private void startBleScanning(ClientSession session) {
        scanTarget.set(session);

        if (activeScanCb != null) {
            Log.d(TAG, "BLE scan already running, redirected to new session");
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
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        activeScanCb = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                ClientSession target = scanTarget.get();
                if (target == null) return;
                byte[] ad = buildBleScanRecord(result);
                if (ad != null) target.sendAdvertisement(ad);
            }
            @Override public void onScanFailed(int errorCode) {
                Log.e(TAG, "BLE scan failed: " + errorCode);
            }
        };

        try {
            bleScanner.startScan(null, settings, activeScanCb);
            Log.i(TAG, "BLE scan started");
        } catch (SecurityException e) {
            Log.e(TAG, "BLE scan permission denied, grant BLUETOOTH_SCAN / ACCESS_FINE_LOCATION");
            activeScanCb = null;
        }
    }

    // protobuf builder
    private static byte[] buildFrame(int msgType, byte[] payload) {
        ByteArrayOutputStream f = new ByteArrayOutputStream(8 + payload.length);
        f.write(0x00); // plaintext indicator
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
        encodeBytesField(out, 2,  name);
        encodeBytesField(out, 3,  mac);
        encodeBytesField(out, 4,  "2026.4.0");           // esphome version
        encodeBytesField(out, 6,  "android-bt-proxy");    // model
        encodeVarintField(out, 11, BT_LEGACY_VERSION);    // legacy_bluetooth_proxy_version
        encodeBytesField(out, 12, "Android");             // manufacturer
        encodeBytesField(out, 13, name);                  // friendly_name
        encodeVarintField(out, 15, BT_PROXY_FLAGS);       // bluetooth_proxy_feature_flags
        if (!btMac.isEmpty()) encodeBytesField(out, 18, btMac); // bluetooth_mac_address
        Log.d(TAG, "DeviceInfoResponse: flags=" + BT_PROXY_FLAGS + " legacyVer=" + BT_LEGACY_VERSION
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

            // rssi is sint32 must be zigzag-encoded
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

    private static byte[] buildServiceData(String uuid, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeBytesField(out, 1, uuid);
        if (data != null && data.length > 0) encodeLenField(out, 3, data);
        return out.toByteArray();
    }

    // protobuf wire helpers

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0L) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void encodeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, (long) field << 3); // wire type 0
        writeVarint(out, value);
    }

    // sint32 uses zigzag encoding: (n<<1) ^ (n>>31)
    private static void encodeZigzagField(ByteArrayOutputStream out, int field, int value) {
        encodeVarintField(out, field, (((long) value << 1)) ^ ((long) (value >> 31)));
    }

    private static void encodeLenField(ByteArrayOutputStream out, int field, byte[] data) {
        writeVarint(out, ((long) field << 3) | 2L); // wire type 2
        writeVarint(out, data.length);
        out.write(data, 0, data.length);
    }

    private static void encodeBytesField(ByteArrayOutputStream out, int field, String value) {
        encodeLenField(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readFrame(InputStream in, int[] outMsgType) throws IOException {
        int b = in.read();
        if (b == -1) return null;
        if (b != 0x00) throw new IOException("bad preamble: 0x" + Integer.toHexString(b));
        int payloadLen = readVarint(in);
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

    private static long parseMacToLong(String mac) {
        String[] parts = mac.split(":");
        long addr = 0;
        for (String p : parts) addr = (addr << 8) | Integer.parseInt(p, 16);
        return addr;
    }

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
