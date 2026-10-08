package me.rapierxbox.shellyelevatev2.settings;

import static me.rapierxbox.shellyelevatev2.settings.SettingDef.Condition.eq;
import static me.rapierxbox.shellyelevatev2.settings.SettingDef.Condition.in;
import static me.rapierxbox.shellyelevatev2.settings.SettingDef.Condition.ne;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.rapierxbox.shellyelevatev2.Constants;

public class SettingVisibilityTest {

    private final Map<String, SettingDef> defs = new HashMap<>();
    private final Map<String, Object> values = new HashMap<>();
    private final Map<String, Object> caps = new HashMap<>();

    private SettingDef def(SettingDef.Builder builder) {
        SettingDef def = builder.build();
        defs.put(def.key, def);
        return def;
    }

    private static SettingDef.Builder bool(String key, boolean value) {
        return new SettingDef.Builder(key, SettingDef.TYPE_BOOL, value, "general", key);
    }

    private static SettingDef.Builder integer(String key, int value) {
        return new SettingDef.Builder(key, SettingDef.TYPE_INT, value, "general", key);
    }

    private static SettingDef.Builder string(String key, String value) {
        return new SettingDef.Builder(key, SettingDef.TYPE_STRING, value, "general", key);
    }

    private boolean visible(SettingDef def) {
        return SettingVisibility.visible(def, values::get, caps, defs::get);
    }

    private boolean visible(String key) {
        return SettingVisibility.visible(SettingsRegistry.get(key), values::get, caps);
    }

    @Test
    public void noRulesIsVisible() {
        assertTrue(visible(def(bool("a", false))));
    }

    @Test
    public void hiddenIsNeverVisible() {
        assertFalse(visible(def(bool("a", false).hidden())));
    }

    @Test
    public void eqNeAndInUseCurrentValueOrDefault() {
        def(string("mode", "web"));
        SettingDef onWeb = def(bool("url", false).visibleIf(eq("mode", "web")));
        SettingDef notOff = def(bool("previews", false).visibleIf(ne("mode", "off")));
        SettingDef clock = def(integer("level", 0).visibleIf(in("mode", "clock", "date")));

        // unset values fall back to the default
        assertTrue(visible(onWeb));
        assertTrue(visible(notOff));
        assertFalse(visible(clock));

        values.put("mode", "off");
        assertFalse(visible(onWeb));
        assertFalse(visible(notOff));
        assertFalse(visible(clock));

        values.put("mode", "date");
        assertFalse(visible(onWeb));
        assertTrue(visible(notOff));
        assertTrue(visible(clock));
    }

    @Test
    public void numbersCompareByValue() {
        def(integer("id", 0));
        SettingDef child = def(integer("level", 0).visibleIf(in("id", 1, 2)));
        values.put("id", 2L);
        assertTrue(visible(child));
        values.put("id", 1.0);
        assertTrue(visible(child));
        values.put("id", 3);
        assertFalse(visible(child));
    }

    @Test
    public void allConditionsMustHold() {
        def(bool("a", true));
        def(bool("b", true));
        SettingDef child = def(bool("c", false).visibleIf(eq("a", true), eq("b", true)));
        assertTrue(visible(child));
        values.put("b", false);
        assertFalse(visible(child));
    }

    @Test
    public void requiresTruthyAndMin() {
        SettingDef lux = def(bool("lux", false).requires("lux"));
        SettingDef second = def(bool("second", false).requires("buttons", 2).requires("relays", 1));

        assertFalse(visible(lux));
        caps.put("lux", false);
        assertFalse(visible(lux));
        caps.put("lux", true);
        assertTrue(visible(lux));

        caps.put("buttons", 1);
        caps.put("relays", 1);
        assertFalse(visible(second));
        caps.put("buttons", 4);
        assertTrue(visible(second));
        caps.put("relays", 0);
        assertFalse(visible(second));
        // min needs a number
        caps.put("relays", true);
        assertFalse(visible(second));
    }

    @Test
    public void truthyCapabilities() {
        SettingDef def = def(bool("a", false).requires("x"));
        caps.put("x", 0);
        assertFalse(visible(def));
        caps.put("x", 2);
        assertTrue(visible(def));
        caps.put("x", "");
        assertFalse(visible(def));
        caps.put("x", "yes");
        assertTrue(visible(def));
    }

    @Test
    public void hiddenParentHidesTheChild() {
        def(bool("api", true));
        def(bool("voice", false).visibleIf(eq("api", true)));
        def(bool("wake", true).visibleIf(eq("voice", true)));
        SettingDef model = def(string("model", "").visibleIf(eq("wake", true)));

        values.put("voice", true);
        assertTrue(visible(model));
        // the chain breaks at the top even though voice and wake still hold true
        values.put("api", false);
        assertFalse(visible(model));
    }

    @Test
    public void unavailableBoolParentCountsAsFalse() {
        def(bool("auto", true).requires("lux"));
        SettingDef fixed = def(integer("fixed", 255).visibleIf(eq("auto", false)));
        SettingDef min = def(integer("min", 48).visibleIf(eq("auto", true)));

        // the stored value is ignored while the parent is unavailable
        values.put("auto", true);
        assertTrue(visible(fixed));
        assertFalse(visible(min));

        caps.put("lux", true);
        assertFalse(visible(fixed));
        assertTrue(visible(min));
    }

    @Test
    public void unavailableOtherParentCountsAsDefault() {
        def(integer("mode", 1).requires("inputs", 1));
        SettingDef child = def(integer("relay", 0).visibleIf(ne("mode", 0)));
        values.put("mode", 0);
        assertTrue(visible(child));
    }

    @Test
    public void unknownParentHides() {
        assertFalse(visible(def(bool("a", false).visibleIf(eq("missing", true)))));
    }

    @Test
    public void cycleHides() {
        def(bool("a", true).visibleIf(eq("b", true)));
        def(bool("b", true).visibleIf(eq("a", true)));
        SettingDef self = def(bool("self", true).visibleIf(eq("self", true)));
        assertFalse(visible(defs.get("a")));
        assertFalse(visible(defs.get("b")));
        assertFalse(visible(self));
        // a child of the cycle is hidden too
        assertFalse(visible(def(bool("c", true).visibleIf(eq("a", true)))));
    }

    @Test
    public void diamondIsNoCycle() {
        def(bool("root", true));
        def(bool("left", true).visibleIf(eq("root", true)));
        def(bool("right", true).visibleIf(eq("root", true)));
        assertTrue(visible(def(bool("leaf", true).visibleIf(eq("left", true), eq("right", true)))));
    }

    // ---- registry rules ----

    @Test
    public void everyConditionReferencesAKnownSetting() {
        for (SettingDef def : SettingsRegistry.all()) {
            for (SettingDef.Condition condition : def.visibleIf) {
                assertNotNull(def.key + " references " + condition.key, SettingsRegistry.get(condition.key));
            }
        }
    }

    @Test
    public void registryHasNoCycle() {
        for (SettingDef def : SettingsRegistry.all()) {
            assertCycleFree(def, new HashSet<>());
        }
    }

    private void assertCycleFree(SettingDef def, Set<String> path) {
        assertTrue("cycle through " + def.key, path.add(def.key));
        for (SettingDef.Condition condition : def.visibleIf) {
            assertCycleFree(SettingsRegistry.get(condition.key), path);
        }
        path.remove(def.key);
    }

    @Test
    public void brightnessWithoutLightSensor() {
        caps.put("lux", false);
        assertFalse(visible(Constants.SP_AUTOMATIC_BRIGHTNESS));
        assertTrue(visible(Constants.SP_BRIGHTNESS));
        assertFalse(visible(Constants.SP_MIN_BRIGHTNESS));
        assertFalse(visible(Constants.SP_SCREEN_SAVER_MIN_BRIGHTNESS));

        caps.put("lux", true);
        assertTrue(visible(Constants.SP_AUTOMATIC_BRIGHTNESS));
        assertFalse(visible(Constants.SP_BRIGHTNESS));
        assertTrue(visible(Constants.SP_MIN_BRIGHTNESS));
    }

    @Test
    public void screensaverBrightnessOnlyForClocks() {
        caps.put("lux", true);
        for (int id : Arrays.asList(0, 3)) {
            values.put(Constants.SP_SCREEN_SAVER_ID, id);
            assertFalse(visible(Constants.SP_SCREEN_SAVER_MIN_BRIGHTNESS));
        }
        values.put(Constants.SP_SCREEN_SAVER_ID, 1);
        assertTrue(visible(Constants.SP_SCREEN_SAVER_MIN_BRIGHTNESS));
        values.put(Constants.SP_AUTOMATIC_BRIGHTNESS, false);
        assertFalse(visible(Constants.SP_SCREEN_SAVER_MIN_BRIGHTNESS));
    }

    @Test
    public void screensaverSettingsNeedScreensaver() {
        caps.put("lux", true);
        caps.put("proximity", true);
        values.put(Constants.SP_SCREEN_SAVER_ID, 1);
        List<String> children = Arrays.asList(Constants.SP_SCREEN_SAVER_DELAY, Constants.SP_SCREEN_SAVER_ID,
                Constants.SP_SCREEN_SAVER_MIN_BRIGHTNESS, Constants.SP_WAKE_ON_PROXIMITY,
                Constants.SP_PROXIMITY_KEEP_AWAKE_SECONDS, Constants.SP_TOUCH_TO_WAKE,
                Constants.SP_SLEEP_OPTIMIZATION_LEVEL);
        for (String key : children) assertTrue(key, visible(key));
        values.put(Constants.SP_SCREEN_SAVER_ENABLED, false);
        for (String key : children) assertFalse(key, visible(key));
    }

    @Test
    public void mqttSettingsNeedMqtt() {
        List<String> children = Arrays.asList(Constants.SP_MQTT_BROKER, Constants.SP_MQTT_CLIENTID,
                Constants.SP_PUBLISH_THERMAL_SENSORS);
        // unset falls back to the default which is off
        for (String key : children) assertFalse(key, visible(key));
        values.put(Constants.SP_MQTT_ENABLED, true);
        for (String key : children) assertTrue(key, visible(key));
        values.put(Constants.SP_MQTT_ENABLED, false);
        for (String key : children) assertFalse(key, visible(key));
    }

    @Test
    public void voiceNeedsMicrophoneAndIntegrationApi() {
        values.put(Constants.SP_HA_VOICE_ENABLED, true);
        assertFalse(visible(Constants.SP_HA_VOICE_ENABLED));
        assertFalse(visible(Constants.SP_VOICE_WAKE_SENSITIVITY));

        caps.put("microphone", true);
        assertTrue(visible(Constants.SP_HA_VOICE_ENABLED));
        assertTrue(visible(Constants.SP_VOICE_WAKE_SENSITIVITY));

        values.put(Constants.SP_VOICE_WAKE_ENABLED, false);
        assertFalse(visible(Constants.SP_VOICE_WAKE_SENSITIVITY));
        assertTrue(visible(Constants.SP_VOICE_WAKE_SOUND_ENABLED));

        values.put(Constants.SP_INTEGRATION_API_ENABLED, false);
        assertFalse(visible(Constants.SP_HA_VOICE_ENABLED));
        assertFalse(visible(Constants.SP_VOICE_WAKE_SOUND_ENABLED));
    }

    @Test
    public void buttonRowsFollowTheButtonCount() {
        caps.put("buttons", 2);
        caps.put("relays", 1);
        values.put(Constants.SP_BUTTON_RELAY_ENABLED, true);
        assertTrue(visible(String.format(Constants.SP_BUTTON_RELAY_MAP_FORMAT, 1)));
        assertFalse(visible(String.format(Constants.SP_BUTTON_RELAY_MAP_FORMAT, 2)));
        values.put(Constants.SP_BUTTON_RELAY_ENABLED, false);
        assertFalse(visible(String.format(Constants.SP_BUTTON_RELAY_MAP_FORMAT, 0)));
    }

    @Test
    public void moduleOptionsFollowTheModule() {
        values.put(Constants.SP_DISPLAY_MODULE, Constants.DISPLAY_MODULE_WEBVIEW);
        assertTrue(visible(Constants.SP_WEBVIEW_URL));
        assertFalse(visible("app.package"));
        values.put(Constants.SP_DISPLAY_MODULE, "app");
        assertFalse(visible(Constants.SP_WEBVIEW_URL));
        assertTrue(visible("app.component"));
        assertTrue(SettingsRegistry.get("app.component").readOnly);
    }

    @Test
    public void settingEverShownIsHidden() {
        assertTrue(SettingsRegistry.get(Constants.SP_SETTINGS_EVER_SHOWN).hidden);
        assertFalse(visible(Constants.SP_SETTINGS_EVER_SHOWN));
        assertEquals(Boolean.FALSE, SettingVisibility.fallback(SettingsRegistry.get(Constants.SP_SETTINGS_EVER_SHOWN)));
    }
}
