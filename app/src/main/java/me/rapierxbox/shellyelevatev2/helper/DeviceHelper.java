package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private boolean brightnessModeSet = false;
    private boolean screenOn = true;
    private int lastScreenBrightness;
    private final DeviceModel deviceModel;

    // Executor for off-thread shell commands (sleep/wake via root shell fallback)
    private static final ExecutorService POWER_EXEC = Executors.newSingleThreadExecutor();

    private static final String TAG = "DeviceHelper";

    public DeviceHelper() {
        this.deviceModel = DeviceModel.getReportedDevice();
        if (!deviceModel.usesAndroidBrightness) {
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
    }

    public void setScreenOn(boolean on) {
        // ScreenManager owns the brightness target; just track the boolean here.
        screenOn = on;
    }

    public boolean getScreenOn() {
        return screenOn;
    }

    public void setScreenBrightness(int brightness) {
        setScreenBrightness(brightness, false);
    }

    // force skips the dedup so the screen-off retry write reaches panels that
    // swallow the first brightness=0 write (e.g. X2i/JENNA)
    public void setScreenBrightness(int brightness, boolean force) {
        if (!force && lastScreenBrightness == brightness) return;

        lastScreenBrightness = brightness;
        setScreenBrightnessInternal(brightness);
    }

    private void setScreenBrightnessInternal(int brightness){
        if (mMQTTServer != null) mMQTTServer.publishScreenBrightness(brightness);

        writeScreenBrightness(brightness);
    }

    private void writeScreenBrightness(int brightness) {
        brightness = Math.max(0, Math.min(brightness, 255));
        if (BuildConfig.DEBUG) Log.d(TAG, "Set brightness to: " + brightness);

        if (deviceModel.usesAndroidBrightness) {
            // X2i (JENNA, SKU SAWD-5A1XX10EU0): sysfs backlight node is EACCES; use the
            // Android Settings.System API instead.  WRITE_SETTINGS is requested in
            // MainActivity.onCreate; if it is not granted we log and skip.
            if (!Settings.System.canWrite(mApplicationContext)) {
                Log.w(TAG, "Cannot set Android screen brightness: WRITE_SETTINGS is not granted");
                return;
            }
            if (!brightnessModeSet) {
                if (Settings.System.putInt(mApplicationContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)) {
                    brightnessModeSet = true;
                }
            }
            boolean ok = Settings.System.putInt(
                    mApplicationContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness);
            if (!ok) Log.w(TAG, "Failed to set Android screen brightness via Settings.System");
            return;
        }

        // SELinux denials for the sysfs write are expected and harmless on rooted
        // Shelly devices running permissive mode. WRITE_SETTINGS is requested in
        // MainActivity.onCreate so we can disable Android's automatic brightness.
        // only needs to succeed once so skip the settings write on later frames
        if (!brightnessModeSet) {
            if (!Settings.System.canWrite(mApplicationContext)) {
                Log.i(TAG, "Please disable androids automatic brightness or give the app the change settings permission.");
            } else if (Settings.System.putInt(mApplicationContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)) {
                brightnessModeSet = true;
            }
        }

        writeFileContent(screenBrightnessFile, String.valueOf(brightness));
    }

    public int getScreenBrightness() {
        if (deviceModel.usesAndroidBrightness) {
            try {
                return Settings.System.getInt(
                        mApplicationContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS);
            } catch (Settings.SettingNotFoundException ignored) {
                return lastScreenBrightness;
            }
        }
        String raw = sanitizeString(readFileContent(screenBrightnessFile));
        if (raw.isEmpty()) return lastScreenBrightness;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return lastScreenBrightness;
        }
    }

    /**
     * Requests an Android PowerManager sleep on devices that use the Android power path
     * (currently X2i / JENNA).  For other devices this is a no-op; sleep is handled by
     * setting brightness to 0.
     *
     * <p>Primary path: reflection call to {@code PowerManager.goToSleep()} which requires the
     * {@code DEVICE_POWER} permission (granted to system/privileged apps on a rooted device).
     * Fallback: {@code cmd power sleep} via root shell.
     */
    public void requestAndroidSleep() {
        if (!deviceModel.usesAndroidPowerManager) return;
        POWER_EXEC.execute(() -> {
            if (tryPowerManagerSleep()) return;
            // Fallback: root shell – works on rooted devices even without DEVICE_POWER
            PrivilegedShell.Result r = PrivilegedShell.runShell("cmd power sleep");
            if (!r.ok()) {
                Log.w(TAG, "requestAndroidSleep shell fallback failed: " + r.stderr.trim());
            } else {
                Log.i(TAG, "requestAndroidSleep: display off via shell fallback");
            }
        });
    }

    /**
     * Requests an Android PowerManager wake on devices that use the Android power path
     * (currently X2i / JENNA).  For other devices this is a no-op.
     *
     * <p>Uses {@code PowerManager.wakeUp()} (via reflection) to turn on the display without
     * holding a wake lock. If that fails, falls back to a root-shell power-button keyevent.
     * The app does not retain any wake lock after the call completes.
     *
     * <p>Primary path: reflection call to {@code PowerManager.wakeUp()} (API 20+).
     * Fallback: {@code input keyevent 26} via root shell (simulate power-button press).
     */
    public void requestAndroidWake() {
        if (!deviceModel.usesAndroidPowerManager) return;
        POWER_EXEC.execute(() -> {
            if (tryPowerManagerWake()) return;
            // Fallback: simulate power-button keyevent via root shell
            PrivilegedShell.Result r = PrivilegedShell.runShell("input keyevent 26");
            if (!r.ok()) {
                Log.w(TAG, "requestAndroidWake shell fallback failed: " + r.stderr.trim());
            } else {
                Log.i(TAG, "requestAndroidWake: display on via keyevent fallback");
            }
        });
    }

    /** @return true if PowerManager.goToSleep succeeded */
    private boolean tryPowerManagerSleep() {
        try {
            PowerManager pm = (PowerManager) mApplicationContext.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;
            Method goToSleep = PowerManager.class.getMethod("goToSleep", long.class);
            goToSleep.invoke(pm, System.currentTimeMillis());
            Log.i(TAG, "requestAndroidSleep: display off via PowerManager.goToSleep");
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "requestAndroidSleep: DEVICE_POWER not granted, using shell fallback: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "requestAndroidSleep: PowerManager.goToSleep unavailable: " + e.getMessage());
        }
        return false;
    }

    /** @return true if PowerManager.wakeUp succeeded */
    private boolean tryPowerManagerWake() {
        try {
            PowerManager pm = (PowerManager) mApplicationContext.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;
            // wakeUp(long time, String reason) is available from API 20; ACQUIRE_CAUSES_WAKEUP
            // is used through the PARTIAL_WAKE_LOCK acquire path, so we use the wakeUp API
            // directly to avoid permanently holding a FULL_WAKE_LOCK.
            Method wakeUp;
            try {
                wakeUp = PowerManager.class.getMethod("wakeUp", long.class, String.class);
                wakeUp.invoke(pm, System.currentTimeMillis(), "ShellyElevate:X2i:wake");
            } catch (NoSuchMethodException nsme) {
                // API < 20 fallback — plain wakeUp(long)
                wakeUp = PowerManager.class.getMethod("wakeUp", long.class);
                wakeUp.invoke(pm, System.currentTimeMillis());
            }
            Log.i(TAG, "requestAndroidWake: display on via PowerManager.wakeUp");
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "requestAndroidWake: DEVICE_POWER not granted, using shell fallback: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "requestAndroidWake: PowerManager.wakeUp unavailable: " + e.getMessage());
        }
        return false;
    }

    public boolean getRelay(int num) {
        return Objects.requireNonNull(readFileContent(getRelayFile(num))).contains("1") ^ deviceModel.invertRelay;
    }

    public void setRelay(int num, boolean state) {
        boolean physicalState = state ^ deviceModel.invertRelay;
        if (deviceModel.usesInitScriptRelay()) {
            triggerInitRelay(num, physicalState);
        } else {
            writeFileContent(getRelayFile(num), physicalState ? "1" : "0");
        }
        // publish the logical state so mqtt matches getRelay and the http api
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
            Log.e(TAG, "Error when reading file with path:" + filePath, e);
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
            Log.e(TAG, "Error when writing file with path:" + filePath, e);
        }
    }
}
