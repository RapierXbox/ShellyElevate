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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// one gatt connection to a peripheral with handles numbered like the flat esphome att space
// ops are queued because android only allows one gatt call in flight at a time
@SuppressLint("MissingPermission")
public class ActiveBleConnection {
    private static final String TAG = "BtProxyConn";

    // att maximum and the stack negotiates down
    private static final int PREFERRED_MTU = 517;
    private static final int DEFAULT_MTU = 23;
    // reported when an op never reached the stack since real gatt codes stay within 0..0xff
    private static final int SYNTHETIC_GATT_FAILURE = 0x101;

    // recovers the op queue when a gatt completion never arrives
    private static final long OP_TIMEOUT_MS = 20_000;
    // no response writes only wait for the stack to release its busy flag
    private static final long NO_RESPONSE_WRITE_TIMEOUT_MS = 2_000;
    private static final ScheduledExecutorService OP_TIMEOUT_EXEC =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ble-op-timeout");
                t.setDaemon(true);
                return t;
            });

    // writing 0x0001 0x0002 or 0x0000 here turns notify or indicate on or both off
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // called from gatt binder threads the op timeout thread or the caller so keep these fast
    public interface Callback {
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
        READ_CHAR,
        WRITE_CHAR,
        // acked once the stack accepts it but kept in flight until onCharacteristicWrite
        // because android rejects the next op while its busy flag is still set
        WRITE_CHAR_NO_RESPONSE,
        READ_DESC,
        WRITE_DESC,
        // notify request from ha whose cccd write completion goes to onNotifyResult
        NOTIFY_CCCD_WRITE
    }

    private interface GattCall {
        // true when the stack accepted the request
        boolean start(BluetoothGatt g);
    }

    private static final class PendingOp {
        final OpKind kind;
        // char handle for char and notify ops and desc handle for desc ops
        final int reportHandle;
        // characteristic or descriptor this op works on so stray callbacks can be told apart
        final Object target;
        final GattCall call;
        // guarded by opLock so a timeout and a real callback cannot both finish it
        boolean settled = false;
        // success already reported to ha so a timeout must not report a failure
        volatile boolean acked = false;

        PendingOp(OpKind kind, int reportHandle, Object target, GattCall call) {
            this.kind = kind;
            this.reportHandle = reportHandle;
            this.target = target;
            this.call = call;
        }
    }

    private final Context ctx;
    private final BluetoothDevice device;
    private final long address;
    private final Callback cb;
    private final boolean clearCacheOnConnect;

    // guards gatt closed and state reports so a racing close cannot leak a slot or evict a newer connection
    private final Object gattLock = new Object();
    private volatile BluetoothGatt gatt;
    private volatile boolean closed = false;
    private volatile boolean connected = false;
    private volatile int negotiatedMtu = DEFAULT_MTU;
    private final AtomicBoolean reportedReady = new AtomicBoolean(false);

    private final Object opLock = new Object();
    private final Queue<PendingOp> opQueue = new ArrayDeque<>();
    private PendingOp currentOp;
    private ScheduledFuture<?> opTimeoutFuture;

    // handle lookups are written on discovery and read from the socket and binder threads
    private final Map<Integer, BluetoothGattCharacteristic> handleToChar = new ConcurrentHashMap<>();
    private final Map<Integer, BluetoothGattDescriptor> handleToDesc = new ConcurrentHashMap<>();
    // gatt objects do not override equals so key them by identity
    private final Map<BluetoothGattService, Integer> serviceHandle =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<BluetoothGattCharacteristic, Integer> charHandle =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<BluetoothGattDescriptor, Integer> descHandle =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public ActiveBleConnection(Context ctx, BluetoothDevice device, Callback cb, boolean clearCacheOnConnect) {
        this.ctx = ctx;
        this.device = device;
        this.address = parseMacToLong(device.getAddress());
        this.cb = cb;
        this.clearCacheOnConnect = clearCacheOnConnect;
    }

    public int getMtu() { return negotiatedMtu; }

    // true once the connected state has been reported and the link is still up
    public boolean isReady() {
        return connected && reportedReady.get() && !closed;
    }

    // false when no gatt callback will ever fire for this connection
    public boolean connect() {
        synchronized (gattLock) {
            if (closed) return false;
            BluetoothGatt g;
            try {
                // without TRANSPORT_LE the stack may pick br/edr on dual mode devices
                g = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } catch (RuntimeException e) {
                Log.w(TAG, "connectGatt failed: " + e.getMessage());
                return false;
            }
            gatt = g;
            return g != null;
        }
    }

    public void requestDisconnect() {
        BluetoothGatt g = gatt;
        if (g == null) return;
        try {
            g.disconnect();
        } catch (RuntimeException e) {
            Log.w(TAG, "disconnect failed: " + e.getMessage());
        }
    }

    public void close() {
        BluetoothGatt g;
        synchronized (gattLock) {
            if (closed) return;
            closed = true;
            g = gatt;
            gatt = null;
        }
        connected = false;
        if (g != null) {
            // disconnect first so the os releases the gatt slot cleanly
            try { g.disconnect(); } catch (RuntimeException ignored) {}
            try { g.close(); } catch (RuntimeException ignored) {}
        }
        synchronized (opLock) {
            cancelOpTimeoutLocked();
            opQueue.clear();
            currentOp = null;
        }
    }

    public boolean discoverServices() {
        BluetoothGatt g = gatt;
        if (g == null) return false;
        try {
            return g.discoverServices();
        } catch (RuntimeException e) {
            Log.w(TAG, "discoverServices failed: " + e.getMessage());
            return false;
        }
    }

    public boolean clearGattCache() {
        BluetoothGatt g = gatt;
        return g != null && refreshGattCache(g);
    }

    public List<BluetoothGattService> getServices() {
        BluetoothGatt g = gatt;
        return g != null ? g.getServices() : Collections.emptyList();
    }

    public Integer handleOf(BluetoothGattService s)        { return serviceHandle.get(s); }
    public Integer handleOf(BluetoothGattCharacteristic c) { return charHandle.get(c); }
    public Integer handleOf(BluetoothGattDescriptor d)     { return descHandle.get(d); }

    public void readCharacteristic(int handle) {
        BluetoothGattCharacteristic c = handleToChar.get(handle);
        if (c == null) { cb.onCharRead(address, handle, null, SYNTHETIC_GATT_FAILURE); return; }
        enqueue(new PendingOp(OpKind.READ_CHAR, handle, c, g -> g.readCharacteristic(c)));
    }

    public void writeCharacteristic(int handle, byte[] data, boolean withResponse) {
        BluetoothGattCharacteristic c = handleToChar.get(handle);
        if (c == null) { cb.onCharWrite(address, handle, SYNTHETIC_GATT_FAILURE); return; }
        OpKind kind = withResponse ? OpKind.WRITE_CHAR : OpKind.WRITE_CHAR_NO_RESPONSE;
        int writeType = withResponse
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        enqueue(new PendingOp(kind, handle, c, g -> gattWriteChar(g, c, data, writeType)));
    }

    public void readDescriptor(int handle) {
        BluetoothGattDescriptor d = handleToDesc.get(handle);
        if (d == null) { cb.onDescRead(address, handle, null, SYNTHETIC_GATT_FAILURE); return; }
        enqueue(new PendingOp(OpKind.READ_DESC, handle, d, g -> g.readDescriptor(d)));
    }

    public void writeDescriptor(int handle, byte[] data) {
        BluetoothGattDescriptor d = handleToDesc.get(handle);
        if (d == null) { cb.onDescWrite(address, handle, SYNTHETIC_GATT_FAILURE); return; }
        enqueue(new PendingOp(OpKind.WRITE_DESC, handle, d, g -> gattWriteDesc(g, d, data)));
    }

    public void setNotify(int charHandleId, boolean enable) {
        BluetoothGattCharacteristic c = handleToChar.get(charHandleId);
        BluetoothGattDescriptor cccd = c != null ? c.getDescriptor(CCCD_UUID) : null;
        if (cccd == null) { cb.onNotifyResult(address, charHandleId, SYNTHETIC_GATT_FAILURE); return; }

        BluetoothGatt g = gatt;
        try {
            if (g != null) g.setCharacteristicNotification(c, enable);
        } catch (RuntimeException e) {
            Log.w(TAG, "setCharacteristicNotification failed: " + e.getMessage());
        }

        byte[] value = cccdValue(c, enable);
        enqueue(new PendingOp(OpKind.NOTIFY_CCCD_WRITE, charHandleId, cccd,
                gg -> gattWriteDesc(gg, cccd, value)));
    }

    // some peripherals only support indicate so fall back to it when notify is missing
    private static byte[] cccdValue(BluetoothGattCharacteristic c, boolean enable) {
        if (!enable) return BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
        int props = c.getProperties();
        boolean indicateOnly = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                && (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
        return indicateOnly
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
    }

    @SuppressWarnings("deprecation")
    private static boolean gattWriteChar(BluetoothGatt g, BluetoothGattCharacteristic c,
                                         byte[] data, int writeType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return g.writeCharacteristic(c, data, writeType) == BluetoothStatusCodes.SUCCESS;
        }
        c.setWriteType(writeType);
        c.setValue(data);
        return g.writeCharacteristic(c);
    }

    @SuppressWarnings("deprecation")
    private static boolean gattWriteDesc(BluetoothGatt g, BluetoothGattDescriptor d, byte[] data) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return g.writeDescriptor(d, data) == BluetoothStatusCodes.SUCCESS;
        }
        d.setValue(data);
        return g.writeDescriptor(d);
    }

    // refresh is hidden api but the only way to invalidate the android gatt cache
    private static boolean refreshGattCache(BluetoothGatt g) {
        try {
            Method refresh = BluetoothGatt.class.getMethod("refresh");
            Object result = refresh.invoke(g);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            Log.w(TAG, "gatt.refresh() unavailable: " + t.getMessage());
            return false;
        }
    }

    private void enqueue(PendingOp op) {
        boolean rejected = false;
        boolean startNow = false;
        synchronized (opLock) {
            if (closed) {
                rejected = true;
            } else if (currentOp == null) {
                currentOp = op;
                armOpTimeoutLocked(op);
                startNow = true;
            } else {
                opQueue.add(op);
            }
        }
        if (rejected) reportOpFailure(op);
        else if (startNow) startOp(op);
    }

    // runs outside opLock because the stack may call back synchronously
    private void startOp(PendingOp op) {
        BluetoothGatt g = gatt;
        boolean accepted = false;
        if (g != null) {
            try {
                accepted = op.call.start(g);
            } catch (RuntimeException e) {
                Log.w(TAG, "gatt op " + op.kind + " failed to start: " + e.getMessage());
            }
        }
        if (!accepted) {
            if (finishOp(op)) reportOpFailure(op);
        } else if (op.kind == OpKind.WRITE_CHAR_NO_RESPONSE) {
            op.acked = true;
            cb.onCharWrite(address, op.reportHandle, BluetoothGatt.GATT_SUCCESS);
        }
    }

    // settles op if it is still in flight then starts the next queued op
    private boolean finishOp(PendingOp op) {
        PendingOp next;
        synchronized (opLock) {
            if (op.settled || currentOp != op) return false;
            op.settled = true;
            cancelOpTimeoutLocked();
            currentOp = opQueue.poll();
            next = currentOp;
            if (next != null) armOpTimeoutLocked(next);
        }
        if (next != null) startOp(next);
        return true;
    }

    // settles the op in flight only when a gatt callback belongs to it
    // a late callback from a timed out op must not complete the op queued after it
    private PendingOp finishCurrent(Object target, OpKind... kinds) {
        PendingOp op;
        synchronized (opLock) {
            op = currentOp;
        }
        if (op == null || op.target != target) return null;
        for (OpKind k : kinds) {
            if (op.kind == k) return finishOp(op) ? op : null;
        }
        return null;
    }

    // must hold opLock
    private void armOpTimeoutLocked(PendingOp op) {
        cancelOpTimeoutLocked();
        long timeoutMs = op.kind == OpKind.WRITE_CHAR_NO_RESPONSE ? NO_RESPONSE_WRITE_TIMEOUT_MS : OP_TIMEOUT_MS;
        opTimeoutFuture = OP_TIMEOUT_EXEC.schedule(() -> onOpTimeout(op), timeoutMs, TimeUnit.MILLISECONDS);
    }

    // must hold opLock
    private void cancelOpTimeoutLocked() {
        if (opTimeoutFuture != null) {
            opTimeoutFuture.cancel(false);
            opTimeoutFuture = null;
        }
    }

    private void onOpTimeout(PendingOp op) {
        if (!finishOp(op)) return;
        Log.w(TAG, "gatt op timed out kind=" + op.kind + " handle=" + op.reportHandle);
        if (!op.acked) reportOpFailure(op);
    }

    private void reportOpFailure(PendingOp op) {
        switch (op.kind) {
            case READ_CHAR:
                cb.onCharRead(address, op.reportHandle, null, SYNTHETIC_GATT_FAILURE);
                break;
            case WRITE_CHAR:
            case WRITE_CHAR_NO_RESPONSE:
                cb.onCharWrite(address, op.reportHandle, SYNTHETIC_GATT_FAILURE);
                break;
            case READ_DESC:
                cb.onDescRead(address, op.reportHandle, null, SYNTHETIC_GATT_FAILURE);
                break;
            case WRITE_DESC:
                cb.onDescWrite(address, op.reportHandle, SYNTHETIC_GATT_FAILURE);
                break;
            case NOTIFY_CCCD_WRITE:
                cb.onNotifyResult(address, op.reportHandle, SYNTHETIC_GATT_FAILURE);
                break;
        }
    }

    // every callback bails once closed so a stale gatt cannot report into the session
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (closed) return;
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                if (clearCacheOnConnect) refreshGattCache(g);
                boolean mtuRequested = false;
                try {
                    mtuRequested = g.requestMtu(PREFERRED_MTU);
                } catch (RuntimeException e) {
                    Log.w(TAG, "requestMtu failed: " + e.getMessage());
                }
                // onMtuChanged never fires without a request in flight
                if (!mtuRequested) reportConnectedOnce();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                    || status != BluetoothGatt.GATT_SUCCESS) {
                // held so a racing close cannot let this stale report evict a newer connection
                synchronized (gattLock) {
                    if (closed) return;
                    connected = false;
                    cb.onConnectionStateChanged(address, false, 0, status);
                    // release the os gatt slot since ha reconnects on its own
                    close();
                }
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (closed) return;
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu;
            reportConnectedOnce();
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (closed) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                cb.onServicesError(address, status);
                return;
            }
            assignHandles(g);
            cb.onServicesReady(address);
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                   byte[] value, int status) {
            if (closed) return;
            PendingOp op = finishCurrent(c, OpKind.READ_CHAR);
            if (op == null) { Log.d(TAG, "ignoring stray characteristic read callback"); return; }
            byte[] data = status == BluetoothGatt.GATT_SUCCESS ? value : null;
            cb.onCharRead(address, op.reportHandle, data, status);
        }

        // pre api 33 path
        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            byte[] v = c.getValue();
            onCharacteristicRead(g, c, v != null ? v : new byte[0], status);
        }

        // no response writes were acked on start so they only release the queue here
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (closed) return;
            PendingOp op = finishCurrent(c, OpKind.WRITE_CHAR, OpKind.WRITE_CHAR_NO_RESPONSE);
            if (op == null || op.kind == OpKind.WRITE_CHAR_NO_RESPONSE) return;
            cb.onCharWrite(address, op.reportHandle, status);
        }

        @Override public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d,
                                               int status, byte[] value) {
            if (closed) return;
            PendingOp op = finishCurrent(d, OpKind.READ_DESC);
            if (op == null) { Log.d(TAG, "ignoring stray descriptor read callback"); return; }
            byte[] data = status == BluetoothGatt.GATT_SUCCESS ? value : null;
            cb.onDescRead(address, op.reportHandle, data, status);
        }

        // pre api 33 path
        @Override @SuppressWarnings("deprecation")
        public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            byte[] v = d.getValue();
            onDescriptorRead(g, d, status, v != null ? v : new byte[0]);
        }

        // cccd writes from setNotify go to onNotifyResult and plain ones to onDescWrite
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (closed) return;
            PendingOp op = finishCurrent(d, OpKind.WRITE_DESC, OpKind.NOTIFY_CCCD_WRITE);
            if (op == null) { Log.d(TAG, "ignoring stray descriptor write callback"); return; }
            if (op.kind == OpKind.NOTIFY_CCCD_WRITE) {
                cb.onNotifyResult(address, op.reportHandle, status);
            } else {
                cb.onDescWrite(address, op.reportHandle, status);
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                      byte[] value) {
            if (closed) return;
            int handle = orZero(charHandle.get(c));
            cb.onNotifyData(address, handle, value != null ? value : new byte[0]);
        }

        // pre api 33 path
        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            onCharacteristicChanged(g, c, v != null ? v : new byte[0]);
        }
    };

    // onMtuChanged and the failed request fallback can both land here
    private void reportConnectedOnce() {
        synchronized (gattLock) {
            if (closed || !reportedReady.compareAndSet(false, true)) return;
            if (connected) cb.onConnectionStateChanged(address, true, negotiatedMtu, 0);
        }
    }

    // numbering restarts on every discovery so the same gatt table always gets the same handles
    private synchronized void assignHandles(BluetoothGatt g) {
        handleToChar.clear();
        handleToDesc.clear();
        serviceHandle.clear();
        charHandle.clear();
        descHandle.clear();
        int next = 1;
        for (BluetoothGattService s : g.getServices()) {
            serviceHandle.put(s, next++);
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int ch = next++;
                handleToChar.put(ch, c);
                charHandle.put(c, ch);
                for (BluetoothGattDescriptor d : c.getDescriptors()) {
                    int dh = next++;
                    handleToDesc.put(dh, d);
                    descHandle.put(d, dh);
                }
            }
        }
    }

    private static int orZero(Integer v) { return v != null ? v : 0; }

    // "AA:BB:CC:DD:EE:FF" to 0x0000AABBCCDDEEFF
    private static long parseMacToLong(String mac) {
        long addr = 0;
        for (String p : mac.split(":")) addr = (addr << 8) | Integer.parseInt(p, 16);
        return addr;
    }
}
