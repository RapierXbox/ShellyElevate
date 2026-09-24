package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_SW_INPUT_INVERT_FORMAT;
import static me.rapierxbox.shellyelevatev2.Constants.SP_SW_INPUT_MODE_FORMAT;
import static me.rapierxbox.shellyelevatev2.Constants.SP_SW_INPUT_RELAY_MAP_FORMAT;
import static me.rapierxbox.shellyelevatev2.Constants.SW_INPUT_MODE_BUTTON;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mShellyElevateJavascriptInterface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.rapierxbox.shellyelevatev2.DeviceModel;

// single owner of the sw terminal input
// two hardware reporting schemes exist for the one sw terminal (every
// supported model declares a single input)
//   edge coded  - each contact transition is a short down+up key pulse whose
//                 keycode carries the direction: 141/key_f11 rising
//                 142/key_f12 falling (verified on the x1i)
//   level coded - one keycode (8/key_1) held down while the contact is closed
//                 and released when it opens (verified on the stargate whose
//                 gpio_keys node declares that single code)
// both are decoded here so a model reporting either one works without a
// per-model flag. edges arrive via both the focused activity keyevents and
// the native input monitor. SwInputStateMachine drops the duplicate delivery
// and applies the configured input mode
public class SwInputHandler {
    private static final String TAG = "SwInputHandler";

    // all current hardware has exactly one sw terminal
    private static final int SW_INPUT_INDEX = 0;

    // js interface reports sw inputs as button ids 100+i (pre-existing contract)
    private static final int JS_BUTTON_ID_BASE = 100;

    private final DeviceModel device;
    private final SwInputStateMachine stateMachine;
    private final ButtonPressDetector[] pressDetectors;
    private final int[] configuredModes;
    private final boolean[] configuredInverts;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // relay sysfs writes and mqtt publishes stay off the main thread; a single
    // thread keeps press/release ordering intact
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver settingsReceiver;
    // memo for the level key source check below; key events are main thread only
    private int levelKeySourceId = Integer.MIN_VALUE;
    private boolean levelKeySourceIsTerminal;

    public SwInputHandler() {
        device = DeviceModel.getReportedDevice();
        stateMachine = new SwInputStateMachine(device.inputs, new StateMachineActions());
        pressDetectors = new ButtonPressDetector[device.inputs];
        configuredModes = new int[device.inputs];
        configuredInverts = new boolean[device.inputs];
        for (int i = 0; i < device.inputs; i++) {
            pressDetectors[i] = new ButtonPressDetector(JS_BUTTON_ID_BASE + i, (buttonId, pressType) ->
                    ioExecutor.execute(() -> {
                        if (mMQTTServer != null && mMQTTServer.shouldSend()) {
                            mMQTTServer.publishButton(buttonId, pressType);
                        }
                    }));
        }

        seedDefaults();
        for (int i = 0; i < device.inputs; i++) {
            configuredModes[i] = readMode(i);
            configuredInverts[i] = readInvert(i);
            stateMachine.configure(i, configuredModes[i], configuredInverts[i]);
        }

        settingsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                applyConfigChanges();
            }
        };
        LocalBroadcastManager.getInstance(mApplicationContext)
                .registerReceiver(settingsReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));
    }

    // handles sw input keycodes returns false for anything else so callers can pass those on
    public boolean onKeyEvent(KeyEvent event) {
        Boolean swLevel = SwInputStateMachine.levelForAndroidKey(event.getKeyCode());
        if (swLevel != null) {
            // the level is coded in the keycode so only the pulses action_down carries
            // a transition. the pulse tail (up) and auto repeats are swallowed same as
            // everything on models without an input so sw keycodes never leak to the webview
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                    && SW_INPUT_INDEX < device.inputs) {
                submitEdge(SW_INPUT_INDEX, swLevel);
            }
            return true;
        }
        if (isSwTerminalLevelKey(event)) {
            // the key state is the contact: down closes it up opens it. android auto
            // repeats the down while held which changes nothing and is swallowed along
            // with the rest so the code never reaches the webview
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                submitEdge(SW_INPUT_INDEX, true);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                submitEdge(SW_INPUT_INDEX, false);
            }
            return true;
        }
        return false;
    }

    // intake for the native input monitor action: 0=up 1=down 2=repeat
    public void onNativeKey(int linuxCode, int action) {
        if (SW_INPUT_INDEX >= device.inputs) return;
        Boolean swLevel = SwInputStateMachine.levelForLinuxKey(linuxCode);
        if (swLevel != null) {
            // only the pulses down event carries the transition (see onKeyEvent)
            if (action == 1) submitEdge(SW_INPUT_INDEX, swLevel);
            return;
        }
        // level coded: down closes the contact up opens it repeat (2) changes nothing
        if (SwInputStateMachine.isLinuxSwLevelKey(linuxCode)) {
            if (action == 1) submitEdge(SW_INPUT_INDEX, true);
            else if (action == 0) submitEdge(SW_INPUT_INDEX, false);
        }
    }

    // true while the linux code is a sw terminal key the handler consumes
    public static boolean isNativeSwInputCode(int linuxCode) {
        return SwInputStateMachine.levelForLinuxKey(linuxCode) != null
                || SwInputStateMachine.isLinuxSwLevelKey(linuxCode);
    }

    // unlike the f11/f12 pulses the level code (keycode_1) is an ordinary digit
    // on a real keyboard so it is only claimed when it can actually be the
    // terminal: the model has an input and the event comes off a device that
    // cannot type letters. gpio_keys declares the one sw code and nothing else
    // a usb or bluetooth keyboard keeps its digit and passes it on to the webview
    private boolean isSwTerminalLevelKey(KeyEvent event) {
        if (!SwInputStateMachine.isAndroidSwLevelKey(event.getKeyCode())) return false;
        if (SW_INPUT_INDEX >= device.inputs) return false;
        int sourceId = event.getDeviceId();
        if (sourceId != levelKeySourceId) {
            levelKeySourceId = sourceId;
            InputDevice source = event.getDevice();
            // a synthetic event has no device; taken as the terminal like before
            levelKeySourceIsTerminal = source == null || !source.hasKeys(KeyEvent.KEYCODE_Q)[0];
            Log.i(TAG, "sw level key from input device " + sourceId + " ("
                    + (source != null ? source.getName() : "none") + "): "
                    + (levelKeySourceIsTerminal ? "treated as sw terminal" : "keyboard, passed on"));
        }
        return levelKeySourceIsTerminal;
    }

    // logical contact level of input i null until the first edge after start
    public Boolean getLevel(int input) {
        return stateMachine.getLevel(input);
    }

    public int getMode(int input) {
        return stateMachine.getMode(input);
    }

    private void submitEdge(int input, boolean level) {
        // timestamp at ingestion; processing is serialized on the main looper so
        // native-thread and ui-thread deliveries cannot race the state machine
        final long nowMs = SystemClock.elapsedRealtime();
        mainHandler.post(() -> {
            boolean accepted = stateMachine.onEdge(input, level, nowMs);
            Log.d(TAG, "sw input " + input + (level ? " rise" : " fall") + (accepted ? " accepted" : " dropped"));
        });
    }

    private void applyConfigChanges() {
        for (int i = 0; i < device.inputs; i++) {
            int mode = readMode(i);
            boolean invert = readInvert(i);
            if (mode != configuredModes[i] || invert != configuredInverts[i]) {
                configuredModes[i] = mode;
                configuredInverts[i] = invert;
                stateMachine.configure(i, mode, invert);
                // a stale mid-press level must not suppress the next real edge
                stateMachine.reset(i);
                Log.i(TAG, "sw input " + i + " reconfigured: mode=" + mode + " invert=" + invert);
            }
        }
    }

    // seed the keys so get /settings exposes them before the first ui save
    private void seedDefaults() {
        SharedPreferences.Editor editor = null;
        for (int i = 0; i < device.inputs; i++) {
            if (!mSharedPreferences.contains(formatKey(SP_SW_INPUT_MODE_FORMAT, i))) {
                if (editor == null) editor = mSharedPreferences.edit();
                editor.putInt(formatKey(SP_SW_INPUT_MODE_FORMAT, i), SW_INPUT_MODE_BUTTON);
            }
            if (!mSharedPreferences.contains(formatKey(SP_SW_INPUT_RELAY_MAP_FORMAT, i))) {
                if (editor == null) editor = mSharedPreferences.edit();
                editor.putInt(formatKey(SP_SW_INPUT_RELAY_MAP_FORMAT, i), 0);
            }
            if (!mSharedPreferences.contains(formatKey(SP_SW_INPUT_INVERT_FORMAT, i))) {
                if (editor == null) editor = mSharedPreferences.edit();
                editor.putBoolean(formatKey(SP_SW_INPUT_INVERT_FORMAT, i), false);
            }
        }
        if (editor != null) editor.apply();
    }

    private int readMode(int input) {
        return mSharedPreferences.getInt(formatKey(SP_SW_INPUT_MODE_FORMAT, input), SW_INPUT_MODE_BUTTON);
    }

    private boolean readInvert(int input) {
        return mSharedPreferences.getBoolean(formatKey(SP_SW_INPUT_INVERT_FORMAT, input), false);
    }

    private static String formatKey(String format, int input) {
        return String.format(Locale.US, format, input);
    }

    public void onDestroy() {
        LocalBroadcastManager.getInstance(mApplicationContext).unregisterReceiver(settingsReceiver);
        ioExecutor.shutdown();
    }

    private class StateMachineActions implements SwInputStateMachine.Actions {
        @Override
        public void publishLevel(int input, boolean pressed) {
            ioExecutor.execute(() -> {
                if (mMQTTServer != null && mMQTTServer.shouldSend()) {
                    mMQTTServer.publishSwitch(input, pressed);
                }
            });
        }

        @Override
        public void toggleRelay(int input) {
            ioExecutor.execute(() -> {
                DeviceHelper deviceHelper = mDeviceHelper;
                int relayIndex = relayIndexFor(input);
                if (deviceHelper == null || relayIndex < 0) return;
                deviceHelper.setRelay(relayIndex, !deviceHelper.getRelay(relayIndex));
            });
        }

        @Override
        public void setRelayLevel(int input, boolean on) {
            ioExecutor.execute(() -> {
                DeviceHelper deviceHelper = mDeviceHelper;
                int relayIndex = relayIndexFor(input);
                if (deviceHelper == null || relayIndex < 0) return;
                deviceHelper.setRelay(relayIndex, on);
            });
        }

        @Override
        public void fireJsEvent(int input) {
            if (mShellyElevateJavascriptInterface != null) {
                mShellyElevateJavascriptInterface.onButtonPressed(JS_BUTTON_ID_BASE + input);
            }
        }

        @Override
        public void buttonEdge(int input, boolean down) {
            if (down) pressDetectors[input].onPressDown();
            else pressDetectors[input].onPressUp();
        }

        // read live so relay remapping applies without a reconfigure round trip
        private int relayIndexFor(int input) {
            int relayIndex = mSharedPreferences.getInt(formatKey(SP_SW_INPUT_RELAY_MAP_FORMAT, input), 0);
            if (relayIndex < 0 || relayIndex >= device.relays) return -1;
            return relayIndex;
        }
    }
}
