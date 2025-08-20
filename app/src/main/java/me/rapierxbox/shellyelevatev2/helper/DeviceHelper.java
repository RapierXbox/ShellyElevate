package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import me.rapierxbox.shellyelevatev2.DeviceModel;

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
    private int lastScreenBrightness;

    private static final String TAG = "DeviceHelper";

    public DeviceHelper() {
        for (String brightnessFile : screenBrightnessFiles) {
            if (new File(brightnessFile).exists()) {
                screenBrightnessFile = brightnessFile;
            }
        }
        if (screenBrightnessFile == null) {
            Log.wtf(TAG, "no brightness file found");
            screenBrightnessFile = "";
        }

        List<String> relayFileList = new ArrayList<>();
        for (String relayFile : possibleRelayFiles) {
            if (new File(relayFile).exists()) {
                relayFileList.add(relayFile);
            }
        }
        if (relayFileList.isEmpty()) {
            Log.wtf(TAG, "no relay files found");
            relayFileList.add("");
        }
        relayFiles = relayFileList.toArray(new String[0]);
    }

    public void setScreenOn(boolean on) {
        screenOn = on;

        if (screenOn) {
            writeScreenBrightness(lastScreenBrightness);
        } else {
            writeScreenBrightness(0);
        }
    }

    public boolean getScreenOn() {
        return screenOn;
    }

    public void setScreenBrightness(int brightness) {
        lastScreenBrightness = brightness;

        if (!screenOn) return;

        writeScreenBrightness(brightness);
    }

    private void writeScreenBrightness(int brightness) {
        brightness = Math.max(0, Math.min(brightness, 255));

        Log.d(TAG, "Set brightness to: " + brightness);
        if (!Settings.System.canWrite(mApplicationContext)) {
            Log.i(TAG, "Please disable androids automatic brightness or give the app the change settings permission.");
        } else {
            Settings.System.putInt(mApplicationContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        }

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
        double temp = (Double.parseDouble(tempSplit[1]) * 175.0 / 65535.0) - 45.0;

        temp += DeviceModel.getDevice(mSharedPreferences).temperatureOffset;
        return Math.round(temp * 10.0) / 10.0;
    }

    public double getHumidity() {
        String[] humiditySplit = readFileContent(tempAndHumFile).split(":");
        double humidity = Double.parseDouble(humiditySplit[0]) * 100.0 / 65535.0;

        humidity += DeviceModel.getDevice(mSharedPreferences).humidityOffset;

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
            Log.e(TAG, "Error when reading file with path:" + filePath + ":" + Objects.requireNonNull(e.getMessage()));
        }
        return content.toString();
    }

    private static void writeFileContent(String filePath, String content) {
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
        } catch (IOException e) {
            Log.e(TAG, "Error when writing file with path:" + filePath + ":" + Objects.requireNonNull(e.getMessage()));
        }
    }
}
