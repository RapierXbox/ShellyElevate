package me.rapierxbox.shellyelevatev2.helper;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

// runs privileged shell commands through the apps already elevated exec context
public final class PrivilegedShell {
    private static final String TAG = "PrivilegedShell";
    private static final long TIMEOUT_MS = 60_000L;

    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    private PrivilegedShell() {}

    public static Result run(String... argv) {
        try {
            return drainAndWait(Runtime.getRuntime().exec(argv));
        } catch (IOException e) {
            Log.e(TAG, "exec failed: " + e.getMessage());
            return new Result(-1, "", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    // wraps the command in sh so redirects and chains work
    public static Result runShell(String script) {
        return run("sh", "-c", script);
    }

    private static Result drainAndWait(Process p) {
        // close stdin so commands that read it cannot hang
        try { p.getOutputStream().close(); } catch (IOException ignored) {}
        // drain both streams on separate threads to avoid a full pipe buffer deadlock
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread to = drain(p.getInputStream(), out);
        Thread te = drain(p.getErrorStream(), err);
        int code = -1;
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        try {
            // poll exitValue since waitFor with timeout needs api 26
            while (true) {
                try {
                    code = p.exitValue();
                    break;
                } catch (IllegalThreadStateException notDone) {
                    if (System.currentTimeMillis() >= deadline) {
                        Log.w(TAG, "command timed out after " + TIMEOUT_MS + "ms");
                        p.destroy();
                        break;
                    }
                    Thread.sleep(50);
                }
            }
            to.join(1000);
            te.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroy();
            code = -1;
        }
        return new Result(code, out.toString(), err.toString());
    }

    private static Thread drain(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (IOException ignored) {}
        });
        t.start();
        return t;
    }
}
