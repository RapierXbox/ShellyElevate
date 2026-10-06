package me.rapierxbox.shellyelevatev2.api;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import me.rapierxbox.shellyelevatev2.helper.ForegroundActivities;

// png of the screen for GET /api/v1/screenshot
// screencap sees everything but needs the frame buffer grant of the privileged install
// otherwise our own window is copied which only works while one of our activities is in front
final class ScreenCapture {
    private static final String TAG = "ScreenCapture";
    private static final long TIMEOUT_MS = 5_000;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    private ScreenCapture() {}

    // null when nothing could be captured
    static byte[] capturePng(Context context) {
        byte[] png = screencap();
        if (png != null) return png;
        Activity activity = ForegroundActivities.resumedActivity();
        if (activity == null) return null;
        Bitmap bitmap = copyWindow(activity);
        if (bitmap == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] screencap() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"screencap", "-p"});
            // a hung screencap is killed so it cannot pin a server thread
            Process running = process;
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(TIMEOUT_MS);
                    running.destroy();
                } catch (InterruptedException ignored) {
                    // finished in time
                }
            }, "ScreencapTimeout");
            watchdog.setDaemon(true);
            watchdog.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            byte[] png = out.toByteArray();
            // without the grant screencap prints an error or an all black frame header only
            if (png.length < 1024 || !startsWith(png, PNG_MAGIC)) return null;
            return png;
        } catch (IOException e) {
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static Bitmap copyWindow(Activity activity) {
        Window window = activity.getWindow();
        View decor = window != null ? window.getDecorView() : null;
        if (decor == null || decor.getWidth() == 0 || decor.getHeight() == 0) return null;
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            HandlerThread thread = new HandlerThread("ScreenCapture");
            thread.start();
            try {
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        PixelCopy.request(window, bitmap, result -> {
                            ok.set(result == PixelCopy.SUCCESS);
                            done.countDown();
                        }, new Handler(thread.getLooper()));
                    } catch (IllegalArgumentException e) {
                        done.countDown();
                    }
                });
                done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                thread.quitSafely();
            }
        } else {
            // api 24 and 25 have no pixel copy so the view hierarchy draws itself
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    decor.draw(new Canvas(bitmap));
                    ok.set(true);
                } catch (RuntimeException e) {
                    Log.w(TAG, "View draw failed", e);
                } finally {
                    done.countDown();
                }
            });
            try {
                done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!ok.get()) {
            bitmap.recycle();
            return null;
        }
        return bitmap;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}
