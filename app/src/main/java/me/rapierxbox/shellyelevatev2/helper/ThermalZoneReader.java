package me.rapierxbox.shellyelevatev2.helper;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ThermalZoneReader {

    private static final String TAG = "ThermalZoneReader";
    private static final String THERMAL_BASE = "/sys/class/thermal";

    public static final class Zone {
        public final String path;
        public final String type;

        Zone(String path, String type) {
            this.path = path;
            this.type = type;
        }
    }

    private static volatile List<Zone> cachedZones = null;

    public static List<Zone> discoverZones() {
        if (cachedZones != null && !cachedZones.isEmpty()) return cachedZones;

        List<Zone> zones = new ArrayList<>();
        File base = new File(THERMAL_BASE);
        File[] dirs = base.listFiles(f -> f.isDirectory() && f.getName().startsWith("thermal_zone"));
        if (dirs == null) {
            Log.w(TAG, "No thermal zones found at " + THERMAL_BASE);
            cachedZones = Collections.unmodifiableList(zones);
            return cachedZones;
        }

        for (File dir : dirs) {
            String rawType = readLine(dir.getAbsolutePath() + "/type");
            if (rawType == null) continue;
            String sanitized = rawType.trim().replaceAll("[^a-zA-Z0-9_\\-]", "_");
            if (!sanitized.isEmpty()) {
                zones.add(new Zone(dir.getAbsolutePath(), sanitized));
            }
        }

        cachedZones = Collections.unmodifiableList(zones);
        return cachedZones;
    }

    public static Float readZoneTempC(Zone zone) {
        String raw = readLine(zone.path + "/temp");
        if (raw == null) return null;
        try {
            return Float.parseFloat(raw.trim()) / 1000f;
        } catch (NumberFormatException e) {
            return null;
        }
    }

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
