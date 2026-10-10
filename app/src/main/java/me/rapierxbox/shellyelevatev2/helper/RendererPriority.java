package me.rapierxbox.shellyelevatev2.helper;

import android.os.Process;
import android.util.Log;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// android 7 runs the webview renderer inside our process and leaves its main thread at normal priority
// while the compositor and render threads get display priority
// that thread runs all page script style and layout so it competes with our worker pools on a busy dashboard
// display priority lets it win the cpu when all four cores are taken
public final class RendererPriority {
    private static final String TAG = "RendererPriority";
    // comm keeps the first 15 characters of Chrome_InProcRendererThread
    private static final String RENDERER_THREAD = "Chrome_InProcRe";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RendererPriority");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private RendererPriority() {}

    // the renderer thread exists once the first page started so callers run this after a page load
    public static void boostAsync() {
        EXECUTOR.execute(RendererPriority::boost);
    }

    private static void boost() {
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) return;
        for (File task : tasks) {
            String comm = SysFs.readLine(new File(task, "comm").getPath());
            if (comm == null || !RENDERER_THREAD.equals(comm.trim())) continue;
            try {
                int tid = Integer.parseInt(task.getName());
                if (Process.getThreadPriority(tid) <= Process.THREAD_PRIORITY_DISPLAY) continue;
                Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DISPLAY);
                Log.i(TAG, "renderer thread " + tid + " now at display priority");
            } catch (RuntimeException e) {
                Log.w(TAG, "could not raise the renderer thread: " + e.getMessage());
            }
        }
    }
}
