package me.rapierxbox.shellyelevatev2.stes;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import me.rapierxbox.shellyelevatev2.helper.UartHelper;

public class StesProtocolHandler {

    private static final String TAG = "STES";
    private static final byte HEADER_0 = (byte) 0xAA;
    private static final byte HEADER_1 = (byte) 0x55;
    private static final int DEFAULT_GAMMA = 50;

    // calibration modes (SC_CMD_CALIBRATE payload byte)
    public static final byte CALIB_CLEAR = 0;
    public static final byte CALIB_FULL  = 1;
    public static final byte CALIB_SHORT = 2;

    static UartHelper sUart;
    private static volatile boolean sOperational = false;

    public static volatile DimmerStatus lastStatus;
    public static volatile DimmerPower  lastPower;
    public static volatile DimmerConfig lastConfig;

    @FunctionalInterface public interface OnDimmerListener  { void onResult(DimmerStatus s); default void onError(String e) { Log.w(TAG, "OnDimmerListener: " + e); } }
    @FunctionalInterface public interface OnStatusListener  { void onResult(DimmerStatus s); default void onError(String e) { Log.w(TAG, "OnStatusListener: " + e); } }
    @FunctionalInterface public interface OnPowerListener   { void onResult(DimmerPower p);  default void onError(String e) { Log.w(TAG, "OnPowerListener: "  + e); } }
    @FunctionalInterface public interface OnVersionListener { void onResult(String version); default void onError(String e) { Log.w(TAG, "OnVersionListener: " + e); } }
    @FunctionalInterface public interface OnConfigListener  { void onResult(DimmerConfig c); default void onError(String e) { Log.w(TAG, "OnConfigListener: "  + e); } }
    @FunctionalInterface public interface OnRelayListener   { void onResult(boolean state);  default void onError(String e) { Log.w(TAG, "OnRelayListener: "   + e); } }
    @FunctionalInterface public interface OnVPortListener   { void onResult(int value);      default void onError(String e) { Log.w(TAG, "OnVPortListener: "   + e); } }
    @FunctionalInterface public interface OnSimpleListener  { void onDone();                 default void onError(String e) { Log.w(TAG, "OnSimpleListener: "  + e); } }

    public static class DimmerStatus {
        public boolean on;
        public int targetBrightness;
        public int actualBrightness;
        public boolean overheat;
        public boolean overcurrent;
        public boolean undervoltage;
        public boolean notCalibrated;
        public boolean calibrating;
        public boolean noSync;
        public boolean noLoad;
        public boolean notDimmable;
    }

    public static class DimmerPower {
        public float powerW;
        public int   voltageV;
        public float currentA;
    }

    public static class DimmerConfig {
        public boolean edgeButton;
        public boolean trailLead;
    }

    public static void init() {
        String path = UartHelper.findTtyPath();
        if (path == null) {
            Log.i(TAG, "No UART device found... dimmer not available");
            return;
        }
        sUart = new UartHelper();
        if (!sUart.open(path)) {
            sUart = null;
            return;
        }
        // wall display exposes uart node without stes backplate
        // probe mcu before declaring present
        if (!probeBackplate()) {
            Log.i(TAG, "STES backplate not responding on " + path + "... dimmer disabled");
            sUart.close();
            sUart = null;
            return;
        }
        sOperational = true;
        Log.i(TAG, "STES operational on " + path);
    }

    private static boolean probeBackplate() {
        final byte[][] result = {null};
        final Object lock = new Object();
        byte[] frame = buildFrame(StesCommand.GET_VERSION, new byte[]{0});
        final long probeTimeoutMs = 600;
        synchronized (lock) {
            sUart.sendData(frame, new UartHelper.OnDataTransferListener() {
                @Override public void dataReceived(byte[] data) {
                    synchronized (lock) { result[0] = data; lock.notifyAll(); }
                }
                @Override public void readTimeout() {
                    synchronized (lock) { result[0] = new byte[0]; lock.notifyAll(); }
                }
            }, probeTimeoutMs);
            try {
                lock.wait(probeTimeoutMs + 400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (result[0] == null || result[0].length < 5) return false;
        return parseResponse(result[0]) != null;
    }

    public static boolean isOperational() {
        return sOperational;
    }

    public static void close() {
        if (sUart != null) sUart.close();
        sOperational = false;
    }

    public static synchronized void setDimmer(int brightness0to1000, OnDimmerListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        int bri = Math.max(0, Math.min(1000, brightness0to1000));
        byte[] payload = {(byte)(bri >> 8), (byte)(bri & 0xFF), 0, 0, (byte) DEFAULT_GAMMA};
        send(StesCommand.SET_DIMMER, payload, data -> {
            DimmerStatus s = parseStatus(data);
            lastStatus = s;
            if (cb != null) cb.onResult(s);
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void clearDimmer(OnSimpleListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        send(StesCommand.SET_DIMMER_CLR, new byte[0], data -> {
            if (cb != null) cb.onDone();
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void getStatus(OnStatusListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        send(StesCommand.GET_STATUS, new byte[0], data -> {
            DimmerStatus s = parseStatus(data);
            lastStatus = s;
            if (cb != null) cb.onResult(s);
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void getPowerMeter(OnPowerListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        send(StesCommand.POWER_METER, new byte[]{0}, data -> {
            if (data.length < 9) { if (cb != null) cb.onError("Short response"); return; }
            DimmerPower p = parsePowerMeter(data);
            lastPower = p;
            if (cb != null) cb.onResult(p);
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void getConfig(OnConfigListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        send(StesCommand.GET_CONFIG, new byte[0], data -> {
            if (data.length < 3) { if (cb != null) cb.onError("Short response"); return; }
            DimmerConfig c = new DimmerConfig();
            c.edgeButton = (data[2] & 0x01) != 0;
            c.trailLead  = (data[2] & 0x02) != 0;
            lastConfig = c;
            if (cb != null) cb.onResult(c);
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void setConfig(boolean edgeButton, boolean trailLead, OnSimpleListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        byte cfg = (byte)((edgeButton ? 0x01 : 0) | (trailLead ? 0x02 : 0));
        send(StesCommand.SET_CONFIG, new byte[]{cfg}, data -> {
            if (cb != null) cb.onDone();
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void calibrate(byte mode, OnSimpleListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        send(StesCommand.CALIBRATE, new byte[]{mode}, data -> {
            if (cb != null) cb.onDone();
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void resetMcu(OnSimpleListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        send(StesCommand.RESET_MCU, new byte[0], data -> {
            if (cb != null) cb.onDone();
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void setLatchRelay(int channel, OnSimpleListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        send(StesCommand.SET_LRELAY, new byte[]{(byte) channel}, data -> {
            if (cb != null) cb.onDone();
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void resetLatchRelay(int channel, OnSimpleListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        send(StesCommand.RESET_LRELAY, new byte[]{(byte) channel}, data -> {
            if (cb != null) cb.onDone();
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void readLatchRelay(int channel, OnRelayListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        send(StesCommand.READ_LRELAY, new byte[]{(byte) channel}, data -> {
            boolean state = data.length > 2 && (data[2] & 0x01) != 0;
            if (cb != null) cb.onResult(state);
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void writeVPort(boolean on, OnSimpleListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        send(StesCommand.WRITE_VPORT, new byte[]{(byte)(on ? 1 : 0)}, data -> {
            if (cb != null) cb.onDone();
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void readVPort(OnVPortListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        send(StesCommand.READ_VPORT, new byte[0], data -> {
            int val = data.length > 2 ? (data[2] & 0xFF) : 0;
            if (cb != null) cb.onResult(val);
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void getVersion(OnVersionListener cb) {
        if (!sOperational) { if (cb != null) cb.onError("Not operational"); return; }
        send(StesCommand.GET_VERSION, new byte[]{0}, data -> {
            if (data.length < 4) { if (cb != null) cb.onError("Short response"); return; }
            String version = (data[2] & 0xFF) + "." + (data[3] & 0xFF);
            if (data.length > 8) {
                version += " fw=" + (data[6] & 0xFF) + "." + (data[7] & 0xFF) + "." + (data[8] & 0xFF);
            }
            if (data.length > 11) {
                version += " hw=" + (data[9] & 0xFF) + "." + (data[10] & 0xFF) + "." + (data[11] & 0xFF);
            }
            if (cb != null) cb.onResult(version);
        }, e -> { if (cb != null) cb.onError(e); });
    }

    // bootloader ota
    public interface BootloaderUpdateListener {
        void onConnected(int deviceId);
        void onProgress(int pagesWritten, int totalPages);
        void onComplete();
        void onError(String reason);
    }

    private static volatile boolean fwUpdateInProgress = false;

    public static void startFirmwareUpdate(File firmwareFile, BootloaderUpdateListener listener) {
        if (fwUpdateInProgress) { listener.onError("Update already in progress"); return; }
        if (!sOperational)      { listener.onError("STES not operational");       return; }
        fwUpdateInProgress = true;
        new Thread(() -> {
            try {
                Bootloader.run(firmwareFile, listener);
            } finally {
                fwUpdateInProgress = false;
            }
        }, "StesBootloader").start();
    }

    public static boolean isFirmwareUpdateInProgress() { return fwUpdateInProgress; }

    // internal stes frame helpers

    private interface DataCallback { void onData(byte[] resp); }

    private static void send(StesCommand cmd, byte[] payload, DataCallback onData, java.util.function.Consumer<String> onError) {
        send(cmd, payload, onData, onError, UartHelper.DEFAULT_TIMEOUT_MS);
    }

    static void send(StesCommand cmd, byte[] payload, DataCallback onData,
                     java.util.function.Consumer<String> onError, long timeoutMs) {
        byte[] frame = buildFrame(cmd, payload);
        sUart.sendData(frame, new UartHelper.OnDataTransferListener() {
            @Override public void dataReceived(byte[] data) {
                byte[] resp = parseResponse(data);
                if (resp == null) { onError.accept("Bad response or checksum"); return; }
                onData.onData(resp);
            }
            @Override public void readTimeout() { onError.accept("Timeout"); }
        }, timeoutMs);
    }

    static byte[] buildFrame(StesCommand cmd, byte[] payload) {
        byte len = (byte)(payload.length + 1);
        byte[] frame = new byte[payload.length + 5];
        frame[0] = HEADER_0;
        frame[1] = HEADER_1;
        frame[2] = len;
        frame[3] = cmd.value;
        System.arraycopy(payload, 0, frame, 4, payload.length);
        byte[] checksumInput = new byte[len];
        checksumInput[0] = cmd.value;
        System.arraycopy(payload, 0, checksumInput, 1, payload.length);
        frame[frame.length - 1] = checksum(checksumInput);
        return frame;
    }

    static byte[] parseResponse(byte[] raw) {
        if (raw.length < 5) return null;
        if (raw[0] != HEADER_0 || raw[1] != HEADER_1) return null;
        int payloadLen = raw.length - 4;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(raw, 3, payload, 0, payloadLen);
        if (checksum(payload) != raw[raw.length - 1]) {
            Log.w(TAG, "Checksum mismatch");
            return null;
        }
        return payload;
    }

    static byte checksum(byte[] data) {
        int sum = data.length;
        for (byte b : data) sum += (b & 0xFF);
        return (byte)(((sum - 1) ^ 0xFF) & 0xFF);
    }

    private static DimmerStatus parseStatus(byte[] resp) {
        DimmerStatus s = new DimmerStatus();
        if (resp.length < 8) return s;
        s.on             = (resp[2] & 0x01) != 0;
        byte warn        = resp[3];
        s.overheat       = (warn & 0x01) != 0;
        s.overcurrent    = (warn & 0x02) != 0;
        s.undervoltage   = (warn & 0x04) != 0;
        s.notCalibrated  = (warn & 0x08) != 0;
        s.calibrating    = (warn & 0x10) != 0;
        s.noSync         = (warn & 0x20) != 0;
        s.noLoad         = (warn & 0x40) != 0;
        s.notDimmable    = (warn & 0x80) != 0;
        s.targetBrightness = toShort(resp[4], resp[5]);
        s.actualBrightness = toShort(resp[6], resp[7]);
        return s;
    }

    private static DimmerPower parsePowerMeter(byte[] resp) {
        DimmerPower p = new DimmerPower();
        p.powerW   = toShort(resp[3], resp[4]) / 10.0f;
        p.voltageV = toShort(resp[5], resp[6]);
        p.currentA = toShort(resp[7], resp[8]) / 1000.0f;
        return p;
    }

    static int toShort(byte hi, byte lo) {
        return ((hi & 0xFF) << 8) | (lo & 0xFF);
    }

    // STM2 bootloader inner implementation

    private static final class Bootloader {
        // STM32 UART bootloader protocol (AN3155)
        private static final byte BL_SYNCHRO          = 0x7F;
        private static final byte BL_ACK              = 0x79;
        private static final byte BL_NAK              = 0x1F;
        private static final byte BL_CMD_GET_ID       = 0x02;
        private static final byte BL_CMD_WRITE_UNPROTECT = 0x73;
        private static final byte BL_CMD_EXTEND_ERASE = 0x44;
        private static final byte BL_CMD_WRITE_MEMORY = 0x31;

        private static final int FLASH_APP_START    = 0x08010000;
        private static final int BL_PAGE_SIZE       = 2048;
        private static final int BL_CHUNK_SIZE      = 256;
        private static final int BL_FIRST_APP_PAGE  = 12;
        private static final int BL_MAX_PAGES       = 40;
        private static final int BL_MAX_RETRIES     = 3;

        private static final long TIMEOUT_NORMAL    = 1000;
        private static final long TIMEOUT_LONG      = 3000;
        private static final long TIMEOUT_VERY_LONG = 30000;

        static void run(File firmwareFile, BootloaderUpdateListener listener) {
            try {
                byte[] firmware = readFile(firmwareFile);
                if (firmware == null || firmware.length == 0) {
                    listener.onError("Firmware file empty or unreadable");
                    return;
                }

                // 1. reset mcu into bootloader mode via stes
                Log.i(TAG, "BL: resetting MCU into bootloader");
                resetMcu(null);
                Thread.sleep(500);

                // 2. sync
                if (!syncBootloader()) { listener.onError("BL sync failed"); return; }

                // 3. GET_ID
                int deviceId = getDeviceId();
                if (deviceId < 0) { listener.onError("BL GET_ID failed"); return; }
                listener.onConnected(deviceId);
                Log.i(TAG, "BL: device ID = 0x" + Integer.toHexString(deviceId));

                // 4. Write unprotect
                if (!writeUnprotect()) { listener.onError("BL write-unprotect failed"); return; }
                Thread.sleep(200);
                if (!syncBootloader()) { listener.onError("BL re-sync failed"); return; }

                // 5. Erase application pages
                int totalPages = (int)Math.ceil((double)firmware.length / BL_PAGE_SIZE);
                totalPages = Math.min(totalPages, BL_MAX_PAGES);
                if (!extendedErase(BL_FIRST_APP_PAGE, totalPages)) { listener.onError("BL erase failed"); return; }

                // 6. Write firmware in 256b chunks
                int pagesWritten = 0;
                int offset = 0;
                while (offset < firmware.length) {
                    int chunkLen = Math.min(BL_CHUNK_SIZE, firmware.length - offset);
                    byte[] chunk = Arrays.copyOfRange(firmware, offset, offset + chunkLen);
                    int address = FLASH_APP_START + offset;
                    if (!writeMemory(address, chunk)) {
                        listener.onError("BL write failed at offset " + offset);
                        return;
                    }
                    offset += chunkLen;
                    pagesWritten = offset / BL_PAGE_SIZE;
                    listener.onProgress(pagesWritten, totalPages);
                }

                listener.onComplete();
                Log.i(TAG, "BL: firmware update complete");

            } catch (Exception e) {
                listener.onError(e.getMessage());
            }
        }

        private static boolean syncBootloader() throws InterruptedException {
            for (int i = 0; i < BL_MAX_RETRIES; i++) {
                byte[] resp = sendBlocking(new byte[]{BL_SYNCHRO}, TIMEOUT_NORMAL);
                if (resp != null && resp.length > 0 && resp[0] == BL_ACK) return true;
                Thread.sleep(200);
            }
            return false;
        }

        private static int getDeviceId() throws InterruptedException {
            if (!sendCommand(BL_CMD_GET_ID)) return -1;
            byte[] resp = sendBlocking(new byte[0], TIMEOUT_NORMAL);
            if (resp == null || resp.length < 3) return -1;
            return ((resp[1] & 0xFF) << 8) | (resp[2] & 0xFF);
        }

        private static boolean writeUnprotect() throws InterruptedException {
            if (!sendCommand(BL_CMD_WRITE_UNPROTECT)) return false;
            byte[] resp = sendBlocking(new byte[0], TIMEOUT_LONG);
            return resp != null && resp.length > 0 && resp[0] == BL_ACK;
        }

        private static boolean extendedErase(int firstPage, int numPages) throws InterruptedException {
            if (!sendCommand(BL_CMD_EXTEND_ERASE)) return false;
            byte[] data = new byte[2 + numPages * 2 + 1];
            int n = numPages - 1;
            data[0] = (byte)(n >> 8);
            data[1] = (byte)(n & 0xFF);
            for (int i = 0; i < numPages; i++) {
                int page = firstPage + i;
                data[2 + i * 2]     = (byte)(page >> 8);
                data[2 + i * 2 + 1] = (byte)(page & 0xFF);
            }
            data[data.length - 1] = blXor(data, 0, data.length - 1);
            byte[] resp = sendBlocking(data, TIMEOUT_VERY_LONG);
            return resp != null && resp.length > 0 && resp[0] == BL_ACK;
        }

        private static boolean writeMemory(int address, byte[] chunk) throws InterruptedException {
            if (!sendCommand(BL_CMD_WRITE_MEMORY)) return false;
            // send address + xor checksum
            byte[] addrBytes = {
                (byte)(address >> 24), (byte)(address >> 16),
                (byte)(address >> 8),  (byte)(address)
            };
            byte[] addrFrame = new byte[5];
            System.arraycopy(addrBytes, 0, addrFrame, 0, 4);
            addrFrame[4] = blXor(addrBytes, 0, 4);
            byte[] ackAddr = sendBlocking(addrFrame, TIMEOUT_NORMAL);
            if (ackAddr == null || ackAddr.length == 0 || ackAddr[0] != BL_ACK) return false;
            // send data: [n-1, data..., xor of all]
            byte[] dataFrame = new byte[1 + chunk.length + 1];
            dataFrame[0] = (byte)(chunk.length - 1);
            System.arraycopy(chunk, 0, dataFrame, 1, chunk.length);
            dataFrame[dataFrame.length - 1] = blXor(dataFrame, 0, dataFrame.length - 1);
            byte[] ackData = sendBlocking(dataFrame, TIMEOUT_LONG);
            return ackData != null && ackData.length > 0 && ackData[0] == BL_ACK;
        }

        // send [cmd, ~cmd], expect ACK
        private static boolean sendCommand(byte cmd) throws InterruptedException {
            byte[] frame = {cmd, (byte)(~cmd & 0xFF)};
            byte[] resp = sendBlocking(frame, TIMEOUT_NORMAL);
            return resp != null && resp.length > 0 && resp[0] == BL_ACK;
        }

        // synchronous send+receive via UartHelper with locking
        private static byte[] sendBlocking(byte[] data, long timeoutMs) throws InterruptedException {
            final byte[][] result = {null};
            final Object lock = new Object();
            synchronized (lock) {
                sUart.sendData(data, new UartHelper.OnDataTransferListener() {
                    @Override public void dataReceived(byte[] r) {
                        synchronized (lock) { result[0] = r; lock.notifyAll(); }
                    }
                    @Override public void readTimeout() {
                        synchronized (lock) { result[0] = new byte[0]; lock.notifyAll(); }
                    }
                }, timeoutMs);
                lock.wait(timeoutMs + 500);
            }
            return result[0];
        }

        private static byte blXor(byte[] data, int from, int len) {
            byte x = 0;
            for (int i = from; i < from + len; i++) x ^= data[i];
            return x;
        }

        private static byte[] readFile(File f) {
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int read = 0, n;
                while (read < buf.length && (n = in.read(buf, read, buf.length - read)) != -1) read += n;
                return buf;
            } catch (IOException e) {
                Log.e(TAG, "readFile: " + e.getMessage());
                return null;
            }
        }
    }
}
