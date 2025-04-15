package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.SP_AUTOMATIC_BRIGHTNESS;
import static me.rapierxbox.shellyelevatev2.Constants.SP_BRIGHTNESS;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DeviceHelper {
    private static final String[] possibleRelayFiles = {
            "/sys/devices/platform/leds/green_enable",
            "/sys/devices/platform/leds/red_enable",
            "/sys/class/strelay/relay1",
            "/sys/class/strelay/relay2"
    };
    private static final String tempAndHumFile = "/sys/devices/platform/sht3x-user/sht3x_access";
    private static final String[] screenBrightnessFiles = {
            "/sys/devices/platform/leds-mt65xx/leds/lcd-backlight/brightness",
            "/sys/devices/platform/sprd_backlight/backlight/sprd_backlight/brightness",
            "/sys/devices/platform/backlight/backlight/backlight/brightness"
            };
    private String screenBrightnessFile;
    private final String[] relayFiles;
    private boolean screenOn = true;
    private int screenBrightness;
    private boolean automaticBrightness;

    private final String TAG = "DeviceHelper";

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

        List<String> relayFileList = new ArrayList<>();
        for (String relayFile : possibleRelayFiles) {
            if (new File(relayFile).exists()){
                relayFileList.add(relayFile);
            }
        }
        if (relayFileList.isEmpty()) {
            Log.e("FATAL ERROR", "no relay files found");
            relayFileList.add("");
        }
        relayFiles = relayFileList.toArray(new String[0]);

        updateValues();
    }

    public void setScreenOn(boolean on) {
        screenOn = on;
        int brightness = automaticBrightness ? DeviceSensorManager.getScreenBrightnessFromLux(mDeviceSensorManager.getLastMeasuredLux()) : screenBrightness;
        forceScreenBrightness(on ? brightness : 0);
    }

    public boolean getScreenOn() {
        return screenOn;
    }

    public void setScreenBrightness(int brightness) {
        if (!screenOn)
            return;

        forceScreenBrightness(brightness);

        if (!automaticBrightness) {
            mSharedPreferences.edit().putInt(SP_BRIGHTNESS, brightness).apply();
            screenBrightness = brightness;
        }
    }

    public void forceScreenBrightness(int brightness) {
        brightness = Math.max(0, Math.min(brightness, 255));

        Log.d(TAG, "Set brightness to: " + brightness);

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
        if (mMQTTServer.shouldSend()) {
            mMQTTServer.publishRelay(state);
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

    public void updateValues() {
        screenBrightness = mSharedPreferences.getInt(SP_BRIGHTNESS, 255);
        automaticBrightness = mSharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true);
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
