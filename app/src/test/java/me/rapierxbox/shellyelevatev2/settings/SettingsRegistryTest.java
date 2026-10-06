package me.rapierxbox.shellyelevatev2.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SettingsRegistryTest {

    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList(
            "general", "display", "screensaver", "inputs", "mqtt", "voice", "bluetooth", "media", "advanced", "deprecated"));

    @Test
    public void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (SettingDef def : SettingsRegistry.all()) {
            assertTrue("duplicate " + def.key, seen.add(def.key));
        }
    }

    @Test
    public void defaultsMatchTheirType() {
        for (SettingDef def : SettingsRegistry.all()) {
            assertNotNull(def.key + " default", def.defaultValue);
            switch (def.type) {
                case SettingDef.TYPE_BOOL:
                    assertTrue(def.key, def.defaultValue instanceof Boolean);
                    break;
                case SettingDef.TYPE_INT:
                    assertTrue(def.key, def.defaultValue instanceof Integer);
                    break;
                case SettingDef.TYPE_FLOAT:
                    assertTrue(def.key, def.defaultValue instanceof Double);
                    break;
                case SettingDef.TYPE_STRING:
                    assertTrue(def.key, def.defaultValue instanceof String);
                    break;
                case SettingDef.TYPE_STRING_LIST:
                    assertTrue(def.key, def.defaultValue instanceof List);
                    break;
                case SettingDef.TYPE_ENUM:
                    assertNotNull(def.key + " options", def.options);
                    boolean found = false;
                    for (SettingDef.Option option : def.options) found |= option.value.equals(def.defaultValue);
                    assertTrue(def.key + " default not an option", found);
                    break;
                default:
                    fail(def.key + " unknown type " + def.type);
            }
            assertTrue(def.key + " category", CATEGORIES.contains(def.category));
            assertNotNull(def.key + " label", def.label);
        }
    }

    @Test
    public void numericDefaultsAreInRange() {
        for (SettingDef def : SettingsRegistry.all()) {
            if (!(def.defaultValue instanceof Number)) continue;
            double d = ((Number) def.defaultValue).doubleValue();
            if (def.min != null) assertTrue(def.key + " below min", d >= def.min);
            if (def.max != null) assertTrue(def.key + " above max", d <= def.max);
            // the default must survive its own validation
            assertEquals(def.key, def.defaultValue, SettingsRegistry.coerce(def, def.defaultValue));
        }
    }

    @Test
    public void deprecatedKeysLiveInTheDeprecatedCategory() {
        for (SettingDef def : SettingsRegistry.all()) {
            assertEquals(def.key, def.deprecated, "deprecated".equals(def.category));
            if (def.replacedBy != null) assertNotNull(def.key + " replacement", SettingsRegistry.get(def.replacedBy));
        }
        assertEquals("bleScannerEnabled", SettingsRegistry.get("bluetoothProxyEnabled").replacedBy);
        assertEquals("haVoiceEnabled", SettingsRegistry.get("voiceAssistantEnabled").replacedBy);
        assertTrue(SettingsRegistry.get("voiceAssistantToken").secret);
        assertTrue(SettingsRegistry.get("mqttPassword").secret);
        assertTrue(SettingsRegistry.get("mqttDeviceId").perDevice);
    }

    @Test
    public void internalKeysStayOutOfTheSchema() {
        for (String key : SettingsRegistry.internalKeys()) {
            assertNull(key, SettingsRegistry.get(key));
        }
        assertTrue(SettingsRegistry.isInternal("homeAssistantIp"));
        assertFalse(SettingsRegistry.isInternal("httpServer"));
    }

    @Test
    public void coerceValidatesTypesAndRanges() {
        SettingDef brightness = SettingsRegistry.get("brightness");
        assertEquals(200, SettingsRegistry.coerce(brightness, 200.0));
        assertEquals(200, SettingsRegistry.coerce(brightness, "200"));
        rejects(brightness, 256);
        rejects(brightness, 1.5);
        rejects(brightness, true);

        SettingDef saver = SettingsRegistry.get("screenSaverId");
        assertEquals(2, SettingsRegistry.coerce(saver, 2));
        rejects(saver, 9);

        SettingDef gesture = SettingsRegistry.get("appSwitcherGesture");
        assertEquals("swipe_3_left", SettingsRegistry.coerce(gesture, "swipe_3_left"));
        rejects(gesture, "swipe_9_up");

        assertEquals(Boolean.TRUE, SettingsRegistry.coerce(SettingsRegistry.get("mqttEnabled"), true));
        rejects(SettingsRegistry.get("mqttEnabled"), 1);
        assertEquals(0.5, SettingsRegistry.coerce(SettingsRegistry.get("dynamicTempOffsetK"), 0.5));
    }

    @Test
    public void resolveFallsBackToTheDefault() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("mqttPort", "1884");
        stored.put("brightness", "not a number");
        stored.put("dynamicTempOffsetK", 0.25f);
        Map<String, Object> values = SettingsRegistry.resolvedValues(stored);
        assertEquals(1884, values.get("mqttPort"));
        assertEquals(255, values.get("brightness"));
        assertEquals(0.25, (Double) values.get("dynamicTempOffsetK"), 1e-6);
        assertEquals(Boolean.TRUE, values.get("httpServer"));
    }

    private static void rejects(SettingDef def, Object value) {
        try {
            SettingsRegistry.coerce(def, value);
            fail(def.key + " accepted " + value);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
