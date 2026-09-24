package me.rapierxbox.shellyelevatev2.helper;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// soc thermal zones from sysfs. zone temp files report millidegrees celsius
public final class ThermalZoneReader {

    private static final String TAG = "ThermalZoneReader";
    private static final String THERMAL_BASE = "/sys/class/thermal";

    public static final class Zone {
        public final String path;
        // sanitized so it can be used in mqtt topics and object ids
        public final String type;

        Zone(String path, String type) {
            this.path = path;
            this.type = type;
        }
    }

    // zones are made by the kernel at boot so one scan is enough
    private static volatile List<Zone> cachedZones = null;

    private ThermalZoneReader() {
    }

    public static List<Zone> discoverZones() {
        // an empty list is cached too so devices without zones dont rescan
        List<Zone> cached = cachedZones;
        if (cached != null) return cached;

        List<Zone> zones = new ArrayList<>();
        File[] dirs = new File(THERMAL_BASE).listFiles(
                f -> f.isDirectory() && f.getName().startsWith("thermal_zone"));
        if (dirs == null) {
            Log.w(TAG, "No thermal zones found at " + THERMAL_BASE);
        } else {
            for (File dir : dirs) {
                String rawType = readLine(dir.getAbsolutePath() + "/type");
                if (rawType == null) continue;
                String sanitized = rawType.trim().replaceAll("[^a-zA-Z0-9_\\-]", "_");
                if (!sanitized.isEmpty()) {
                    zones.add(new Zone(dir.getAbsolutePath(), sanitized));
                }
            }
        }

        cached = Collections.unmodifiableList(zones);
        cachedZones = cached;
        return cached;
    }

    // null when the zone cant be read
    public static Float readZoneTempC(Zone zone) {
        String raw = readLine(zone.path + "/temp");
        if (raw == null) return null;
        try {
            return Float.parseFloat(raw.trim()) / 1000f;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // null when no zone has this sanitized type or it cant be read
    public static Float readZoneTempCByType(String type) {
        for (Zone z : discoverZones()) {
            if (z.type.equals(type)) return readZoneTempC(z);
        }
        return null;
    }

    private static String readLine(String filePath) {
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            return br.readLine();
        } catch (IOException e) {
            Log.w(TAG, "Cannot read " + filePath + ": " + e.getMessage());
            return null;
        }
    }
}
