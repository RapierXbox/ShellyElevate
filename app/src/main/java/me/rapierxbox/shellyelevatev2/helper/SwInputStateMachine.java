package me.rapierxbox.shellyelevatev2.helper;

import me.rapierxbox.shellyelevatev2.Constants;

// Mode logic for the SW terminal input, mirroring the stock firmware input
// types: detached (report only), button (momentary, press toggles), switch-edge
// (every level change toggles) and switch-follow (relay tracks the contact
// position). onEdge() consumes contact LEVELS: the hardware reports each
// transition as a short key pulse whose keycode encodes the direction
// (141/KEY_F11 = rising, 142/KEY_F12 = falling; verified on the X1i), decoded
// by the static keymap helpers below. Pure java so it stays unit testable;
// all android wiring lives in SwInputHandler.
public class SwInputStateMachine {
    // the native monitor and the activity KeyEvent path both deliver the same
    // physical pulse a few ms apart; a same-level event inside this window is
    // that second delivery. A same-level pulse AFTER the window is treated as a
    // real repeat (possible in multi-way taster circuits) and processed again.
    // Opposite-level events are always real - the direction is keycode-coded
    // and the OEM layer already debounces the contacts.
    public static final long DUPLICATE_WINDOW_MS = 300;

    // android keycodes and raw linux codes carrying the sw terminal edges
    public static final int ANDROID_KEY_SW_RISING = 141;  // KEYCODE_F11
    public static final int ANDROID_KEY_SW_FALLING = 142; // KEYCODE_F12
    public static final int LINUX_KEY_SW_RISING = 87;     // KEY_F11
    public static final int LINUX_KEY_SW_FALLING = 88;    // KEY_F12

    /** Contact level carried by an android sw keycode, or null for other keys. */
    public static Boolean levelForAndroidKey(int keyCode) {
        if (keyCode == ANDROID_KEY_SW_RISING) return Boolean.TRUE;
        if (keyCode == ANDROID_KEY_SW_FALLING) return Boolean.FALSE;
        return null;
    }

    /** Contact level carried by a raw linux sw key code, or null for other keys. */
    public static Boolean levelForLinuxKey(int linuxCode) {
        if (linuxCode == LINUX_KEY_SW_RISING) return Boolean.TRUE;
        if (linuxCode == LINUX_KEY_SW_FALLING) return Boolean.FALSE;
        return null;
    }

    public interface Actions {
        void publishLevel(int input, boolean pressed);
        void toggleRelay(int input);
        void setRelayLevel(int input, boolean on);
        void fireJsEvent(int input);
        void buttonEdge(int input, boolean down);
    }

    private final Actions actions;
    private final int[] modes;
    private final boolean[] inverts;
    private final Boolean[] levels;
    private final long[] lastEdgeAtMs;

    public SwInputStateMachine(int inputCount, Actions actions) {
        this.actions = actions;
        modes = new int[inputCount];
        inverts = new boolean[inputCount];
        levels = new Boolean[inputCount];
        lastEdgeAtMs = new long[inputCount];
        for (int i = 0; i < inputCount; i++) modes[i] = Constants.SW_INPUT_MODE_BUTTON;
    }

    public int getInputCount() {
        return modes.length;
    }

    public synchronized void configure(int input, int mode, boolean invert) {
        if (!validInput(input)) return;
        modes[input] = mode;
        inverts[input] = invert;
    }

    /** Clears the tracked level so the next event is never dropped as a duplicate. */
    public synchronized void reset(int input) {
        if (!validInput(input)) return;
        levels[input] = null;
        lastEdgeAtMs[input] = 0;
    }

    /** Logical contact level, null until the first accepted event. */
    public synchronized Boolean getLevel(int input) {
        return validInput(input) ? levels[input] : null;
    }

    public synchronized int getMode(int input) {
        return validInput(input) ? modes[input] : Constants.SW_INPUT_MODE_DETACHED;
    }

    /**
     * Feed a contact level reported by the hardware. Returns true when the
     * event was accepted; the double delivery of one pulse is dropped.
     */
    public synchronized boolean onEdge(int input, boolean rawLevel, long nowMs) {
        if (!validInput(input)) return false;
        boolean logical = rawLevel ^ inverts[input];
        Boolean level = levels[input];
        if (level != null && level == logical && nowMs - lastEdgeAtMs[input] < DUPLICATE_WINDOW_MS) {
            return false;
        }
        levels[input] = logical;
        lastEdgeAtMs[input] = nowMs;

        actions.publishLevel(input, logical);
        switch (modes[input]) {
            case Constants.SW_INPUT_MODE_BUTTON:
                actions.buttonEdge(input, logical);
                if (logical) {
                    actions.toggleRelay(input);
                    actions.fireJsEvent(input);
                }
                break;
            case Constants.SW_INPUT_MODE_SWITCH_EDGE:
                actions.toggleRelay(input);
                actions.fireJsEvent(input);
                break;
            case Constants.SW_INPUT_MODE_SWITCH_FOLLOW:
                actions.setRelayLevel(input, logical);
                actions.fireJsEvent(input);
                break;
            default:
                // detached: report only; js event once per press like before
                if (logical) actions.fireJsEvent(input);
                break;
        }
        return true;
    }

    private boolean validInput(int input) {
        return input >= 0 && input < modes.length;
    }
}
