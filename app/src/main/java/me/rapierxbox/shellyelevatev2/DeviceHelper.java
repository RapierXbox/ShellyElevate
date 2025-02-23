package me.rapierxbox.shellyelevatev2;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Objects;

public class DeviceHelper {
    public static String[] relayFiles = {"/sys/devices/platform/leds/green_enable", "/sys/devices/platform/leds/red_enable"};
    public static String tempAndHumFile = "/sys/devices/platform/sht3x-user/sht3x_access";
    public static String screenBrightnessFile = "/sys/class/leds/lcd-backlight/brightness";

    private static boolean screenOn = true;
    private static int lastScreenBrightness = 255;


    public static void setScreenOn(boolean on) {
        screenOn = on;
        forceScreenBrightness(on ? lastScreenBrightness : 0);
    }

    public static boolean getScreenOn() {
        return screenOn;
    }

    public static void setScreenBrightness(int brightness) {
        if (screenOn) {
            forceScreenBrightness(brightness);
            lastScreenBrightness = brightness;
        }
    }

    private static void forceScreenBrightness(int brightness) {
        brightness = Math.max(0, Math.min(brightness, 255));
        writeFileContent(screenBrightnessFile, String.valueOf(brightness));
    }
    public static int getScreenBrightness() {
        return Integer.parseInt(readFileContent(screenBrightnessFile));
    }
    public static boolean getRelay() {
        boolean relayState = false;
        for (String relayFile : relayFiles) {
            relayState |= readFileContent(relayFile).contains("1");
        }
        return relayState;
    }
    public static void setRelay(boolean state) {
        for (String relayFile : relayFiles) {
            writeFileContent(relayFile, state ? "1" : "0");
        }
    }
    public static double getTemperature() {
        String[] tempSplit = readFileContent(tempAndHumFile).split(":");
        double temp = (((Double.parseDouble(tempSplit[1]) * 175.0) / 65535.0) - 45.0) - 1.1;
        return Math.round(temp * 10.0) / 10.0;
    }
    public static double getHumidity() {
        String[] humiditySplit = readFileContent(tempAndHumFile).split(":");
        double humidity = ((Double.parseDouble(humiditySplit[0]) * 100.0) / 65535.0) + 18.0;
        return Math.round(humidity);
    }

    private static String readFileContent(String filePath) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            Log.e("DeviceHelper", Objects.requireNonNull(e.getMessage()));
        }
        return content.toString();
    }

    private static void writeFileContent(String filePath, String content) {
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
        } catch (IOException e) {
            Log.e("DeviceHelper", Objects.requireNonNull(e.getMessage()));
        }
    }
}
