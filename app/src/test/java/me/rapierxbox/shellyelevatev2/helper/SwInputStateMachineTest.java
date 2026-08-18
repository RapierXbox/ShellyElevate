package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.Constants.SW_INPUT_MODE_BUTTON;
import static me.rapierxbox.shellyelevatev2.Constants.SW_INPUT_MODE_DETACHED;
import static me.rapierxbox.shellyelevatev2.Constants.SW_INPUT_MODE_SWITCH_EDGE;
import static me.rapierxbox.shellyelevatev2.Constants.SW_INPUT_MODE_SWITCH_FOLLOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SwInputStateMachineTest {

    private static class RecordingActions implements SwInputStateMachine.Actions {
        final List<String> events = new ArrayList<>();

        @Override public void publishLevel(int input, boolean pressed) { events.add("level:" + input + ":" + pressed); }
        @Override public void toggleRelay(int input) { events.add("toggle:" + input); }
        @Override public void setRelayLevel(int input, boolean on) { events.add("set:" + input + ":" + on); }
        @Override public void fireJsEvent(int input) { events.add("js:" + input); }
        @Override public void buttonEdge(int input, boolean down) { events.add("btn:" + input + ":" + (down ? "down" : "up")); }

        long count(String prefix) {
            return events.stream().filter(e -> e.startsWith(prefix)).count();
        }
    }

    private RecordingActions actions;
    private SwInputStateMachine machine;

    @Before
    public void setUp() {
        actions = new RecordingActions();
        machine = new SwInputStateMachine(2, actions);
    }

    // --- keymap: 141/KEY_F11 = rising edge, 142/KEY_F12 = falling edge ---

    @Test
    public void androidKeymapDecodesEdgeDirection() {
        assertEquals(Boolean.TRUE, SwInputStateMachine.levelForAndroidKey(141));
        assertEquals(Boolean.FALSE, SwInputStateMachine.levelForAndroidKey(142));
        assertNull(SwInputStateMachine.levelForAndroidKey(131));
        assertNull(SwInputStateMachine.levelForAndroidKey(140));
    }

    @Test
    public void linuxKeymapDecodesEdgeDirection() {
        assertEquals(Boolean.TRUE, SwInputStateMachine.levelForLinuxKey(87));
        assertEquals(Boolean.FALSE, SwInputStateMachine.levelForLinuxKey(88));
        assertNull(SwInputStateMachine.levelForLinuxKey(63));
        assertNull(SwInputStateMachine.levelForLinuxKey(64));
    }

    // --- dedup: only same-level events inside the duplicate window are dropped ---

    @Test
    public void doubleDeliveryOfOnePulseDropped() {
        assertTrue(machine.onEdge(0, true, 0));
        // the second source delivers the same pulse a few ms later
        assertFalse(machine.onEdge(0, true, 12));
        assertEquals(1, actions.count("toggle:0"));
        assertEquals(1, actions.count("level:0:"));
    }

    @Test
    public void oppositeLevelAlwaysAcceptedEvenFast() {
        machine.configure(0, SW_INPUT_MODE_SWITCH_EDGE, false);
        assertTrue(machine.onEdge(0, true, 0));
        // a genuine opposite transition right after must not be eaten; the
        // direction is keycode-coded, so it cannot be delivery noise
        assertTrue(machine.onEdge(0, false, 5));
        assertEquals(2, actions.count("toggle:0"));
        assertEquals(Boolean.FALSE, machine.getLevel(0));
    }

    @Test
    public void sameLevelRepeatAfterWindowAcceptedAgain() {
        machine.configure(0, SW_INPUT_MODE_SWITCH_EDGE, false);
        assertTrue(machine.onEdge(0, true, 0));
        // a repeated same-direction pulse after the window is a real press
        // (possible in multi-way taster circuits) and must toggle again
        assertTrue(machine.onEdge(0, true, SwInputStateMachine.DUPLICATE_WINDOW_MS + 50));
        assertEquals(2, actions.count("toggle:0"));
    }

    // --- mode behavior on correct levels ---

    @Test
    public void detachedPublishesLevelsOnly() {
        machine.configure(0, SW_INPUT_MODE_DETACHED, false);
        assertTrue(machine.onEdge(0, true, 0));
        assertTrue(machine.onEdge(0, false, 1000));
        assertEquals(List.of("level:0:true", "js:0", "level:0:false"), actions.events);
        assertEquals(0, actions.count("toggle:"));
        assertEquals(0, actions.count("set:"));
        assertEquals(0, actions.count("btn:"));
    }

    @Test
    public void buttonTogglesOnRisingEdgeOnly() {
        // button is the default mode
        assertTrue(machine.onEdge(0, true, 0));
        assertEquals(List.of("level:0:true", "btn:0:down", "toggle:0", "js:0"), actions.events);

        actions.events.clear();
        assertTrue(machine.onEdge(0, false, 1000));
        assertEquals(List.of("level:0:false", "btn:0:up"), actions.events);
    }

    @Test
    public void switchEdgeTogglesOnEveryLevelChange() {
        machine.configure(0, SW_INPUT_MODE_SWITCH_EDGE, false);
        assertTrue(machine.onEdge(0, true, 0));
        assertTrue(machine.onEdge(0, false, 1000));
        assertTrue(machine.onEdge(0, true, 2000));
        assertEquals(3, actions.count("toggle:0"));
        assertEquals(3, actions.count("js:0"));
        assertTrue(actions.events.contains("level:0:true"));
        assertTrue(actions.events.contains("level:0:false"));
    }

    @Test
    public void switchFollowSetsRelayToLevel() {
        machine.configure(0, SW_INPUT_MODE_SWITCH_FOLLOW, false);
        assertTrue(machine.onEdge(0, true, 0));
        assertTrue(machine.onEdge(0, false, 1000));
        assertEquals(List.of("level:0:true", "set:0:true", "js:0", "level:0:false", "set:0:false", "js:0"), actions.events);
        assertEquals(0, actions.count("toggle:"));
    }

    @Test
    public void invertFlipsLogicalLevel() {
        machine.configure(0, SW_INPUT_MODE_SWITCH_FOLLOW, true);
        assertTrue(machine.onEdge(0, true, 0));
        assertEquals(List.of("level:0:false", "set:0:false", "js:0"), actions.events);
        assertEquals(Boolean.FALSE, machine.getLevel(0));
    }

    @Test
    public void firstEventAlwaysAcceptedEvenFalling() {
        assertNull(machine.getLevel(0));
        assertTrue(machine.onEdge(0, false, 0));
        assertEquals(List.of("level:0:false", "btn:0:up"), actions.events);
        assertEquals(0, actions.count("toggle:"));
    }

    @Test
    public void modeChangeWithResetAcceptsNextEvent() {
        assertTrue(machine.onEdge(0, true, 0));
        machine.configure(0, SW_INPUT_MODE_SWITCH_EDGE, false);
        machine.reset(0);
        // same level again right after a reset must not be treated as duplicate
        assertTrue(machine.onEdge(0, true, 50));
        assertEquals(SW_INPUT_MODE_SWITCH_EDGE, machine.getMode(0));
    }

    @Test
    public void inputsAreIndependent() {
        assertTrue(machine.onEdge(0, true, 0));
        assertTrue(machine.onEdge(1, true, 5));
        assertEquals(1, actions.count("toggle:0"));
        assertEquals(1, actions.count("toggle:1"));
    }

    @Test
    public void detachedFiresJsOncePerPress() {
        machine.configure(0, SW_INPUT_MODE_DETACHED, false);
        assertTrue(machine.onEdge(0, false, 0));
        assertTrue(machine.onEdge(0, true, 1000));
        assertTrue(machine.onEdge(0, false, 2000));
        assertEquals(1, actions.count("js:0"));
    }

    @Test
    public void invalidInputIndexRejected() {
        assertFalse(machine.onEdge(5, true, 0));
        assertFalse(machine.onEdge(-1, true, 0));
        assertNull(machine.getLevel(5));
        assertEquals(0, actions.events.size());
    }

    @Test
    public void buttonModeIsDefault() {
        assertEquals(SW_INPUT_MODE_BUTTON, machine.getMode(0));
    }

    // --- regression: the exact hardware trace from the X1i field test ---
    // Two tasters in a multi-way (wechsel/kreuz) circuit: each press flips the
    // line once, alternating direction. Every pulse is delivered twice (native
    // monitor + activity KeyEvent) ~10ms apart. Five presses were observed:
    // rise, fall, rise, fall, rise. The old code swallowed the falling pulses,
    // toggling only every second press.

    private void replayFieldTrace() {
        long[] pressAt = {0, 1030, 1960, 2990, 3820};
        boolean[] lvl = {true, false, true, false, true};
        for (int i = 0; i < pressAt.length; i++) {
            machine.onEdge(0, lvl[i], pressAt[i]);       // native delivery
            machine.onEdge(0, lvl[i], pressAt[i] + 10);  // keyevent delivery of the same pulse
        }
    }

    @Test
    public void fieldTraceEdgeModeTogglesOnEveryPress() {
        machine.configure(0, SW_INPUT_MODE_SWITCH_EDGE, false);
        replayFieldTrace();
        assertEquals(5, actions.count("toggle:0"));
        assertEquals(5, actions.count("level:0:"));
    }

    @Test
    public void fieldTraceButtonModeTogglesOnRisingPressesOnly() {
        replayFieldTrace();
        assertEquals(3, actions.count("toggle:0"));
    }

    @Test
    public void fieldTraceFollowModeTracksLevel() {
        machine.configure(0, SW_INPUT_MODE_SWITCH_FOLLOW, false);
        replayFieldTrace();
        assertEquals(List.of("set:0:true", "set:0:false", "set:0:true", "set:0:false", "set:0:true"),
                actions.events.stream().filter(e -> e.startsWith("set:")).toList());
    }
}
