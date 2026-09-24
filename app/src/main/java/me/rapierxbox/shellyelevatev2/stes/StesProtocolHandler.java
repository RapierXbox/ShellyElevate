package me.rapierxbox.shellyelevatev2.stes;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import me.rapierxbox.shellyelevatev2.helper.UartHelper;

public class StesProtocolHandler {

    private static final String TAG = "STES";

    // frame layout is aa 55 [len] [cmd] [payload...] [checksum]
    private static final byte HEADER_0 = (byte) 0xAA;
    private static final byte HEADER_1 = (byte) 0x55;
    private static final int FRAME_OVERHEAD = 4;
    private static final int MIN_FRAME_LENGTH = 5;
    private static final int DEFAULT_GAMMA = 50;
    private static final byte[] NO_PAYLOAD = new byte[0];

    private static final long PROBE_TIMEOUT_MS = 600;
    // extra wait on top of the uart timeout so its own timeout callback wins
    private static final long BLOCKING_GRACE_MS = 500;

    // calibrate payload values
    public static final byte CALIB_CLEAR = 0;
    public static final byte CALIB_FULL  = 1;
    public static final byte CALIB_SHORT = 2;

    private static volatile UartHelper sUart;
    private static volatile boolean sOperational = false;
    private static volatile boolean sFwUpdateInProgress = false;

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

    // stes mcu firmware update via the stm32 uart bootloader (an3155)
    // callbacks run on the bootloader thread
    public interface BootloaderUpdateListener {
        void onConnected(int deviceId);
        void onProgress(int pagesWritten, int totalPages);
        void onComplete();
        void onError(String reason);
    }

    // turns a parsed response into a value or null when the response is too short
    private interface ResponseParser<T> { T parse(byte[] resp); }

    public static void init() {
        String path = UartHelper.findTtyPath();
        if (path == null) {
            Log.i(TAG, "No UART device found, dimmer not available");
            return;
        }
        UartHelper uart = new UartHelper();
        if (!uart.open(path)) return;
        sUart = uart;
        // the uart node exists on every wall display even without a backplate so probe the mcu first
        if (!probeBackplate()) {
            Log.i(TAG, "STES backplate not responding on " + path + ", dimmer disabled");
            uart.close();
            sUart = null;
            return;
        }
        sOperational = true;
        Log.i(TAG, "STES operational on " + path);
    }

    private static boolean probeBackplate() {
        byte[] frame = buildFrame(StesCommand.GET_VERSION, new byte[]{0});
        try {
            byte[] resp = transferBlocking(frame, PROBE_TIMEOUT_MS);
            return resp != null && parseResponse(resp) != null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static boolean isOperational() {
        return sOperational;
    }

    public static synchronized void close() {
        sOperational = false;
        UartHelper uart = sUart;
        sUart = null;
        if (uart != null) uart.close();
    }

    public static synchronized void setDimmer(int brightness0to1000, OnDimmerListener cb) {
        int bri = Math.max(0, Math.min(1000, brightness0to1000));
        byte[] payload = {(byte) (bri >> 8), (byte) (bri & 0xFF), 0, 0, (byte) DEFAULT_GAMMA};
        request(StesCommand.SET_DIMMER, payload, StesProtocolHandler::parseStatus, s -> {
            lastStatus = s;
            if (cb != null) cb.onResult(s);
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void clearDimmer(OnSimpleListener cb) {
        requestSimple(StesCommand.SET_DIMMER_CLR, NO_PAYLOAD, cb);
    }

    public static synchronized void getStatus(OnStatusListener cb) {
        request(StesCommand.GET_STATUS, NO_PAYLOAD, StesProtocolHandler::parseStatus, s -> {
            lastStatus = s;
            if (cb != null) cb.onResult(s);
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void getPowerMeter(OnPowerListener cb) {
        request(StesCommand.POWER_METER, new byte[]{0}, StesProtocolHandler::parsePowerMeter, p -> {
            lastPower = p;
            if (cb != null) cb.onResult(p);
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void getConfig(OnConfigListener cb) {
        request(StesCommand.GET_CONFIG, NO_PAYLOAD, StesProtocolHandler::parseConfig, c -> {
            lastConfig = c;
            if (cb != null) cb.onResult(c);
        }, e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void setConfig(boolean edgeButton, boolean trailLead, OnSimpleListener cb) {
        byte cfg = (byte) ((edgeButton ? 0x01 : 0) | (trailLead ? 0x02 : 0));
        requestSimple(StesCommand.SET_CONFIG, new byte[]{cfg}, cb);
    }

    public static synchronized void calibrate(byte mode, OnSimpleListener cb) {
        requestSimple(StesCommand.CALIBRATE, new byte[]{mode}, cb);
    }

    public static synchronized void resetMcu(OnSimpleListener cb) {
        requestSimple(StesCommand.RESET_MCU, NO_PAYLOAD, cb);
    }

    public static synchronized void setLatchRelay(int channel, OnSimpleListener cb) {
        requestSimple(StesCommand.SET_LRELAY, new byte[]{(byte) channel}, cb);
    }

    public static synchronized void resetLatchRelay(int channel, OnSimpleListener cb) {
        requestSimple(StesCommand.RESET_LRELAY, new byte[]{(byte) channel}, cb);
    }

    public static synchronized void readLatchRelay(int channel, OnRelayListener cb) {
        StesProtocolHandler.<Boolean>request(StesCommand.READ_LRELAY, new byte[]{(byte) channel},
                resp -> resp.length > 2 ? (resp[2] & 0x01) != 0 : null,
                state -> { if (cb != null) cb.onResult(state); },
                e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void writeVPort(boolean on, OnSimpleListener cb) {
        requestSimple(StesCommand.WRITE_VPORT, new byte[]{(byte) (on ? 1 : 0)}, cb);
    }

    public static synchronized void readVPort(OnVPortListener cb) {
        StesProtocolHandler.<Integer>request(StesCommand.READ_VPORT, NO_PAYLOAD,
                resp -> resp.length > 2 ? (resp[2] & 0xFF) : null,
                value -> { if (cb != null) cb.onResult(value); },
                e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void getVersion(OnVersionListener cb) {
        request(StesCommand.GET_VERSION, new byte[]{0}, StesProtocolHandler::parseVersion,
                version -> { if (cb != null) cb.onResult(version); },
                e -> { if (cb != null) cb.onError(e); });
    }

    public static synchronized void startFirmwareUpdate(File firmwareFile, BootloaderUpdateListener listener) {
        if (sFwUpdateInProgress) { listener.onError("Update already in progress"); return; }
        if (!sOperational)       { listener.onError("STES not operational");       return; }
        sFwUpdateInProgress = true;
        new Thread(() -> {
            try {
                Bootloader.run(firmwareFile, listener);
            } finally {
                sFwUpdateInProgress = false;
            }
        }, "StesBootloader").start();
    }

    public static boolean isFirmwareUpdateInProgress() { return sFwUpdateInProgress; }

    // request plumbing

    private static void requestSimple(StesCommand cmd, byte[] payload, OnSimpleListener cb) {
        request(cmd, payload, resp -> resp,
                resp -> { if (cb != null) cb.onDone(); },
                e -> { if (cb != null) cb.onError(e); });
    }

    private static <T> void request(StesCommand cmd, byte[] payload, ResponseParser<T> parser,
                                    Consumer<T> onResult, Consumer<String> onError) {
        if (!sOperational) { onError.accept("Not operational"); return; }
        // regular traffic would collide with the bootloader on the shared uart
        if (sFwUpdateInProgress) { onError.accept("Firmware update in progress"); return; }
        transmit(cmd, payload, resp -> {
            T value = parser.parse(resp);
            if (value == null) { onError.accept("Short response"); return; }
            onResult.accept(value);
        }, onError, UartHelper.DEFAULT_TIMEOUT_MS);
    }

    private static void transmit(StesCommand cmd, byte[] payload, Consumer<byte[]> onResponse,
                                 Consumer<String> onError, long timeoutMs) {
        UartHelper uart = sUart;
        if (uart == null) { onError.accept("Not operational"); return; }
        // uart helper keeps its listener after a transfer so late chunks would fire the callback twice
        AtomicBoolean done = new AtomicBoolean(false);
        boolean sent = uart.sendData(buildFrame(cmd, payload), new UartHelper.OnDataTransferListener() {
            @Override public void dataReceived(byte[] data) {
                if (!done.compareAndSet(false, true)) return;
                byte[] resp = parseResponse(data);
                if (resp == null) { onError.accept("Bad response or checksum"); return; }
                onResponse.accept(resp);
            }
            @Override public void readTimeout() {
                if (done.compareAndSet(false, true)) onError.accept("Timeout");
            }
        }, timeoutMs);
        // false also means queued so only a closed port is a failure here
        if (!sent && !uart.isReady() && done.compareAndSet(false, true)) onError.accept("Not operational");
    }

    // returns the raw reply or an empty array on timeout or null if nothing came back in time
    private static byte[] transferBlocking(byte[] data, long timeoutMs) throws InterruptedException {
        UartHelper uart = sUart;
        if (uart == null || !uart.isReady()) return null;
        final Object lock = new Object();
        final byte[][] result = {null};
        uart.sendData(data, new UartHelper.OnDataTransferListener() {
            @Override public void dataReceived(byte[] r) { deliver(r); }
            @Override public void readTimeout() { deliver(new byte[0]); }
            private void deliver(byte[] r) {
                synchronized (lock) {
                    if (result[0] != null) return;
                    result[0] = r;
                    lock.notifyAll();
                }
            }
        }, timeoutMs);
        long deadline = SystemClock.uptimeMillis() + timeoutMs + BLOCKING_GRACE_MS;
        synchronized (lock) {
            // loop guards against spurious wakeups
            while (result[0] == null) {
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) break;
                lock.wait(remaining);
            }
            return result[0];
        }
    }

    // frame encode and decode

    static byte[] buildFrame(StesCommand cmd, byte[] payload) {
        // len counts cmd and payload but not the header or checksum
        int len = payload.length + 1;
        byte[] frame = new byte[payload.length + MIN_FRAME_LENGTH];
        frame[0] = HEADER_0;
        frame[1] = HEADER_1;
        frame[2] = (byte) len;
        frame[3] = cmd.value;
        System.arraycopy(payload, 0, frame, 4, payload.length);
        frame[frame.length - 1] = checksum(frame, 3, len);
        return frame;
    }

    // strips header and len and checksum and returns cmd plus payload
    static byte[] parseResponse(byte[] raw) {
        if (raw.length < MIN_FRAME_LENGTH) return null;
        if (raw[0] != HEADER_0 || raw[1] != HEADER_1) return null;
        int bodyLen = raw.length - FRAME_OVERHEAD;
        if (checksum(raw, 3, bodyLen) != raw[raw.length - 1]) {
            Log.w(TAG, "Checksum mismatch");
            return null;
        }
        return Arrays.copyOfRange(raw, 3, 3 + bodyLen);
    }

    // not(sum of bytes plus length minus 1) mod 256 which matches the firmware verifier
    private static byte checksum(byte[] data, int from, int len) {
        int sum = len;
        for (int i = from; i < from + len; i++) sum += (data[i] & 0xFF);
        return (byte) (((sum - 1) ^ 0xFF) & 0xFF);
    }

    // response layout after stripping the frame
    //   [0..1] reserved | [2] on flag | [3] warning bitmap
    //   [4..5] target brightness be | [6..7] actual brightness be
    private static DimmerStatus parseStatus(byte[] resp) {
        // null instead of a zeroed status so callers dont publish a bogus off state
        if (resp.length < 8) return null;
        DimmerStatus s = new DimmerStatus();
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
        s.targetBrightness = u16(resp[4], resp[5]);
        s.actualBrightness = u16(resp[6], resp[7]);
        return s;
    }

    // power in 0.1 w and voltage in v and current in ma
    private static DimmerPower parsePowerMeter(byte[] resp) {
        if (resp.length < 9) return null;
        DimmerPower p = new DimmerPower();
        p.powerW   = u16(resp[3], resp[4]) / 10.0f;
        p.voltageV = u16(resp[5], resp[6]);
        p.currentA = u16(resp[7], resp[8]) / 1000.0f;
        return p;
    }

    private static DimmerConfig parseConfig(byte[] resp) {
        if (resp.length < 3) return null;
        DimmerConfig c = new DimmerConfig();
        c.edgeButton = (resp[2] & 0x01) != 0;
        c.trailLead  = (resp[2] & 0x02) != 0;
        return c;
    }

    private static String parseVersion(byte[] resp) {
        if (resp.length < 4) return null;
        StringBuilder version = new StringBuilder()
                .append(resp[2] & 0xFF).append('.').append(resp[3] & 0xFF);
        if (resp.length > 8) {
            version.append(" fw=").append(resp[6] & 0xFF).append('.')
                    .append(resp[7] & 0xFF).append('.').append(resp[8] & 0xFF);
        }
        if (resp.length > 11) {
            version.append(" hw=").append(resp[9] & 0xFF).append('.')
                    .append(resp[10] & 0xFF).append('.').append(resp[11] & 0xFF);
        }
        return version.toString();
    }

    private static int u16(byte hi, byte lo) {
        return ((hi & 0xFF) << 8) | (lo & 0xFF);
    }

    // stm32 uart bootloader sequence used to flash the stes mcu
    // synchro then get id then write unprotect then extended erase then chunked write memory
    private static final class Bootloader {
        private static final byte BL_SYNCHRO             = 0x7F;
        private static final byte BL_ACK                 = 0x79;
        private static final byte BL_CMD_GET_ID          = 0x02;
        private static final byte BL_CMD_WRITE_UNPROTECT = 0x73;
        private static final byte BL_CMD_EXTEND_ERASE    = 0x44;
        private static final byte BL_CMD_WRITE_MEMORY    = 0x31;

        private static final int FLASH_BASE        = 0x08000000;
        private static final int FLASH_APP_START   = 0x08010000;
        private static final int BL_PAGE_SIZE      = 2048;
        private static final int BL_CHUNK_SIZE     = 256;
        // derived from the write start address so erase and write cover the same pages
        private static final int BL_FIRST_APP_PAGE = (FLASH_APP_START - FLASH_BASE) / BL_PAGE_SIZE;
        private static final int BL_MAX_PAGES      = 40;
        private static final int BL_MAX_BYTES      = BL_MAX_PAGES * BL_PAGE_SIZE;
        private static final int BL_MAX_RETRIES    = 3;

        private static final long TIMEOUT_NORMAL    = 1000;
        private static final long TIMEOUT_LONG      = 3000;
        private static final long TIMEOUT_VERY_LONG = 30000;

        static void run(File firmwareFile, BootloaderUpdateListener listener) {
            try {
                long fileSize = firmwareFile.length();
                if (fileSize > BL_MAX_BYTES) {
                    listener.onError(tooLargeMessage(fileSize));
                    return;
                }
                byte[] firmware = readFile(firmwareFile);
                if (firmware == null || firmware.length == 0) {
                    listener.onError("Firmware file empty or unreadable");
                    return;
                }
                if (firmware.length > BL_MAX_BYTES) {
                    listener.onError(tooLargeMessage(firmware.length));
                    return;
                }

                Log.i(TAG, "BL: resetting MCU into bootloader");
                // bypasses request() since that refuses traffic while an update runs
                transmit(StesCommand.RESET_MCU, NO_PAYLOAD, resp -> { },
                        e -> Log.w(TAG, "BL: reset MCU: " + e), UartHelper.DEFAULT_TIMEOUT_MS);
                Thread.sleep(500);

                if (!syncBootloader()) { listener.onError("BL sync failed"); return; }

                int deviceId = getDeviceId();
                if (deviceId < 0) { listener.onError("BL GET_ID failed"); return; }
                listener.onConnected(deviceId);
                Log.i(TAG, "BL: device ID = 0x" + Integer.toHexString(deviceId));

                if (!writeUnprotect()) { listener.onError("BL write-unprotect failed"); return; }
                // the stm32 reboots after write unprotect so sync again
                Thread.sleep(200);
                if (!syncBootloader()) { listener.onError("BL re-sync failed"); return; }

                int totalPages = (firmware.length + BL_PAGE_SIZE - 1) / BL_PAGE_SIZE;
                if (!extendedErase(BL_FIRST_APP_PAGE, totalPages)) { listener.onError("BL erase failed"); return; }

                int offset = 0;
                while (offset < firmware.length) {
                    int chunkLen = Math.min(BL_CHUNK_SIZE, firmware.length - offset);
                    byte[] chunk = Arrays.copyOfRange(firmware, offset, offset + chunkLen);
                    if (!writeMemory(FLASH_APP_START + offset, chunk)) {
                        listener.onError("BL write failed at offset " + offset);
                        return;
                    }
                    offset += chunkLen;
                    // a trailing partial page still counts so progress reaches the total
                    int pagesWritten = offset == firmware.length ? totalPages : offset / BL_PAGE_SIZE;
                    listener.onProgress(pagesWritten, totalPages);
                }

                listener.onComplete();
                Log.i(TAG, "BL: firmware update complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                listener.onError("Firmware update interrupted");
            } catch (Exception e) {
                Log.e(TAG, "BL: firmware update failed", e);
                listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }

        private static String tooLargeMessage(long size) {
            return "Firmware too large: " + size + " bytes, max " + BL_MAX_BYTES
                    + " bytes (" + BL_MAX_PAGES + " pages)";
        }

        private static boolean syncBootloader() throws InterruptedException {
            for (int i = 0; i < BL_MAX_RETRIES; i++) {
                if (isAck(transferBlocking(new byte[]{BL_SYNCHRO}, TIMEOUT_NORMAL))) return true;
                Thread.sleep(200);
            }
            return false;
        }

        private static int getDeviceId() throws InterruptedException {
            byte[] resp = transferBlocking(commandFrame(BL_CMD_GET_ID), TIMEOUT_NORMAL);
            if (!isAck(resp)) return -1;
            // reply is [n] [pid hi] [pid lo] [ack] and may share a read with the command ack
            byte[] idResp = Arrays.copyOfRange(resp, 1, resp.length);
            if (idResp.length < 3) {
                // keep any id bytes that came with the ack and append the rest
                byte[] more = transferBlocking(new byte[0], TIMEOUT_NORMAL);
                if (more == null) return -1;
                byte[] joined = Arrays.copyOf(idResp, idResp.length + more.length);
                System.arraycopy(more, 0, joined, idResp.length, more.length);
                idResp = joined;
            }
            if (idResp.length < 3) return -1;
            return u16(idResp[1], idResp[2]);
        }

        private static boolean writeUnprotect() throws InterruptedException {
            if (!sendCommand(BL_CMD_WRITE_UNPROTECT)) return false;
            return isAck(transferBlocking(new byte[0], TIMEOUT_LONG));
        }

        private static boolean extendedErase(int firstPage, int numPages) throws InterruptedException {
            if (!sendCommand(BL_CMD_EXTEND_ERASE)) return false;
            // [n-1 be] then each page number be then xor
            byte[] data = new byte[2 + numPages * 2 + 1];
            int n = numPages - 1;
            data[0] = (byte) (n >> 8);
            data[1] = (byte) (n & 0xFF);
            for (int i = 0; i < numPages; i++) {
                int page = firstPage + i;
                data[2 + i * 2]     = (byte) (page >> 8);
                data[2 + i * 2 + 1] = (byte) (page & 0xFF);
            }
            data[data.length - 1] = xor(data, 0, data.length - 1);
            return isAck(transferBlocking(data, TIMEOUT_VERY_LONG));
        }

        private static boolean writeMemory(int address, byte[] chunk) throws InterruptedException {
            if (!sendCommand(BL_CMD_WRITE_MEMORY)) return false;

            byte[] addrFrame = {
                (byte) (address >> 24), (byte) (address >> 16),
                (byte) (address >> 8),  (byte) address,
                0
            };
            addrFrame[4] = xor(addrFrame, 0, 4);
            if (!isAck(transferBlocking(addrFrame, TIMEOUT_NORMAL))) return false;

            // [n-1] then data then xor over everything before it
            byte[] dataFrame = new byte[chunk.length + 2];
            dataFrame[0] = (byte) (chunk.length - 1);
            System.arraycopy(chunk, 0, dataFrame, 1, chunk.length);
            dataFrame[dataFrame.length - 1] = xor(dataFrame, 0, dataFrame.length - 1);
            return isAck(transferBlocking(dataFrame, TIMEOUT_LONG));
        }

        // sends [cmd] [not cmd] and expects an ack
        private static boolean sendCommand(byte cmd) throws InterruptedException {
            return isAck(transferBlocking(commandFrame(cmd), TIMEOUT_NORMAL));
        }

        private static byte[] commandFrame(byte cmd) {
            return new byte[]{cmd, (byte) (~cmd & 0xFF)};
        }

        private static boolean isAck(byte[] resp) {
            return resp != null && resp.length > 0 && resp[0] == BL_ACK;
        }

        private static byte xor(byte[] data, int from, int len) {
            byte x = 0;
            for (int i = from; i < from + len; i++) x ^= data[i];
            return x;
        }

        private static byte[] readFile(File f) {
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int read = 0;
                int n;
                while (read < buf.length && (n = in.read(buf, read, buf.length - read)) != -1) read += n;
                // the file may shrink between length() and the read
                return read < buf.length ? Arrays.copyOf(buf, read) : buf;
            } catch (IOException e) {
                Log.e(TAG, "BL: readFile failed: " + e.getMessage());
                return null;
            }
        }
    }
}
