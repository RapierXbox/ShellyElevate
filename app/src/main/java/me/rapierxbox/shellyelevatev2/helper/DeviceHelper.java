package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;

import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Objects;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.DeviceModel;

public class DeviceHelper {

    private static final String[][] possibleRelayFiles = {
            {
                    "/sys/devices/platform/leds/green_enable",
                    "/sys/class/strelay/relay1"
            },
            {
                    "/sys/devices/platform/leds/red_enable",
                    "/sys/class/strelay/relay2"
            }
    };

    private static final String tempAndHumFile = "/sys/devices/platform/sht3x-user/sht3x_access";
    private static final String[] screenBrightnessFiles = {
            "/sys/devices/platform/leds-mt65xx/leds/lcd-backlight/brightness",
            "/sys/devices/platform/sprd_backlight/backlight/sprd_backlight/brightness",
            "/sys/devices/platform/backlight/backlight/backlight/brightness"
    };
    private String screenBrightnessFile;
    private boolean screenOn = true;
    private int lastScreenBrightness;

    private static final String TAG = "DeviceHelper";

    public DeviceHelper() {
        for (String brightnessFile : screenBrightnessFiles) {
            if (new File(brightnessFile).exists()) {
                screenBrightnessFile = brightnessFile;
            }
        }
        if (screenBrightnessFile == null) {
            Log.wtf(TAG, "No brightness file found");
            screenBrightnessFile = "";
        }
    }

    public void setScreenOn(boolean on) {
        screenOn = on;

        // Don't write brightness here; let ScreenManager control it via updateBrightness()
        // Avoid redundant writes and let the brightness manager decide the target
    }

    public boolean getScreenOn() {
        return screenOn;
    }

    public void setScreenBrightness(int brightness) {
        // Skip redundant writes to avoid duplicate logs and I/O
        if (lastScreenBrightness == brightness) return;

        lastScreenBrightness = brightness;
        setScreenBrightnessInternal(brightness);
    }

    private void setScreenBrightnessInternal(int brightness){
        mMQTTServer.publishScreenBrightness(brightness);

        writeScreenBrightness(brightness);
    }

    private void writeScreenBrightness(int brightness) {
        brightness = Math.max(0, Math.min(brightness, 255));
        if (BuildConfig.DEBUG) Log.d(TAG, "Set brightness to: " + brightness);

        // Check for WRITE_SETTINGS permission (requested in MainActivity.onCreate)
        // Note: SELinux denials for sysfs access (avc: denied { write } for name="brightness")
        // are expected and work in permissive mode on rooted Shelly devices
        if (!Settings.System.canWrite(mApplicationContext)) {
            Log.i(TAG, "Please disable androids automatic brightness or give the app the change settings permission.");
        } else {
            Settings.System.putInt(mApplicationContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        }

        writeFileContent(screenBrightnessFile, String.valueOf(brightness));
    }

    public int getScreenBrightness() {
        return Integer.parseInt(sanitizeString(readFileContent(screenBrightnessFile)));
    }

    public boolean getRelay(int num) {
        boolean relayState = false;

        // Safety check
        if (num < 0 || num >= possibleRelayFiles.length) return false;

        for (String relayFile : possibleRelayFiles[num]) {
            String content = readFileContent(relayFile);
            if (content != null) {
                relayState |= content.contains("1");
            }
        }

        return relayState;
    }

    public void setRelay(int num, boolean state) {
        // Safety check
        if (num < 0 || num >= possibleRelayFiles.length) return;

        for (String relayFile : possibleRelayFiles[num]) {
            writeFileContent(relayFile, state ? "1" : "0");
        }

        if (mMQTTServer.shouldSend()) {
            mMQTTServer.publishRelay(num, state);
        }
    }

    public double getTemperature() {
        try
        {
            var content = readFileContent(tempAndHumFile);
            if (content == null || content.isEmpty()) return -999;

            String[] tempSplit = content.split(":");
            double temp = (Double.parseDouble(tempSplit[1]) * 175.0 / 65535.0) - 45.0;

            temp += DeviceModel.getReportedDevice().temperatureOffset;
            return Math.round(temp * 10.0) / 10.0;
        } catch (Exception e) {
            Log.d("TAG", "Error while reading temperature: " + e);
            return -999;
        }
    }

    public double getHumidity() {
        try {
            var content = readFileContent(tempAndHumFile);
            if (content == null || content.isEmpty()) return -999;

            String[] humiditySplit = content.split(":");
            double humidity = Double.parseDouble(humiditySplit[0]) * 100.0 / 65535.0;

            humidity += DeviceModel.getReportedDevice().humidityOffset;

            return Math.round(humidity);
        } catch (Exception e) {
            Log.d("TAG", "Error while reading humidity: " + e);
            return -999;
        }
    }

    public static boolean fileExists(String filePath) {
        try {
            File file = new File(filePath);
            return file.exists() && file.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    private static String readFileContent(String filePath) {
        if (!fileExists(filePath))
            return null;

        StringBuilder content = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error when reading file with path:" + filePath + ":" + Objects.requireNonNull(e.getMessage()));
        }
        return content.toString();
    }

    private static String sanitizeString(String input) {
        if (input == null) return "";
        return input.replaceAll("[^0-9]", ""); // keep only digits
    }

    private static void writeFileContent(String filePath, String content) {
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
        } catch (IOException e) {
            Log.e(TAG, "Error when writing file with path:" + filePath + ":" + Objects.requireNonNull(e.getMessage()));
        }
    }
}
