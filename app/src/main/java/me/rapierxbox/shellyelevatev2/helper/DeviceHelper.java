package me.rapierxbox.shellyelevatev2.helper;

import static android.content.Context.DEVICE_ID_DEFAULT;
import static me.rapierxbox.shellyelevatev2.Constants.*;
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
import java.util.Objects;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.stes.StesProtocolHandler;

public class DeviceHelper {

    private static final String[][] possibleRelayFiles = {
            {
                    "/sys/devices/platform/leds/red_enable",
                    "/sys/class/strelay/relay1"
            },
            {
                    "/sys/devices/platform/leds/green_enable",
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
    private final DeviceModel deviceModel;

    private static final String TAG = "DeviceHelper";

    public DeviceHelper() {
        this.deviceModel = DeviceModel.getReportedDevice();
        for (String brightnessFile : screenBrightnessFiles) {
            if (new File(brightnessFile).exists()) {
                screenBrightnessFile = brightnessFile;
                break;
            }
        }
        if (screenBrightnessFile == null) {
            Log.wtf(TAG, "No brightness file found");
            screenBrightnessFile = "";
        }
    }

    public void setScreenOn(boolean on) {
        // ScreenManager owns the brightness target; just track the boolean here.
        screenOn = on;
    }

    public boolean getScreenOn() {
        return screenOn;
    }

    public void setScreenBrightness(int brightness) {
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

        // SELinux denials for the sysfs write are expected and harmless on rooted
        // Shelly devices running permissive mode. WRITE_SETTINGS is requested in
        // MainActivity.onCreate so we can disable Android's automatic brightness.
        if (!Settings.System.canWrite(mApplicationContext)) {
            Log.i(TAG, "Please disable androids automatic brightness or give the app the change settings permission.");
        } else {
            Settings.System.putInt(mApplicationContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        }

        writeFileContent(screenBrightnessFile, String.valueOf(brightness));
    }

    public int getScreenBrightness() {
        String raw = sanitizeString(readFileContent(screenBrightnessFile));
        if (raw.isEmpty()) return lastScreenBrightness;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return lastScreenBrightness;
        }
    }

    public boolean getRelay(int num) {
        return Objects.requireNonNull(readFileContent(getRelayFile(num))).contains("1") ^ deviceModel.invertRelay;
    }

    public void setRelay(int num, boolean state) {
        state ^= deviceModel.invertRelay;
        if (deviceModel.usesInitScriptRelay()) {
            triggerInitRelay(num, state);
        } else {
            writeFileContent(getRelayFile(num), state ? "1" : "0");
        }
        if (mMQTTServer.shouldSend()) {
            mMQTTServer.publishRelay(num, state);
        }
    }

    private void triggerInitRelay(int num, boolean state) {
        String[] scripts = deviceModel.initRelayScripts;
        if (scripts == null || num >= scripts.length) return;
        String scriptName = scripts[num];
        try {
            // Newer models expose relays via init.rc scripts: write the desired state to
            // a system property and pulse `ctl.start` to run the script.
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, "shelly.relay." + num + ".state", state ? "1" : "0");
            set.invoke(null, "ctl.start", scriptName);
        } catch (Exception e) {
            Log.w(TAG, "Init relay failed for " + scriptName + ", falling back to sysfs: " + e.getMessage());
            writeFileContent(getRelayFile(num), state ? "1" : "0");
        }
    }

    public static String getRelayFile(int i) {
        if (0 <= i && i < possibleRelayFiles.length) {
            for (String str : possibleRelayFiles[i]) {
                if (new File(str).exists()) {
                    return str;
                }
            }
            return "";
        }
        return "";
    }

    public double getTemperature() {
        try {
            var content = readFileContent(tempAndHumFile);
            if (content.isEmpty()) return -999;

            String[] tempSplit = content.trim().split(":");
            if (tempSplit.length < 2) return -999;
            double temp = (Double.parseDouble(tempSplit[1].trim()) * 175.0 / 65535.0) - 45.0;

            temp += DeviceModel.getReportedDevice().temperatureOffset;
            temp -= getDynamicTempCorrection();
            return Math.round(temp * 10.0) / 10.0;
        } catch (Exception e) {
            Log.d("TAG", "Error while reading temperature: " + e);
            return -999;
        }
    }

    public double getDynamicTempCorrection() {
        if (!mSharedPreferences.getBoolean(SP_DYNAMIC_TEMP_OFFSET_ENABLED, false)) return 0.0;
        String zone = mSharedPreferences.getString(SP_DYNAMIC_TEMP_OFFSET_ZONE, null);
        if (zone == null || zone.isEmpty()) return 0.0;
        Float dev = ThermalZoneReader.readZoneTempCByType(zone);
        if (dev == null) return 0.0;
        float baseline = mSharedPreferences.getFloat(SP_DYNAMIC_TEMP_OFFSET_BASELINE, 40.0f);
        float k = mSharedPreferences.getFloat(SP_DYNAMIC_TEMP_OFFSET_K, 0.3f);
        return Math.max(0.0, (dev - baseline) * k);
    }

    public double getHumidity() {
        try {
            var content = readFileContent(tempAndHumFile);
            if (content.isEmpty()) return -999;

            String[] humiditySplit = content.trim().split(":");
            if (humiditySplit.length < 2) return -999;
            double humidity = Double.parseDouble(humiditySplit[0].trim()) * 100.0 / 65535.0;

            humidity += DeviceModel.getReportedDevice().humidityOffset;

            return Math.round(humidity);
        } catch (Exception e) {
            Log.d("TAG", "Error while reading humidity: " + e);
            return -999;
        }
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

    public boolean isDimmerAttached() {
        return StesProtocolHandler.isOperational();
    }

    public void setDimmerBrightness(int percent0to100, Runnable onComplete) {
        int stes = Math.round(percent0to100 * 10.0f);
        StesProtocolHandler.setDimmer(stes, new StesProtocolHandler.OnDimmerListener() {
            @Override public void onResult(StesProtocolHandler.DimmerStatus s) {
                mSharedPreferences.edit()
                    .putInt(SP_DIMMER_LAST_BRIGHTNESS, percent0to100)
                    .putBoolean(SP_DIMMER_LAST_STATE, percent0to100 > 0)
                    .apply();
                if (mMQTTServer.shouldSend()) {
                    mMQTTServer.publishDimmer(percent0to100 > 0, percent0to100);
                }
                if (onComplete != null) onComplete.run();
            }
            @Override public void onError(String e) {
                if (onComplete != null) onComplete.run();
            }
        });
    }

    public void setDimmerOn(boolean on) {
        int lastBri = mSharedPreferences.getInt(SP_DIMMER_LAST_BRIGHTNESS, 100);
        setDimmerBrightness(on ? lastBri : 0, null);
    }

    public StesProtocolHandler.DimmerStatus getDimmerStatus() {
        return StesProtocolHandler.lastStatus;
    }

    public StesProtocolHandler.DimmerPower getDimmerPower() {
        return StesProtocolHandler.lastPower;
    }

    private static String sanitizeString(String input) {
        if (input == null) return "";
        return input.replaceAll("[^0-9]", "");
    }

    private static void writeFileContent(String filePath, String content) {
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
        } catch (IOException e) {
            Log.e(TAG, "Error when writing file with path:" + filePath + ":" + Objects.requireNonNull(e.getMessage()));
        }
    }
}
