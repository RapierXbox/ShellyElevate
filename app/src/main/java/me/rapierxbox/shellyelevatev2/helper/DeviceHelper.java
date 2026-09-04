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
import java.lang.reflect.InvocationTargetException;
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

    // newer hardware dropped the sht3x entirely (x2i confirmed #104) and the node then throws ENOENT on every read.
    // probed once at class load, a sysfs node the kernel makes at boot cant show up later
    private static final boolean TEMP_AND_HUM_SENSOR_PRESENT = new File(tempAndHumFile).exists();

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

    // true between a requestAndroidSleep() and its matching wake. screenmanager calls wakeScreen() on every screensaver exit. but the panel is only autally asleep when we put it there. iissueing wake for a display that is already lit used to blank it troughthey keyevent fallback #102
    private volatile boolean androidSleepIssued = false;

    // Executor for off-thread shell commands 
    private static final ExecutorService POWER_EXEC = Executors.newSingleThreadExecutor();

    // PowerManager.GO_TO_SLEEP_REASON_APPLICATION / WAKE_REASON_APPLICATION
    private static final int GO_TO_SLEEP_REASON_APPLICATION = 0;
    private static final int WAKE_REASON_APPLICATION = 0;
    private static final String WAKE_REASON_TAG = "ShellyElevate:X2i:wake";

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
     * Fallback: {@code cmd power sleep} then {@code input keyevent 223} KEYCODE_SLEEP via root shell.
     */
    public void requestAndroidSleep() {
        if (!deviceModel.usesAndroidPowerManager) return;

        // rcord the intent before attempting it even if every path below fails the matching wake must still be allowed through
        androidSleepIssued = true;

        // Try the PowerManager path synchronously so callers can rely on ordering.
        if (tryPowerManagerSleep()) return;

        // Fallback: root shell – run off-thread to avoid blocking callers.
        // cmd power sleep first then KEYCODE_SLEEP for build whose power service has no such subcommand, KEYCODE_SLEEP rather than KEYCODE_POWER: it only ever turns the display off, so the duplicate request this path receives (both INTENT_TURN_SCREEN_OFF and the screen-off saver ask for sleep) cannot toggle a blanked panel back on.
        POWER_EXEC.execute(() -> {
            PrivilegedShell.Result r = PrivilegedShell.runShell("cmd power sleep");
            if (r.ok()) {
                Log.i(TAG, "requestAndroidSleep: display off via shell fallback");
                return;
            }
            Log.w(TAG, "requestAndroidSleep shell fallback failed: " + r.stderr.trim());

            r = PrivilegedShell.runShell("input keyevent 223");
            if (!r.ok()) {
                Log.w(TAG, "requestAndroidSleep keyevent fallback failed: " + r.stderr.trim());
            } else {
                Log.i(TAG, "requestAndroidSleep: display off via keyevent fallback");
            }
        });
    }

    /**
     * Requests an Android PowerManager wake on devices that use the Android power path
     * (currently X2i / JENNA).  For other devices this is a no-op.
     *
     * <p>Uses {@code PowerManager.wakeUp()} (via reflection) to turn on the display without
     * holding a wake lock. If that fails, falls back to a root-shell wake keyevent.
     * The app does not retain any wake lock after the call completes.
     *
     * <p>Primary path: reflection call to {@code PowerManager.wakeUp()} (API 20+).
     * Fallback: {@code input keyevent 224} (KEYCODE_WAKEUP) via root shell.
     */
    public void requestAndroidWake() {
        if (!deviceModel.usesAndroidPowerManager) return;

        // nothing to wake when the panel is already lit and we never put it to sleep. without this the fallback below ran on EVERY screensaver exit and, being a power button toggle back then, blanked the display about a second later #102
        // isInteractive() still catches a display that android or the power button put to sleep behind our back
        if (!androidSleepIssued && isDisplayInteractive()) return;
        androidSleepIssued = false;

        // Try the PowerManager path synchronously so callers can rely on ordering.
        if (tryPowerManagerWake()) return;

        // fallback: root shell, off thread so callers dont block
        // KEYCODE_WAKEUP only ever turns the display ON unlike KEYCODE_POWER, so it stays safe even if the display woke on its own before the shell call lands
        POWER_EXEC.execute(() -> {
            PrivilegedShell.Result r = PrivilegedShell.runShell("input keyevent 224");
            if (!r.ok()) {
                Log.w(TAG, "requestAndroidWake shell fallback failed: " + r.stderr.trim());
            } else {
                Log.i(TAG, "requestAndroidWake: display on via keyevent fallback");
            }
        });
    }

    // true when the display is on. defaults to true when powermanager cant be reached so an unknown state never triggers a wake we cant justify
    private boolean isDisplayInteractive() {
        try {
            PowerManager pm = (PowerManager) mApplicationContext.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Exception e) {
            Log.w(TAG, "isInteractive check failed, assuming the display is on: " + e.getMessage());
            return true;
        }
    }

    /** @return true if PowerManager.goToSleep succeeded */
    private boolean tryPowerManagerSleep() {
        PowerManager pm = (PowerManager) mApplicationContext.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return false;

        long now = android.os.SystemClock.uptimeMillis();
        // goToSleep(long, int, int) is the current hidden signature, goToSleep(long) is there for older builds. both need DEVICE_POWER
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

    /** @return true if PowerManager.wakeUp succeeded */
    private boolean tryPowerManagerWake() {
        PowerManager pm = (PowerManager) mApplicationContext.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return false;

        long now = System.currentTimeMillis();
        // android 10 replaced wakeUp(long, String) with wakeUp(long, int, String) and only the old overload was ever probed, so on the X2i (android 11) EVERY wake fell through to the shell fallback
        // wakeUp() and not a FULL_WAKE_LOCK so nothing is held once the call returns
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

    // reflection call into a hidden powermanager method. returns true when it went through, a missing signature stays silent so the caller can probe the next one
    // whatever the target throws comes back wrapped in InvocationTargetException so the real cause (usually SecurityException for the missing DEVICE_POWER) has to be unwrapped for the log
    // the old catch (SecurityException) could never match a wrapped throw, thats why the log only ever said "unavailable: null" and never named the cause
    private static boolean invokeHidden(PowerManager pm, String name, Class<?>[] paramTypes,
                                        Object[] args, String logPrefix) {
        try {
            Method method = PowerManager.class.getMethod(name, paramTypes);
            method.invoke(pm, args);
            return true;
        } catch (NoSuchMethodException e) {
            // expected while probing, caller moves on to the next signature
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

    // true when this device actually has the sht3x. mqtt discovery uses it to not offer entities that can never report
    public static boolean hasTempAndHumSensor() {
        return TEMP_AND_HUM_SENSOR_PRESENT;
    }

    public double getTemperature() {
        if (!TEMP_AND_HUM_SENSOR_PRESENT) return -999;
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
        if (!TEMP_AND_HUM_SENSOR_PRESENT) return -999;
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
            // no stack trace. the path is already in the message and a missing sysfs node used to dump four full traces a minute #104
            Log.w(TAG, "Error when reading file with path:" + filePath + " " + e);
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
