package me.rapierxbox.shellyelevatev2.helper;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;

import android_serialport_api.SerialPort;

// request and response transport to the stes backplate mcu
// one transfer is in flight at a time and a single slot holds the newest command that arrives meanwhile
public class UartHelper {

    private static final String TAG = "UartHelper";
    public static final long DEFAULT_TIMEOUT_MS = 1995;
    private static final int BAUD_RATE = 57600;
    private static final int READ_BUFFER_SIZE = 512;
    // pause after a read error so a persistent failure does not spin the cpu
    private static final long READ_ERROR_BACKOFF_MS = 100;

    private static final String[] TTY_CANDIDATES = {
            "/dev/ttyS5",
            "/dev/ttyMT1",
            "/dev/ttyS1",
    };

    public interface OnDataTransferListener {
        default void dataReceived(byte[] data) {}

        default void readTimeout() {}
    }

    // shared by every instance and only used for transfer timeouts
    private static final Handler sHandler;

    static {
        HandlerThread handlerThread = new HandlerThread("ShellyUartHandler");
        handlerThread.start();
        sHandler = new Handler(handlerThread.getLooper());
    }

    private volatile SerialPort mSerialPort;
    private InputStream mInputStream;
    private OutputStream mOutputStream;
    private ReaderThread mReaderThread;
    private WriterThread mWriterThread;

    // transfer state is guarded by this
    private boolean transferActive = false;
    private OnDataTransferListener mListener;
    private byte[] mQueuedCommand;
    private OnDataTransferListener mQueuedListener;
    private long mQueuedTimeoutMs = DEFAULT_TIMEOUT_MS;
    // bumped per transfer so a timeout that already left the handler queue cant end a newer transfer
    private long mTransferId = 0;
    private Runnable mPendingTimeout;

    private class ReaderThread extends Thread {
        volatile boolean cancelled = false;

        ReaderThread() {
            super("ShellyStesReader");
        }

        @Override
        public void run() {
            byte[] buf = new byte[READ_BUFFER_SIZE];
            while (!isInterrupted() && !cancelled) {
                try {
                    int n = mInputStream.read(buf);
                    if (n < 0) {
                        if (!cancelled) Log.w(TAG, "EOF on serial input, stopping reader");
                        break;
                    }
                    if (n == 0) continue;
                    byte[] data = new byte[n];
                    System.arraycopy(buf, 0, data, 0, n);
                    OnDataTransferListener l = finishTransfer();
                    if (l != null) l.dataReceived(data);
                    drainQueue();
                } catch (IOException e) {
                    if (!cancelled) Log.e(TAG, "Read error: " + e.getMessage());
                    if (cancelled) break;
                    // end the transfer like a timeout so its caller is always notified
                    OnDataTransferListener l = finishTransfer();
                    if (l != null) l.readTimeout();
                    drainQueue();
                    try {
                        Thread.sleep(READ_ERROR_BACKOFF_MS);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }
    }

    private class WriterThread extends Thread {
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

        WriterThread() {
            super("ShellyStesWriter");
        }

        void write(byte[] data) {
            queue.offer(data);
        }

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    byte[] d = queue.take();
                    mOutputStream.write(d);
                    mOutputStream.flush();
                } catch (InterruptedException e) {
                    interrupt();
                } catch (IOException e) {
                    Log.e(TAG, "Write error: " + e.getMessage());
                }
            }
        }
    }

    public static String findTtyPath() {
        for (String path : TTY_CANDIDATES) {
            if (new File(path).exists()) return path;
        }
        return null;
    }

    public synchronized boolean open(String path) {
        SerialPort port = null;
        try {
            port = new SerialPort(new File(path), BAUD_RATE, 8, 1, 0, 0, 0);
            mInputStream = port.getInputStream();
            mOutputStream = port.getOutputStream();
            mReaderThread = new ReaderThread();
            mWriterThread = new WriterThread();
            mReaderThread.start();
            mWriterThread.start();
            // published last so isReady() never sees a half opened port
            mSerialPort = port;
            Log.i(TAG, "UART opened at " + path + ", " + BAUD_RATE + " baud");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to open " + path + ": " + e.getMessage());
            if (port != null) {
                try {
                    port.close();
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }

    public boolean isReady() {
        return mSerialPort != null;
    }

    public synchronized boolean sendData(byte[] cmd, OnDataTransferListener listener) {
        return sendData(cmd, listener, DEFAULT_TIMEOUT_MS);
    }

    // false when nothing was sent. a busy port keeps the command in the slot and sends it once the current transfer ends
    public synchronized boolean sendData(byte[] cmd, OnDataTransferListener listener, long timeoutMs) {
        if (!isReady()) return false;
        if (transferActive) {
            // the slot holds one command so fail the one being replaced instead of leaving its caller hanging
            OnDataTransferListener replaced = mQueuedListener;
            if (replaced != null && replaced != listener) sHandler.post(replaced::readTimeout);
            mQueuedCommand = cmd;
            mQueuedListener = listener;
            mQueuedTimeoutMs = timeoutMs;
            return false;
        }
        transferActive = true;
        mListener = listener;
        mQueuedCommand = null;
        mQueuedListener = null;
        mWriterThread.write(cmd);
        final long transferId = ++mTransferId;
        mPendingTimeout = () -> onTransferTimeout(transferId);
        sHandler.postDelayed(mPendingTimeout, timeoutMs);
        return true;
    }

    private void onTransferTimeout(long transferId) {
        OnDataTransferListener l;
        synchronized (this) {
            // a null pending timeout means data or close already ended this transfer
            if (transferId != mTransferId || mPendingTimeout == null) return;
            mPendingTimeout = null;
            transferActive = false;
            l = mListener;
            mListener = null;
        }
        Log.w(TAG, "Read timeout");
        if (l != null) l.readTimeout();
        drainQueue();
    }

    // ends the current transfer and hands back the listener to notify outside the lock
    // callers wait on their own lock inside the listener so calling it while holding this could deadlock
    // the listener is cleared so a late or unrequested read never reaches a finished caller
    private synchronized OnDataTransferListener finishTransfer() {
        cancelPendingTimeout();
        transferActive = false;
        OnDataTransferListener l = mListener;
        mListener = null;
        return l;
    }

    private synchronized void cancelPendingTimeout() {
        if (mPendingTimeout != null) {
            sHandler.removeCallbacks(mPendingTimeout);
            mPendingTimeout = null;
        }
    }

    private synchronized void drainQueue() {
        if (mQueuedCommand == null) return;
        byte[] cmd = mQueuedCommand;
        OnDataTransferListener l = mQueuedListener;
        long timeoutMs = mQueuedTimeoutMs;
        mQueuedCommand = null;
        mQueuedListener = null;
        sendData(cmd, l, timeoutMs);
    }

    public synchronized void close() {
        cancelPendingTimeout();
        transferActive = false;
        mListener = null;
        mQueuedCommand = null;
        mQueuedListener = null;
        if (mReaderThread != null) {
            mReaderThread.cancelled = true;
            mReaderThread.interrupt();
        }
        if (mWriterThread != null) mWriterThread.interrupt();
        try {
            if (mInputStream != null) mInputStream.close();
        } catch (IOException ignored) {
        }
        try {
            if (mOutputStream != null) mOutputStream.close();
        } catch (IOException ignored) {
        }
        try {
            if (mSerialPort != null) mSerialPort.close();
        } catch (Exception ignored) {
        }
        mSerialPort = null;
        Log.i(TAG, "UART closed");
    }
}
