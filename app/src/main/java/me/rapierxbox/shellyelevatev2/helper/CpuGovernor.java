package me.rapierxbox.shellyelevatev2.helper;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class CpuGovernor {

    private static final String TAG = "CpuGovernor";
    private static final String CPU_BASE = "/sys/devices/system/cpu";
    private static final Pattern CPU_DIR = Pattern.compile("cpu[0-9]+");

    private static final List<String> PREFERRED_LOW_POWER = Arrays.asList(
            "powersave", "conservative", "ondemand", "schedutil"
    );

    private static volatile List<String> cachedCpuPaths = null;
    private final Map<String, String> savedGovernors = new HashMap<>();
    private volatile boolean denied = false;

    public List<String> discover() {
        List<String> cached = cachedCpuPaths;
        if (cached != null) return cached;

        List<String> paths = new ArrayList<>();
        File base = new File(CPU_BASE);
        File[] dirs = base.listFiles(f -> f.isDirectory() && CPU_DIR.matcher(f.getName()).matches());
        if (dirs != null) {
            for (File dir : dirs) {
                File gov = new File(dir, "cpufreq/scaling_governor");
                if (gov.exists()) paths.add(gov.getAbsolutePath());
            }
        }
        Collections.sort(paths);
        cachedCpuPaths = Collections.unmodifiableList(paths);
        return cachedCpuPaths;
    }

    private static List<String> readAvailable(String governorPath) {
        String availPath = governorPath.replace("scaling_governor", "scaling_available_governors");
        String raw = readLine(availPath);
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        return Arrays.asList(raw.trim().split("\\s+"));
    }

    private static String pickLowPower(List<String> available) {
        if (available == null || available.isEmpty()) return "powersave";
        for (String pref : PREFERRED_LOW_POWER) {
            if (available.contains(pref)) return pref;
        }
        return available.get(0);
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
            String current = readLine(path);
            if (current == null) continue;
            current = current.trim();
            if (!savedGovernors.containsKey(path)) {
                savedGovernors.put(path, current);
            }
            String target = pickLowPower(readAvailable(path));
            if (target.equals(current)) {
                applied++;
                continue;
            }
            if (writeFile(path, target)) {
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
            if (writeFile(e.getKey(), e.getValue())) restored++;
        }
        Log.i(TAG, "Restored governor for " + restored + "/" + savedGovernors.size() + " CPUs");
        savedGovernors.clear();
    }

    private static String readLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean writeFile(String path, String value) {
        try (FileWriter w = new FileWriter(path)) {
            w.write(value);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
