package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.SP_DIMMER_LAST_BRIGHTNESS;
import static me.rapierxbox.shellyelevatev2.Constants.SP_DIMMER_LAST_STATE;
import static me.rapierxbox.shellyelevatev2.Constants.SP_DYNAMIC_TEMP_OFFSET_BASELINE;
import static me.rapierxbox.shellyelevatev2.Constants.SP_DYNAMIC_TEMP_OFFSET_ENABLED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_DYNAMIC_TEMP_OFFSET_K;
import static me.rapierxbox.shellyelevatev2.Constants.SP_DYNAMIC_TEMP_OFFSET_ZONE;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.stes.StesProtocolHandler;

public class DeviceHelper {

    private static final String TAG = "DeviceHelper";

    // per relay the candidate sysfs nodes in probe order
    private static final String[][] RELAY_FILES = {
            {
                    "/sys/devices/platform/leds/red_enable",
                    "/sys/class/strelay/relay1"
            },
            {
                    "/sys/devices/platform/leds/green_enable",
                    "/sys/class/strelay/relay2"
            }
    };

    private static final String TEMP_AND_HUM_FILE = "/sys/devices/platform/sht3x-user/sht3x_access";

    // newer hardware dropped the sht3x entirely (x2i confirmed #104) and the node then throws ENOENT on every read
    // probed once at class load since a sysfs node the kernel makes at boot cant show up later
    private static final boolean TEMP_AND_HUM_SENSOR_PRESENT = new File(TEMP_AND_HUM_FILE).exists();

    // returned by the sensor getters when there is no valid reading
    private static final double INVALID_READING = -999;

    private static final String[] SCREEN_BRIGHTNESS_FILES = {
            "/sys/devices/platform/leds-mt65xx/leds/lcd-backlight/brightness",
            "/sys/devices/platform/sprd_backlight/backlight/sprd_backlight/brightness",
            "/sys/devices/platform/backlight/backlight/backlight/brightness"
    };

    // hidden PowerManager reason constants (GO_TO_SLEEP_REASON_APPLICATION and WAKE_REASON_APPLICATION)
    private static final int GO_TO_SLEEP_REASON_APPLICATION = 0;
    private static final int WAKE_REASON_APPLICATION = 0;
    private static final String WAKE_REASON_TAG = "ShellyElevate:X2i:wake";

    // runs the root shell power fallbacks off the caller thread
    private static final ExecutorService POWER_EXEC = Executors.newSingleThreadExecutor();

    private final DeviceModel deviceModel;
    private String screenBrightnessFile;
    private boolean brightnessModeSet = false;
    private boolean screenOn = true;
    private int lastScreenBrightness;

    // true between a requestAndroidSleep() and its matching wake
    // screenmanager calls wakeScreen() on every screensaver exit but the panel is only actually asleep when we put it there
    // issuing a wake for a display that is already lit used to blank it through the keyevent fallback #102
    private volatile boolean androidSleepIssued = false;

    public DeviceHelper() {
        this.deviceModel = DeviceModel.getReportedDevice();
        if (!deviceModel.usesAndroidBrightness) {
            screenBrightnessFile = firstExistingFile(SCREEN_BRIGHTNESS_FILES);
            if (screenBrightnessFile.isEmpty()) {
                Log.wtf(TAG, "No brightness file found");
            }
        }
    }

    // screenmanager owns the brightness target so only the flag is tracked here
    public void setScreenOn(boolean on) {
        screenOn = on;
    }

    public boolean getScreenOn() {
        return screenOn;
    }

    public void setScreenBrightness(int brightness) {
        setScreenBrightness(brightness, false);
    }

    // force skips the dedup so the screen off retry reaches panels that swallow the first brightness=0 write (x2i jenna)
    public void setScreenBrightness(int brightness, boolean force) {
        if (!force && lastScreenBrightness == brightness) return;

        lastScreenBrightness = brightness;
        if (mMQTTServer != null) mMQTTServer.publishScreenBrightness(brightness);
        writeScreenBrightness(brightness);
    }

    private void writeScreenBrightness(int brightness) {
        brightness = Math.max(0, Math.min(brightness, 255));
        if (BuildConfig.DEBUG) Log.d(TAG, "Set brightness to: " + brightness);

        if (deviceModel.usesAndroidBrightness) {
            // x2i (jenna) sysfs backlight is EACCES so go through Settings.System instead
            // WRITE_SETTINGS is requested in MainActivity.onCreate
            if (!Settings.System.canWrite(mApplicationContext)) {
                Log.w(TAG, "Cannot set Android screen brightness: WRITE_SETTINGS is not granted");
                return;
            }
            ensureManualBrightnessMode();
            boolean ok = Settings.System.putInt(contentResolver(), Settings.System.SCREEN_BRIGHTNESS, brightness);
            if (!ok) Log.w(TAG, "Failed to set Android screen brightness via Settings.System");
            return;
        }

        // auto brightness would fight the sysfs write so switch it off once
        // selinux denials on the sysfs write are expected and harmless on rooted devices in permissive mode
        if (!brightnessModeSet) {
            if (Settings.System.canWrite(mApplicationContext)) {
                ensureManualBrightnessMode();
            } else {
                Log.i(TAG, "Please disable androids automatic brightness or give the app the change settings permission.");
            }
        }

        writeFileContent(screenBrightnessFile, String.valueOf(brightness));
    }

    // only has to succeed once so later frames skip the settings write
    private void ensureManualBrightnessMode() {
        if (brightnessModeSet) return;
        if (Settings.System.putInt(contentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)) {
            brightnessModeSet = true;
        }
    }

    private static ContentResolver contentResolver() {
        return mApplicationContext.getContentResolver();
    }

    public int getScreenBrightness() {
        if (deviceModel.usesAndroidBrightness) {
            try {
                return Settings.System.getInt(contentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            } catch (Settings.SettingNotFoundException ignored) {
                return lastScreenBrightness;
            }
        }
        String raw = digitsOnly(readFileContent(screenBrightnessFile));
        if (raw.isEmpty()) return lastScreenBrightness;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return lastScreenBrightness;
        }
    }

    // puts the display to sleep on devices that use the android power path (x2i jenna)
    // every other device sleeps by writing brightness 0 so this is a no-op there
    // tries the hidden PowerManager.goToSleep first which needs DEVICE_POWER
    // then falls back to cmd power sleep and KEYCODE_SLEEP through the root shell
    public void requestAndroidSleep() {
        if (!deviceModel.usesAndroidPowerManager) return;

        // record the intent before trying so the matching wake is let through even if every path below fails
        androidSleepIssued = true;

        // synchronous so callers can rely on ordering
        if (tryPowerManagerSleep()) return;

        // KEYCODE_SLEEP and not KEYCODE_POWER because it only ever turns the display off
        // this path gets duplicate requests (INTENT_TURN_SCREEN_OFF and the screen off saver) so a toggle would light a blanked panel again
        POWER_EXEC.execute(() -> {
            PrivilegedShell.Result r = PrivilegedShell.runShell("cmd power sleep");
            if (r.ok()) {
                Log.i(TAG, "requestAndroidSleep: display off via shell fallback");
                return;
            }
            Log.w(TAG, "requestAndroidSleep shell fallback failed: " + r.stderr.trim());

            r = PrivilegedShell.runShell("input keyevent 223");
            if (r.ok()) {
                Log.i(TAG, "requestAndroidSleep: display off via keyevent fallback");
            } else {
                Log.w(TAG, "requestAndroidSleep keyevent fallback failed: " + r.stderr.trim());
            }
        });
    }

    // wakes the display on devices that use the android power path (x2i jenna)
    // PowerManager.wakeUp over reflection turns the display on without holding a wake lock afterwards
    // falls back to KEYCODE_WAKEUP through the root shell
    public void requestAndroidWake() {
        if (!deviceModel.usesAndroidPowerManager) return;

        // nothing to wake when the panel is lit and we never put it to sleep
        // without this the fallback ran on every screensaver exit and back when it was a power toggle it blanked the display a second later #102
        // isInteractive() still catches a display that android or the power button put to sleep behind our back
        if (!androidSleepIssued && isDisplayInteractive()) return;
        androidSleepIssued = false;

        // synchronous so callers can rely on ordering
        if (tryPowerManagerWake()) return;

        // KEYCODE_WAKEUP only ever turns the display on so it stays safe even if the display woke by itself before the shell call lands
        POWER_EXEC.execute(() -> {
            PrivilegedShell.Result r = PrivilegedShell.runShell("input keyevent 224");
            if (r.ok()) {
                Log.i(TAG, "requestAndroidWake: display on via keyevent fallback");
            } else {
                Log.w(TAG, "requestAndroidWake shell fallback failed: " + r.stderr.trim());
            }
        });
    }

    // defaults to true when powermanager cant be reached so an unknown state never triggers a wake we cant justify
    private boolean isDisplayInteractive() {
        try {
            PowerManager pm = powerManager();
            return pm == null || pm.isInteractive();
        } catch (Exception e) {
            Log.w(TAG, "isInteractive check failed, assuming the display is on: " + e.getMessage());
            return true;
        }
    }

    private static PowerManager powerManager() {
        return (PowerManager) mApplicationContext.getSystemService(Context.POWER_SERVICE);
    }

    private boolean tryPowerManagerSleep() {
        PowerManager pm = powerManager();
        if (pm == null) return false;

        long now = SystemClock.uptimeMillis();
        // goToSleep(long int int) is the current hidden signature and goToSleep(long) covers older builds
        boolean ok = invokeHidden(pm, "goToSleep",
                        new Class<?>[]{long.class, int.class, int.class},
                        new Object[]{now, GO_TO_SLEEP_REASON_APPLICATION, 0}, "requestAndroidSleep")
                || invokeHidden(pm, "goToSleep",
                        new Class<?>[]{long.class},
                        new Object[]{now}, "requestAndroidSleep");

        if (ok) {
            Log.i(TAG, "requestAndroidSleep: display off via PowerManager.goToSleep");
        } else {
            Log.w(TAG, "requestAndroidSleep: no usable PowerManager.goToSleep, using shell fallback");
        }
        return ok;
    }

    private boolean tryPowerManagerWake() {
        PowerManager pm = powerManager();
        if (pm == null) return false;

        long now = System.currentTimeMillis();
        // android 10 replaced wakeUp(long String) with wakeUp(long int String)
        // probing only the old one sent every x2i (android 11) wake to the shell fallback
        boolean ok = invokeHidden(pm, "wakeUp",
                        new Class<?>[]{long.class, int.class, String.class},
                        new Object[]{now, WAKE_REASON_APPLICATION, WAKE_REASON_TAG}, "requestAndroidWake")
                || invokeHidden(pm, "wakeUp",
                        new Class<?>[]{long.class, String.class},
                        new Object[]{now, WAKE_REASON_TAG}, "requestAndroidWake")
                || invokeHidden(pm, "wakeUp",
                        new Class<?>[]{long.class},
                        new Object[]{now}, "requestAndroidWake");

        if (ok) {
            Log.i(TAG, "requestAndroidWake: display on via PowerManager.wakeUp");
        } else {
            Log.w(TAG, "requestAndroidWake: no usable PowerManager.wakeUp, using shell fallback");
        }
        return ok;
    }

    // calls a hidden powermanager method and returns true when it went through
    // a missing signature stays silent so the caller can probe the next one
    // the target throw arrives wrapped in InvocationTargetException so the real cause (usually SecurityException for DEVICE_POWER) is unwrapped for the log
    private static boolean invokeHidden(PowerManager pm, String name, Class<?>[] paramTypes,
                                        Object[] args, String logPrefix) {
        try {
            Method method = PowerManager.class.getMethod(name, paramTypes);
            method.invoke(pm, args);
            return true;
        } catch (NoSuchMethodException e) {
            // expected while probing
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Log.w(TAG, logPrefix + ": " + name + "/" + paramTypes.length + " threw "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (Exception e) {
            Log.w(TAG, logPrefix + ": " + name + "/" + paramTypes.length + " unavailable: " + e);
        }
        return false;
    }

    public boolean getRelay(int num) {
        return readFileContent(getRelayFile(num)).contains("1") ^ deviceModel.invertRelay;
    }

    public void setRelay(int num, boolean state) {
        // a missing relay would only log a failed write and publish a phantom state
        if (num < 0 || num >= deviceModel.relays) {
            Log.w(TAG, "Ignoring relay " + num + " since " + deviceModel.displayName + " has " + deviceModel.relays);
            return;
        }
        boolean physicalState = state ^ deviceModel.invertRelay;
        if (deviceModel.usesInitScriptRelay()) {
            triggerInitRelay(num, physicalState);
        } else {
            writeFileContent(getRelayFile(num), physicalState ? "1" : "0");
        }
        // publish the logical state so mqtt matches getRelay and the http api
        if (mMQTTServer != null && mMQTTServer.shouldSend()) {
            mMQTTServer.publishRelay(num, state);
        }
    }

    // newer models drive relays through init.rc scripts
    // the wanted state goes into a system property and ctl.start runs the script
    private void triggerInitRelay(int num, boolean state) {
        String[] scripts = deviceModel.initRelayScripts;
        if (scripts == null || num < 0 || num >= scripts.length) return;
        String scriptName = scripts[num];
        String value = state ? "1" : "0";
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method set = systemProperties.getMethod("set", String.class, String.class);
            set.invoke(null, "shelly.relay." + num + ".state", value);
            set.invoke(null, "ctl.start", scriptName);
        } catch (Exception e) {
            Log.w(TAG, "Init relay failed for " + scriptName + ", falling back to sysfs: " + e.getMessage());
            writeFileContent(getRelayFile(num), value);
        }
    }

    // empty when the relay index is unknown or none of its nodes exist
    public static String getRelayFile(int i) {
        if (i < 0 || i >= RELAY_FILES.length) return "";
        return firstExistingFile(RELAY_FILES[i]);
    }

    private static String firstExistingFile(String[] candidates) {
        for (String path : candidates) {
            if (new File(path).exists()) return path;
        }
        return "";
    }

    // mqtt discovery uses this to skip entities that could never report
    public static boolean hasTempAndHumSensor() {
        return TEMP_AND_HUM_SENSOR_PRESENT;
    }

    public double getTemperature() {
        if (!TEMP_AND_HUM_SENSOR_PRESENT) return INVALID_READING;
        try {
            String[] raw = readSht3xRaw();
            if (raw == null) return INVALID_READING;
            // sht3x datasheet conversion from the raw 16 bit ticks
            double temp = (Double.parseDouble(raw[1].trim()) * 175.0 / 65535.0) - 45.0;

            temp += deviceModel.temperatureOffset;
            temp -= getDynamicTempCorrection();
            return Math.round(temp * 10.0) / 10.0;
        } catch (Exception e) {
            Log.d(TAG, "Error while reading temperature: " + e);
            return INVALID_READING;
        }
    }

    // soc heat leaks into the sht3x so subtract k degrees for every degree the chosen zone runs above baseline
    // never negative so a cold soc cannot push the reading up
    public double getDynamicTempCorrection() {
        if (!mSharedPreferences.getBoolean(SP_DYNAMIC_TEMP_OFFSET_ENABLED, false)) return 0.0;
        String zone = mSharedPreferences.getString(SP_DYNAMIC_TEMP_OFFSET_ZONE, null);
        if (zone == null || zone.isEmpty()) return 0.0;
        Float zoneTemp = ThermalZoneReader.readZoneTempCByType(zone);
        if (zoneTemp == null) return 0.0;
        float baseline = mSharedPreferences.getFloat(SP_DYNAMIC_TEMP_OFFSET_BASELINE, 40.0f);
        float k = mSharedPreferences.getFloat(SP_DYNAMIC_TEMP_OFFSET_K, 0.3f);
        return Math.max(0.0, (zoneTemp - baseline) * k);
    }

    public double getHumidity() {
        if (!TEMP_AND_HUM_SENSOR_PRESENT) return INVALID_READING;
        try {
            String[] raw = readSht3xRaw();
            if (raw == null) return INVALID_READING;
            double humidity = Double.parseDouble(raw[0].trim()) * 100.0 / 65535.0;

            humidity += deviceModel.humidityOffset;
            return Math.round(humidity);
        } catch (Exception e) {
            Log.d(TAG, "Error while reading humidity: " + e);
            return INVALID_READING;
        }
    }

    // the node reads as humidity:temperature in raw ticks. null when it is empty or malformed
    private static String[] readSht3xRaw() {
        String content = readFileContent(TEMP_AND_HUM_FILE).trim();
        if (content.isEmpty()) return null;
        String[] parts = content.split(":");
        return parts.length < 2 ? null : parts;
    }

    public boolean isDimmerAttached() {
        return StesProtocolHandler.isOperational();
    }

    public void setDimmerBrightness(int percent0to100, Runnable onComplete) {
        // stes takes brightness in tenths of a percent
        int stesBrightness = Math.round(percent0to100 * 10.0f);
        StesProtocolHandler.setDimmer(stesBrightness, new StesProtocolHandler.OnDimmerListener() {
            @Override
            public void onResult(StesProtocolHandler.DimmerStatus s) {
                boolean on = percent0to100 > 0;
                SharedPreferences.Editor editor = mSharedPreferences.edit().putBoolean(SP_DIMMER_LAST_STATE, on);
                // keep the last level on turn off so the next turn on restores it
                if (on) editor.putInt(SP_DIMMER_LAST_BRIGHTNESS, percent0to100);
                editor.apply();
                if (mMQTTServer != null && mMQTTServer.shouldSend()) {
                    mMQTTServer.publishDimmer(on, percent0to100);
                }
                if (onComplete != null) onComplete.run();
            }

            @Override
            public void onError(String e) {
                if (onComplete != null) onComplete.run();
            }
        });
    }

    public void setDimmerOn(boolean on) {
        int lastBrightness = mSharedPreferences.getInt(SP_DIMMER_LAST_BRIGHTNESS, 100);
        // older builds stored 0 on turn off
        if (lastBrightness <= 0) lastBrightness = 100;
        setDimmerBrightness(on ? lastBrightness : 0, null);
    }

    public StesProtocolHandler.DimmerStatus getDimmerStatus() {
        return StesProtocolHandler.lastStatus;
    }

    public StesProtocolHandler.DimmerPower getDimmerPower() {
        return StesProtocolHandler.lastPower;
    }

    private static String digitsOnly(String input) {
        if (input == null) return "";
        return input.replaceAll("[^0-9]", "");
    }

    // never null. an unreadable file comes back empty
    private static String readFileContent(String filePath) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            // no stack trace since the path is in the message and a missing sysfs node used to dump four traces a minute #104
            Log.w(TAG, "Error when reading file with path:" + filePath + " " + e);
        }
        return content.toString();
    }

    private static void writeFileContent(String filePath, String content) {
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
        } catch (IOException e) {
            Log.e(TAG, "Error when writing file with path:" + filePath, e);
        }
    }
}
