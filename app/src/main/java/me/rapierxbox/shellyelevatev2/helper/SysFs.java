package me.rapierxbox.shellyelevatev2.helper;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

// plain file access for sysfs and proc nodes
public final class SysFs {
    private static final String TAG = "SysFs";

    private SysFs() {}

    // null when the node is missing or unreadable
    public static String readLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (IOException e) {
            // no stack trace since a missing node can be polled every few seconds #104
            Log.w(TAG, "cannot read " + path + ": " + e.getMessage());
            return null;
        }
    }

    // never null. an unreadable file comes back empty
    public static String readAll(String path) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append('\n');
            }
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + path + ": " + e.getMessage());
        }
        return content.toString();
    }

    public static boolean write(String path, String value) {
        try (FileWriter w = new FileWriter(path)) {
            w.write(value);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + path + ": " + e.getMessage());
            return false;
        }
    }
}
