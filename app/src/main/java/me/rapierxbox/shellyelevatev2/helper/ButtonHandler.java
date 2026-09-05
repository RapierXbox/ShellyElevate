package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.BUTTON_PRESS_TYPE_LONG;
import static me.rapierxbox.shellyelevatev2.Constants.SP_BUTTON_RELAY_ENABLED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_BUTTON_RELAY_MAP_FORMAT;
import static me.rapierxbox.shellyelevatev2.Constants.SP_POWER_BUTTON_AUTO_REBOOT;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mShellyElevateJavascriptInterface;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.rapierxbox.shellyelevatev2.DeviceModel;

// single owner of the capacitive buttons and the power button
//
// all of this used to sit in MainActivity.dispatchKeyEvent. with lite mode on the HA companion app is the one in
// front, nothing of ours holds focus, no key event ever reaches us and the buttons are simply dead #101
// so the edges come off the native input monitor now, which reads /dev/input and doesnt care whats on top. the
// activity path stays for when the monitor cant open the node, both deliveries are taken and the duplicate dropped
public class ButtonHandler {
    private static final String TAG = "ButtonHandler";

    // the power button is id 140 all the way out to mqtt and the js event, thats a pre existing contract
    public static final int POWER_BUTTON_ID = 140;

    // android keycodes: 131..134 (KEYCODE_F1..F4) are capacitive buttons 0..3, 140 (KEYCODE_F10) is power
    private static final int ANDROID_KEY_BUTTON_BASE = 131;
    private static final int ANDROID_KEY_POWER = 140;
    // the same keys raw off the event node: KEY_F1..KEY_F4 = 59..62, KEY_F10 = 68
    private static final int LINUX_KEY_BUTTON_BASE = 59;
    private static final int LINUX_KEY_POWER = 68;
    private static final int MAX_BUTTONS = 4;

    // the monitor and the focused activity deliver one physical edge a few ms apart. same window and same
    // reasoning as SwInputStateMachine.DUPLICATE_WINDOW_MS
    private static final long DUPLICATE_WINDOW_MS = 300;

    private final DeviceModel device;
    // slots 0..buttons-1 are the capacitive buttons, power takes the slot after them on models that have one
    private final int powerSlot;
    private final ButtonPressDetector[] pressDetectors;
    private final Boolean[] lastDown;
    private final long[] lastEdgeAtMs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // relay writes, mqtt publishes and the reboot stay off the main thread. one thread keeps press and release ordered
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    public ButtonHandler() {
        device = DeviceModel.getReportedDevice();
        powerSlot = device.hasPowerButton ? device.buttons : -1;

        int slots = device.buttons + (device.hasPowerButton ? 1 : 0);
        pressDetectors = new ButtonPressDetector[slots];
        lastDown = new Boolean[slots];
        lastEdgeAtMs = new long[slots];
        for (int slot = 0; slot < slots; slot++) {
            pressDetectors[slot] = new ButtonPressDetector(buttonIdForSlot(slot), this::onPressTypeDetected);
        }
        Log.i(TAG, "ButtonHandler ready: buttons=" + device.buttons + " powerButton=" + device.hasPowerButton);
    }

    // handles the button keycodes, returns false for anything else so callers can pass those on
    public boolean onKeyEvent(KeyEvent event) {
        int slot = slotForButtonId(buttonIdForAndroidKey(event.getKeyCode()));
        if (slot < 0) return false;
        // auto repeat resends ACTION_DOWN while the button is held and would reset the press timer and inflate the click count
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            submitEdge(slot, true);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            submitEdge(slot, false);
        }
        return true;
    }

    // intake for the native input monitor. action 0=UP 1=DOWN 2=REPEAT
    public void onNativeKey(int linuxCode, int action) {
        int slot = slotForButtonId(buttonIdForLinuxKey(linuxCode));
        if (slot < 0) return;
        // 2 is autorepeat, swallowed here for the same reason as on the activity path
        if (action == 1) submitEdge(slot, true);
        else if (action == 0) submitEdge(slot, false);
    }

    // true while the linux code is a button edge this handler consumes
    public static boolean isNativeButtonCode(int linuxCode) {
        return buttonIdForLinuxKey(linuxCode) >= 0;
    }

    // button id carried by an android keycode, -1 for other keys
    public static int buttonIdForAndroidKey(int keyCode) {
        if (keyCode == ANDROID_KEY_POWER) return POWER_BUTTON_ID;
        if (keyCode >= ANDROID_KEY_BUTTON_BASE && keyCode < ANDROID_KEY_BUTTON_BASE + MAX_BUTTONS) {
            return keyCode - ANDROID_KEY_BUTTON_BASE;
        }
        return -1;
    }

    // button id carried by a raw linux key code, -1 for other keys
    public static int buttonIdForLinuxKey(int linuxCode) {
        if (linuxCode == LINUX_KEY_POWER) return POWER_BUTTON_ID;
        if (linuxCode >= LINUX_KEY_BUTTON_BASE && linuxCode < LINUX_KEY_BUTTON_BASE + MAX_BUTTONS) {
            return linuxCode - LINUX_KEY_BUTTON_BASE;
        }
        return -1;
    }

    // linux code behind a `getevent -l` key name, -1 when its not one of ours
    // EXACT match on purpose. a startsWith or a contains would let KEY_F1 swallow KEY_F10, KEY_F11 and KEY_F12
    public static int linuxCodeForKeyName(String keyName) {
        if ("KEY_F10".equals(keyName)) return LINUX_KEY_POWER;
        for (int i = 0; i < MAX_BUTTONS; i++) {
            if (("KEY_F" + (i + 1)).equals(keyName)) return LINUX_KEY_BUTTON_BASE + i;
        }
        return -1;
    }

    public void onDestroy() {
        ioExecutor.shutdown();
    }

    private void submitEdge(int slot, boolean down) {
        // timestamp at ingestion, the rest is serialized on the main looper so the native thread and the ui thread
        // cant race the duplicate check
        final long nowMs = SystemClock.elapsedRealtime();
        mainHandler.post(() -> {
            if (!accept(slot, down, nowMs)) {
                Log.d(TAG, "button " + buttonIdForSlot(slot) + (down ? " down" : " up") + " dropped as duplicate");
                return;
            }
            int buttonId = buttonIdForSlot(slot);
            if (down) {
                pressDetectors[slot].onPressDown();
            } else {
                // relay first then the detector, the order MainActivity used
                if (buttonId != POWER_BUTTON_ID) toggleMappedRelay(buttonId);
                pressDetectors[slot].onPressUp();
            }
        });
    }

    // buttons alternate down and up, so repeating the edge we are already on inside the window is the second delivery of one press
    // a real double click still gets through since its second down follows an up. the same edge AFTER the window is taken as real
    // so one lost edge cant wedge the button for good
    private boolean accept(int slot, boolean down, long nowMs) {
        Boolean last = lastDown[slot];
        if (last != null && last == down && nowMs - lastEdgeAtMs[slot] < DUPLICATE_WINDOW_MS) return false;
        lastDown[slot] = down;
        lastEdgeAtMs[slot] = nowMs;
        return true;
    }

    private void onPressTypeDetected(int buttonId, String pressType) {
        Log.d(TAG, "button " + buttonId + " press type detected: " + pressType);

        ioExecutor.execute(() -> {
            if (mMQTTServer != null) mMQTTServer.publishButton(buttonId, pressType);
        });
        if (mShellyElevateJavascriptInterface != null) {
            mShellyElevateJavascriptInterface.onButtonPressed(buttonId);
        }

        if (buttonId == POWER_BUTTON_ID && BUTTON_PRESS_TYPE_LONG.equals(pressType)) {
            rebootIfEnabled();
        }
    }

    private void rebootIfEnabled() {
        if (mSharedPreferences == null || !mSharedPreferences.getBoolean(SP_POWER_BUTTON_AUTO_REBOOT, true)) return;
        ioExecutor.execute(() -> {
            try {
                Runtime.getRuntime().exec("reboot");
            } catch (IOException e) {
                Log.e(TAG, "Error rebooting:", e);
            }
        });
    }

    // read live so remapping a button takes effect without a restart
    private void toggleMappedRelay(int buttonId) {
        ioExecutor.execute(() -> {
            if (mSharedPreferences == null || !mSharedPreferences.getBoolean(SP_BUTTON_RELAY_ENABLED, false)) return;
            int relayIndex = mSharedPreferences.getInt(String.format(Locale.US, SP_BUTTON_RELAY_MAP_FORMAT, buttonId), -1);
            if (relayIndex < 0 || relayIndex >= device.relays) return;
            DeviceHelper deviceHelper = mDeviceHelper;
            if (deviceHelper == null) return;
            deviceHelper.setRelay(relayIndex, !deviceHelper.getRelay(relayIndex));
        });
    }

    private int buttonIdForSlot(int slot) {
        return slot == powerSlot ? POWER_BUTTON_ID : slot;
    }

    private int slotForButtonId(int buttonId) {
        if (buttonId < 0) return -1;
        if (buttonId == POWER_BUTTON_ID) return powerSlot;
        return buttonId < device.buttons ? buttonId : -1;
    }
}
