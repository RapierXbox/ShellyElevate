package me.rapierxbox.shellyelevatev2.helper;

import android.os.Handler;
import android.os.HandlerThread;
import android_serialport_api.SerialPort;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class UartHelper {

    private static final String TAG = "UartHelper";
    public static final long DEFAULT_TIMEOUT_MS = 1995;
    private static final int BAUD_RATE = 57600;

    private static final String[] TTY_CANDIDATES = {
        "/dev/ttyS5",
        "/dev/ttyMT1",
        "/dev/ttyS1",
    };

    public interface OnDataTransferListener {
        default void dataReceived(byte[] data) {}
        default void readTimeout() {}
    }

    private static final HandlerThread sHandlerThread;
    private static final Handler sHandler;

    static {
        sHandlerThread = new HandlerThread("ShellyUartHandler");
        sHandlerThread.start();
        sHandler = new Handler(sHandlerThread.getLooper());
    }

    private SerialPort mSerialPort;
    private InputStream mInputStream;
    private OutputStream mOutputStream;
    private ReaderThread mReaderThread;
    private WriterThread mWriterThread;

    private volatile boolean transferActive = false;
    private OnDataTransferListener mListener;
    private byte[] mQueuedCommand;
    private OnDataTransferListener mQueuedListener;

    private final Runnable mTimeoutRunnable = () -> {
        Log.w(TAG, "Read timeout");
        OnDataTransferListener l = mListener;
        transferActive = false;
        if (l != null) l.readTimeout();
        drainQueue();
    };

    private class ReaderThread extends Thread {
        volatile boolean cancelled = false;

        ReaderThread() { super("ShellyStesReader"); }

        @Override
        public void run() {
            byte[] buf = new byte[512];
            while (!isInterrupted() && !cancelled) {
                try {
                    int n = mInputStream.read(buf);
                    if (n <= 0) continue;
                    byte[] data = new byte[n];
                    System.arraycopy(buf, 0, data, 0, n);
                    sHandler.removeCallbacks(mTimeoutRunnable);
                    OnDataTransferListener l = mListener;
                    transferActive = false;
                    if (l != null) l.dataReceived(data);
                    drainQueue();
                } catch (IOException e) {
                    if (!cancelled) Log.e(TAG, "Read error: " + e.getMessage());
                    transferActive = false;
                }
            }
        }
    }

    private class WriterThread extends Thread {
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

        WriterThread() { super("ShellyStesWriter"); }

        void write(byte[] data) { queue.offer(data); }

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    byte[] d = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (d == null) continue;
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

    public boolean open(String path) {
        try {
            mSerialPort = new SerialPort(new File(path), BAUD_RATE, 8, 1, 0, 0, 0);
            mInputStream = mSerialPort.getInputStream();
            mOutputStream = mSerialPort.getOutputStream();
            mReaderThread = new ReaderThread();
            mWriterThread = new WriterThread();
            mReaderThread.start();
            mWriterThread.start();
            Log.i(TAG, "UART opened at " + path + ", " + BAUD_RATE + " baud");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to open " + path + ": " + e.getMessage());
            return false;
        }
    }

    public boolean isReady() {
        return mSerialPort != null;
    }

    public synchronized boolean sendData(byte[] cmd, OnDataTransferListener listener) {
        return sendData(cmd, listener, DEFAULT_TIMEOUT_MS);
    }

    public synchronized boolean sendData(byte[] cmd, OnDataTransferListener listener, long timeoutMs) {
        if (!isReady()) return false;
        if (transferActive) {
            mQueuedCommand = cmd;
            mQueuedListener = listener;
            return false;
        }
        transferActive = true;
        mListener = listener;
        mQueuedCommand = null;
        mQueuedListener = null;
        mWriterThread.write(cmd);
        sHandler.postDelayed(mTimeoutRunnable, timeoutMs);
        return true;
    }

    private synchronized void drainQueue() {
        if (mQueuedCommand != null) {
            byte[] cmd = mQueuedCommand;
            OnDataTransferListener l = mQueuedListener;
            mQueuedCommand = null;
            mQueuedListener = null;
            sendData(cmd, l);
        }
    }

    public void close() {
        sHandler.removeCallbacks(mTimeoutRunnable);
        if (mReaderThread != null) { mReaderThread.cancelled = true; mReaderThread.interrupt(); }
        if (mWriterThread != null) { mWriterThread.interrupt(); }
        try { if (mInputStream != null) mInputStream.close(); } catch (IOException ignored) {}
        try { if (mOutputStream != null) mOutputStream.close(); } catch (IOException ignored) {}
        try { if (mSerialPort != null) mSerialPort.close(); } catch (Exception ignored) {}
        mSerialPort = null;
        Log.i(TAG, "UART closed");
    }
}
