package me.rapierxbox.shellyelevatev2.api;

import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

// recent log lines of this process for GET /api/v1/logs. apps may always read their own logcat
final class AppLog {
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private AppLog() {}

    static String read(int lines) {
        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-v", "threadtime", "-t", String.valueOf(lines),
                    "--pid=" + Process.myPid()});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < MAX_BYTES) out.write(buf, 0, n);
            }
            process.waitFor(5, TimeUnit.SECONDS);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException | InterruptedException e) {
            return "logcat unavailable: " + e.getMessage() + "\n";
        } finally {
            if (process != null) process.destroy();
        }
    }
}
