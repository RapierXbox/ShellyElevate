package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Objects;

public class DeviceHelper {
    private static final String[] relayFiles = {"/sys/devices/platform/leds/green_enable", "/sys/devices/platform/leds/red_enable"};
    private static final String tempAndHumFile = "/sys/devices/platform/sht3x-user/sht3x_access";
    private static final String[] screenBrightnessFiles = {
            "/sys/devices/platform/leds-mt65xx/leds/lcd-backlight/brightness",
            "/sys/devices/platform/sprd_backlight/backlight/sprd_backlight/brightness",
            "/sys/devices/platform/backlight/backlight/backlight/brightness"
            };
    private String screenBrightnessFile;
    private boolean screenOn = true;

    public DeviceHelper() {
        for (String brightnessFile : screenBrightnessFiles) {
            if (new File(brightnessFile).exists()){
                screenBrightnessFile = brightnessFile;
            }
        }
        if (screenBrightnessFile == null) {
            Log.e("FATAL ERROR", "no brightness file found");
            screenBrightnessFile = "";
        }
    }

    public void setScreenOn(boolean on) {
        screenOn = on;
        forceScreenBrightness(on ? DeviceSensorManager.getScreenBrightnessFromLux(mDeviceSensorManager.getLastMeasuredLux()) : 0);
    }

    public boolean getScreenOn() {
        return screenOn;
    }

    public void setScreenBrightness(int brightness) {
        if (screenOn) {
            forceScreenBrightness(brightness);
        }
    }

    private void forceScreenBrightness(int brightness) {
        brightness = Math.max(0, Math.min(brightness, 255));
        writeFileContent(screenBrightnessFile, String.valueOf(brightness));
    }
    public int getScreenBrightness() {
        return Integer.parseInt(readFileContent(screenBrightnessFile));
    }
    public boolean getRelay() {
        boolean relayState = false;
        for (String relayFile : relayFiles) {
            relayState |= readFileContent(relayFile).contains("1");
        }
        return relayState;
    }
    public void setRelay(boolean state) {
        for (String relayFile : relayFiles) {
            writeFileContent(relayFile, state ? "1" : "0");
        }
    }
    public double getTemperature() {
        String[] tempSplit = readFileContent(tempAndHumFile).split(":");
        double temp = (((Double.parseDouble(tempSplit[1]) * 175.0) / 65535.0) - 45.0) - 1.1;
        return Math.round(temp * 10.0) / 10.0;
    }
    public double getHumidity() {
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
            Log.e("DeviceHelper", "Error when reading file with path:" + filePath + ":" + Objects.requireNonNull(e.getMessage()));
        }
        return content.toString();
    }

    private static void writeFileContent(String filePath, String content) {
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
        } catch (IOException e) {
            Log.e("DeviceHelper", "Error when writing file with path:" + filePath + ":" + Objects.requireNonNull(e.getMessage()));
        }
    }
}
