package me.rapierxbox.shellyelevatev2.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

// One GATT connection to a peripheral. Handle numbering matches ESPHome's flat ATT space.
// Ops are queued because Android only allows one in-flight GATT call at a time.
@SuppressLint("MissingPermission")
public class ActiveBleConnection {
    private static final String TAG = "BtProxyConn";

    private static final int PREFERRED_MTU = 517; // ATT max; stack negotiates down
    // Used when the op never reached the stack (gatt null, write rejected, etc). Real GATT codes are 0..0xFF.
    private static final int SYNTHETIC_GATT_FAILURE = 0x101;

    // Writing 0x0001/0x0002/0x0000 here enables notify/indicate/off.
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final byte[] CCCD_ENABLE_NOTIFY   = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
    private static final byte[] CCCD_ENABLE_INDICATE = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
    private static final byte[] CCCD_DISABLE         = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;

    public interface Callback {
        // All on the GATT binder thread — keep these fast or you'll stall the BT stack.
        void onConnectionStateChanged(long address, boolean connected, int mtu, int errorCode);
        void onServicesReady(long address);
        void onServicesError(long address, int gattStatus);
        void onCharRead(long address, int handle, byte[] data, int gattStatus);
        void onCharWrite(long address, int handle, int gattStatus);
        void onDescRead(long address, int handle, byte[] data, int gattStatus);
        void onDescWrite(long address, int handle, int gattStatus);
        void onNotifyResult(long address, int handle, int gattStatus);
        void onNotifyData(long address, int handle, byte[] data);
    }

    private enum OpKind {
        READ_CHAR, WRITE_CHAR, READ_DESC, WRITE_DESC,
        // HA's notify request (msg 78); completion reports to onNotifyResult, not onDescWrite
        NOTIFY_CCCD_WRITE
    }

    private static class PendingOp {
        final OpKind kind;
        final int reportHandle;  // char handle for char/notify ops; desc handle for desc ops
        final Runnable start;    // kicks off the BluetoothGatt call
        PendingOp(OpKind k, int h, Runnable s) { kind = k; reportHandle = h; start = s; }
    }

    private final Context ctx;
    private final BluetoothDevice device;
    private final long address;
    private final Callback cb;
    private final boolean clearCacheOnConnect;

    private volatile BluetoothGatt gatt;
    private volatile int negotiatedMtu = 23;
    private volatile boolean connected = false;
    private volatile boolean closed = false;
    private volatile boolean reportedReady = false;

    private final Object opLock = new Object();
    private final Queue<PendingOp> opQueue = new ArrayDeque<>();
    private PendingOp currentOp;

    // Single counter across services/chars/descriptors, matching ESPHome's flat ATT handle space.
    private int nextHandle = 1;
    private final Map<Integer, BluetoothGattService>        handleToService = new HashMap<>();
    private final Map<Integer, BluetoothGattCharacteristic> handleToChar    = new HashMap<>();
    private final Map<Integer, BluetoothGattDescriptor>     handleToDesc    = new HashMap<>();
    private final Map<BluetoothGattService,        Integer> serviceHandle  = new IdentityHashMap<>();
    private final Map<BluetoothGattCharacteristic, Integer> charHandle     = new IdentityHashMap<>();
    private final Map<BluetoothGattDescriptor,     Integer> descHandle     = new IdentityHashMap<>();

    public ActiveBleConnection(Context ctx, BluetoothDevice device, Callback cb) {
        this(ctx, device, cb, false);
    }

    public ActiveBleConnection(Context ctx, BluetoothDevice device, Callback cb, boolean clearCacheOnConnect) {
        this.ctx = ctx;
        this.device = device;
        this.address = parseMacToLong(device.getAddress());
        this.cb = cb;
        this.clearCacheOnConnect = clearCacheOnConnect;
    }

    public long getAddress()  { return address; }
    public int  getMtu()      { return negotiatedMtu; }
    public boolean isConnected() { return connected; }

    public void connect() {
        BluetoothGatt g;
        // without TRANSPORT_LE the stack may pick BR/EDR on dual-mode devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            g = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            g = device.connectGatt(ctx, false, gattCallback);
        }
        this.gatt = g;
    }

    public void requestDisconnect() {
        BluetoothGatt g = gatt;
        if (g != null) {
            try { g.disconnect(); } catch (Exception ignored) {}
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        BluetoothGatt g = gatt;
        if (g != null) {
            try { g.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        synchronized (opLock) {
            opQueue.clear();
            currentOp = null;
        }
    }

    public boolean discoverServices() {
        BluetoothGatt g = gatt;
        if (g == null) return false;
        try { return g.discoverServices(); } catch (Exception e) { return false; }
    }

    // refresh() is hidden API but the only way to invalidate Android's GATT cache.
    public boolean clearGattCache() {
        BluetoothGatt g = gatt;
        if (g == null) return false;
        try {
            Method refresh = BluetoothGatt.class.getMethod("refresh");
            Object r = refresh.invoke(g);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            Log.w(TAG, "gatt.refresh() unavailable: " + t.getMessage());
            return false;
        }
    }

    public List<BluetoothGattService> getServices() {
        BluetoothGatt g = gatt;
        return g != null ? g.getServices() : Collections.emptyList();
    }

    public Integer handleOf(BluetoothGattService s)        { return serviceHandle.get(s); }
    public Integer handleOf(BluetoothGattCharacteristic c) { return charHandle.get(c); }
    public Integer handleOf(BluetoothGattDescriptor d)     { return descHandle.get(d); }
    public BluetoothGattCharacteristic charByHandle(int h) { return handleToChar.get(h); }
    public BluetoothGattDescriptor     descByHandle(int h) { return handleToDesc.get(h); }

    public void readCharacteristic(int handle) {
        BluetoothGattCharacteristic c = handleToChar.get(handle);
        if (c == null) { cb.onCharRead(address, handle, null, SYNTHETIC_GATT_FAILURE); return; }
        enqueue(new PendingOp(OpKind.READ_CHAR, handle, () -> {
            BluetoothGatt g = gatt;
            boolean ok = g != null;
            try { ok = ok && g.readCharacteristic(c); } catch (Exception e) { ok = false; }
            if (!ok) {
                completeAndAdvance();
                cb.onCharRead(address, handle, null, SYNTHETIC_GATT_FAILURE);
            }
        }));
    }

    public void writeCharacteristic(int handle, byte[] data, boolean withResponse) {
        BluetoothGattCharacteristic c = handleToChar.get(handle);
        if (c == null) { cb.onCharWrite(address, handle, SYNTHETIC_GATT_FAILURE); return; }
        enqueue(new PendingOp(OpKind.WRITE_CHAR, handle, () -> {
            BluetoothGatt g = gatt;
            int writeType = withResponse
                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            boolean queued = false;
            try {
                if (g != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        queued = g.writeCharacteristic(c, data, writeType) == BluetoothStatusCodes.SUCCESS;
                    } else {
                        c.setWriteType(writeType);
                        c.setValue(data);
                        queued = g.writeCharacteristic(c);
                    }
                }
            } catch (Exception ignored) {}
            // no-response writes don't reliably fire onCharacteristicWrite, so ack immediately
            if (!withResponse && queued) {
                completeAndAdvance();
                cb.onCharWrite(address, handle, BluetoothGatt.GATT_SUCCESS);
            } else if (!queued) {
                completeAndAdvance();
                cb.onCharWrite(address, handle, SYNTHETIC_GATT_FAILURE);
            }
        }));
    }

    public void readDescriptor(int handle) {
        BluetoothGattDescriptor d = handleToDesc.get(handle);
        if (d == null) { cb.onDescRead(address, handle, null, SYNTHETIC_GATT_FAILURE); return; }
        enqueue(new PendingOp(OpKind.READ_DESC, handle, () -> {
            BluetoothGatt g = gatt;
            boolean ok = g != null;
            try { ok = ok && g.readDescriptor(d); } catch (Exception e) { ok = false; }
            if (!ok) {
                completeAndAdvance();
                cb.onDescRead(address, handle, null, SYNTHETIC_GATT_FAILURE);
            }
        }));
    }

    public void writeDescriptor(int handle, byte[] data) {
        BluetoothGattDescriptor d = handleToDesc.get(handle);
        if (d == null) { cb.onDescWrite(address, handle, SYNTHETIC_GATT_FAILURE); return; }
        enqueue(new PendingOp(OpKind.WRITE_DESC, handle, () -> {
            boolean ok = doWriteDescriptor(d, data);
            if (!ok) {
                completeAndAdvance();
                cb.onDescWrite(address, handle, SYNTHETIC_GATT_FAILURE);
            }
        }));
    }

    public void setNotify(int charHandleId, boolean enable) {
        BluetoothGattCharacteristic c = handleToChar.get(charHandleId);
        if (c == null) { cb.onNotifyResult(address, charHandleId, SYNTHETIC_GATT_FAILURE); return; }
        BluetoothGattDescriptor cccd = c.getDescriptor(CCCD_UUID);
        if (cccd == null) { cb.onNotifyResult(address, charHandleId, SYNTHETIC_GATT_FAILURE); return; }

        BluetoothGatt g = gatt;
        try { if (g != null) g.setCharacteristicNotification(c, enable); } catch (Exception ignored) {}

        // some devices only support indicate or only notify, check properties
        boolean indicate = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
        byte[] value = enable ? (indicate ? CCCD_ENABLE_INDICATE : CCCD_ENABLE_NOTIFY) : CCCD_DISABLE;

        enqueue(new PendingOp(OpKind.NOTIFY_CCCD_WRITE, charHandleId, () -> {
            boolean ok = doWriteDescriptor(cccd, value);
            if (!ok) {
                completeAndAdvance();
                cb.onNotifyResult(address, charHandleId, SYNTHETIC_GATT_FAILURE);
            }
        }));
    }

    private boolean doWriteDescriptor(BluetoothGattDescriptor d, byte[] data) {
        BluetoothGatt g = gatt;
        if (g == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return g.writeDescriptor(d, data) == BluetoothStatusCodes.SUCCESS;
            }
            d.setValue(data);
            return g.writeDescriptor(d);
        } catch (Exception e) {
            return false;
        }
    }

    private void enqueue(PendingOp op) {
        Runnable toRun = null;
        synchronized (opLock) {
            if (closed) return;
            if (currentOp == null) {
                currentOp = op;
                toRun = op.start;
            } else {
                opQueue.add(op);
            }
        }
        if (toRun != null) toRun.run();
    }

    private void completeAndAdvance() {
        Runnable next = null;
        synchronized (opLock) {
            currentOp = opQueue.poll();
            if (currentOp != null) next = currentOp.start;
        }
        if (next != null) next.run();
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true;
                // clear cache before discovery if requested; best-effort since refresh() is hidden API
                if (clearCacheOnConnect) {
                    try {
                        Method refresh = BluetoothGatt.class.getMethod("refresh");
                        refresh.invoke(g);
                    } catch (Throwable t) {
                        Log.w(TAG, "clearCacheOnConnect: refresh() unavailable: " + t.getMessage());
                    }
                }
                boolean queued = false;
                try { queued = g.requestMtu(PREFERRED_MTU); } catch (Exception ignored) {}
                if (!queued) reportConnectedOnce(); // MTU request failed, skip waiting for onMtuChanged
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean wasConnected = connected;
                connected = false;
                cb.onConnectionStateChanged(address, false, 0, status);
                // release the OS gatt slot; HA reconnects on its own
                if (wasConnected || gatt != null) close();
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu;
            reportConnectedOnce();
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                cb.onServicesError(address, status);
                return;
            }
            assignHandlesUnsafe();
            cb.onServicesReady(address);
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                    byte[] value, int status) {
            int handle = orZero(charHandle.get(c));
            byte[] data = (status == BluetoothGatt.GATT_SUCCESS) ? value : null;
            completeAndAdvance();
            cb.onCharRead(address, handle, data, status);
        }
        // pre-API 33 compat
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            byte[] v = c.getValue();
            onCharacteristicRead(g, c, v != null ? v : new byte[0], status);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            // no-response writes already acked in writeCharacteristic; ignore this sometimes-spurious callback
            OpKind kind;
            synchronized (opLock) { kind = currentOp != null ? currentOp.kind : null; }
            if (kind != OpKind.WRITE_CHAR) return;
            int handle = orZero(charHandle.get(c));
            completeAndAdvance();
            cb.onCharWrite(address, handle, status);
        }

        @Override public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d,
                                                int status, byte[] value) {
            int handle = orZero(descHandle.get(d));
            byte[] data = (status == BluetoothGatt.GATT_SUCCESS) ? value : null;
            completeAndAdvance();
            cb.onDescRead(address, handle, data, status);
        }
        @Override public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            byte[] v = d.getValue();
            onDescriptorRead(g, d, status, v != null ? v : new byte[0]);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            // CCCD writes from setNotify() → onNotifyResult; plain desc writes → onDescWrite
            OpKind kind;
            int reportHandle;
            synchronized (opLock) {
                kind = currentOp != null ? currentOp.kind : null;
                reportHandle = currentOp != null ? currentOp.reportHandle : orZero(descHandle.get(d));
            }
            completeAndAdvance();
            if (kind == OpKind.NOTIFY_CCCD_WRITE) {
                cb.onNotifyResult(address, reportHandle, status);
            } else {
                cb.onDescWrite(address, reportHandle, status);
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            int handle = orZero(charHandle.get(c));
            cb.onNotifyData(address, handle, value != null ? value : new byte[0]);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            onCharacteristicChanged(g, c, v != null ? v : new byte[0]);
        }
    };

    // guard against double-firing from onMtuChanged and the requestMtu fallback
    private void reportConnectedOnce() {
        if (reportedReady) return;
        reportedReady = true;
        if (connected) cb.onConnectionStateChanged(address, true, negotiatedMtu, 0);
    }

    private void assignHandlesUnsafe() {
        BluetoothGatt g = gatt;
        if (g == null) return;
        for (BluetoothGattService s : g.getServices()) {
            int sh = nextHandle++;
            handleToService.put(sh, s);
            serviceHandle.put(s, sh);
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int ch = nextHandle++;
                handleToChar.put(ch, c);
                charHandle.put(c, ch);
                for (BluetoothGattDescriptor d : c.getDescriptors()) {
                    int dh = nextHandle++;
                    handleToDesc.put(dh, d);
                    descHandle.put(d, dh);
                }
            }
        }
    }

    private static int orZero(Integer v) { return v != null ? v : 0; }

    private static long parseMacToLong(String mac) {
        String[] parts = mac.split(":");
        long addr = 0;
        for (String p : parts) addr = (addr << 8) | Integer.parseInt(p, 16);
        return addr;
    }
}
