package me.rapierxbox.shellyelevatev2.helper;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// tells which app activity is in front so modules can keep an external app there
// usage stats is cheap and the dumpsys fallback covers firmware where the appop did not stick
public final class ForegroundDetector {
    private static final String TAG = "ForegroundDetector";

    // first query looks back this far and later ones only read what is new
    private static final long INITIAL_WINDOW_MS = 24L * 60 * 60 * 1000;
    private static final long OVERLAP_MS = 2_000;
    // the appop is granted in the background at start so a denial is retried after a while
    private static final long USAGE_STATS_RETRY_MS = 60_000;
    // one watchdog pass asks several times so answers this fresh are reused
    private static final long RESULT_TTL_MS = 300;

    private static final Pattern RESUMED = Pattern.compile(
            "(?:mResumedActivity|topResumedActivity|mFocusedActivity)[^{]*\\{[^}]*?\\s([\\w.]+)/([\\w.$]+)");

    public static final class Top {
        public final String packageName;
        // may be null when only the package is known
        public final String className;

        Top(String packageName, String className) {
            this.packageName = packageName;
            this.className = className;
        }

        @Override
        public String toString() {
            return packageName + "/" + className;
        }
    }

    private static Top lastTop;
    private static long lastQueryEnd;
    private static long usageStatsRetryAt;
    // dumpsys refuses apps without the dump permission so a denial is not retried for a while
    private static final long DUMPSYS_RETRY_MS = 10 * 60_000;
    private static long dumpsysRetryAt;
    private static Top cachedTop;
    private static long cachedAt;
    private static boolean cachedValid;

    private ForegroundDetector() {}

    // null when nothing could tell. may block on a shell call so keep it off the main thread
    public static synchronized Top current(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (cachedValid && now - cachedAt < RESULT_TTL_MS) return cachedTop;
        Top top = null;
        if (System.currentTimeMillis() >= usageStatsRetryAt) top = fromUsageStats(context);
        if (top == null) top = fromDumpsys();
        cachedTop = top;
        cachedAt = now;
        cachedValid = true;
        return top;
    }

    // false while the expensive dumpsys fallback answers so callers can poll less often
    public static synchronized boolean isCheap() {
        return System.currentTimeMillis() >= usageStatsRetryAt;
    }

    private static Top fromUsageStats(Context context) {
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            usageStatsRetryAt = Long.MAX_VALUE;
            return null;
        }
        long now = System.currentTimeMillis();
        long begin = lastQueryEnd == 0 ? now - INITIAL_WINDOW_MS : lastQueryEnd - OVERLAP_MS;
        UsageEvents events;
        try {
            events = usm.queryEvents(begin, now);
        } catch (SecurityException e) {
            Log.w(TAG, "usage stats not permitted, using dumpsys");
            usageStatsRetryAt = now + USAGE_STATS_RETRY_MS;
            return null;
        }
        boolean any = false;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            any = true;
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastTop = new Top(event.getPackageName(), event.getClassName());
            }
        }
        lastQueryEnd = now;
        // a denied appop returns an empty stream instead of throwing
        if (!any && lastTop == null) {
            Log.w(TAG, "usage stats returned nothing, using dumpsys");
            usageStatsRetryAt = now + USAGE_STATS_RETRY_MS;
            lastQueryEnd = 0;
            return null;
        }
        return lastTop;
    }

    private static Top fromDumpsys() {
        if (SystemClock.elapsedRealtime() < dumpsysRetryAt) return null;
        PrivilegedShell.Result r = PrivilegedShell.runShell(
                "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|mFocusedActivity|Permission Denial'");
        if (r.stdout.contains("Permission Denial")) {
            Log.w(TAG, "dumpsys not permitted either, foreground stays unknown");
            dumpsysRetryAt = SystemClock.elapsedRealtime() + DUMPSYS_RETRY_MS;
            return null;
        }
        if (!r.ok() || r.stdout.isEmpty()) return null;
        Matcher m = RESUMED.matcher(r.stdout);
        if (!m.find()) return null;
        String pkg = m.group(1);
        String cls = m.group(2);
        if (cls != null && cls.startsWith(".")) cls = pkg + cls;
        return new Top(pkg, cls);
    }
}
