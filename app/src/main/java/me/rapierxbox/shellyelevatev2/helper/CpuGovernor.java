package me.rapierxbox.shellyelevatev2.helper;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// switches every cpu to a low power cpufreq governor during sleep and puts the old ones back afterwards
public final class CpuGovernor {

    private static final String TAG = "CpuGovernor";
    private static final String CPU_BASE = "/sys/devices/system/cpu";
    private static final Pattern CPU_DIR = Pattern.compile("cpu[0-9]+");
    private static final String FALLBACK_GOVERNOR = "powersave";

    // most frugal first
    private static final List<String> PREFERRED_LOW_POWER = Arrays.asList(
            "powersave", "conservative", "ondemand", "schedutil"
    );

    // usual defaults of android kernels in order of preference
    private static final List<String> NORMAL = Arrays.asList(
            "interactive", "schedutil", "ondemand"
    );

    private static volatile List<String> cachedCpuPaths = null;
    private final Map<String, String> savedGovernors = new HashMap<>();
    // set after the first failed write so a locked down kernel is not hammered on every sleep
    private volatile boolean denied = false;

    // scaling_governor paths of all cpus that expose one
    public List<String> discover() {
        List<String> cached = cachedCpuPaths;
        if (cached != null) return cached;

        List<String> paths = new ArrayList<>();
        File[] dirs = new File(CPU_BASE).listFiles(
                f -> f.isDirectory() && CPU_DIR.matcher(f.getName()).matches());
        if (dirs != null) {
            for (File dir : dirs) {
                File gov = new File(dir, "cpufreq/scaling_governor");
                if (gov.exists()) paths.add(gov.getAbsolutePath());
            }
        }
        Collections.sort(paths);
        cached = Collections.unmodifiableList(paths);
        cachedCpuPaths = cached;
        return cached;
    }

    private static List<String> readAvailable(String governorPath) {
        String availPath = governorPath.replace("scaling_governor", "scaling_available_governors");
        String raw = SysFs.readLine(availPath);
        // trimmed before the empty check so a blank line cant yield an empty governor name
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        return Arrays.asList(raw.trim().split("\\s+"));
    }

    private static String pickLowPower(List<String> available) {
        if (available.isEmpty()) return FALLBACK_GOVERNOR;
        for (String pref : PREFERRED_LOW_POWER) {
            if (available.contains(pref)) return pref;
        }
        return available.get(0);
    }

    // what the kernel would normally run with or the current one when none is offered
    private static String pickNormal(List<String> available, String current) {
        for (String normal : NORMAL) {
            if (available.contains(normal)) return normal;
        }
        return current;
    }

    public synchronized void applyLowPower() {
        if (denied) return;
        List<String> paths = discover();
        if (paths.isEmpty()) {
            Log.w(TAG, "No cpufreq governors discovered at " + CPU_BASE);
            return;
        }

        int applied = 0;
        for (String path : paths) {
            String current = SysFs.readLine(path);
            if (current == null) continue;
            current = current.trim();
            List<String> available = readAvailable(path);
            String target = pickLowPower(available);
            // keep the first saved value so a second apply cant record the low power governor as the original
            // a process that died asleep left the low power one behind so a normal governor is saved instead
            if (!savedGovernors.containsKey(path)) {
                savedGovernors.put(path, current.equals(target) ? pickNormal(available, current) : current);
            }
            if (target.equals(current) || SysFs.write(path, target)) {
                applied++;
            } else {
                denied = true;
                Log.w(TAG, "Governor write denied at " + path + " - giving up further attempts");
                break;
            }
        }
        Log.i(TAG, "Applied low-power governor to " + applied + "/" + paths.size() + " CPUs");
    }

    public synchronized void restore() {
        if (savedGovernors.isEmpty()) return;
        int restored = 0;
        for (Map.Entry<String, String> e : savedGovernors.entrySet()) {
            if (SysFs.write(e.getKey(), e.getValue())) restored++;
        }
        Log.i(TAG, "Restored governor for " + restored + "/" + savedGovernors.size() + " CPUs");
        savedGovernors.clear();
    }
}
