package me.rapierxbox.shellyelevatev2.settings;

import static me.rapierxbox.shellyelevatev2.settings.SettingDef.TYPE_BOOL;
import static me.rapierxbox.shellyelevatev2.settings.SettingDef.TYPE_ENUM;
import static me.rapierxbox.shellyelevatev2.settings.SettingDef.TYPE_FLOAT;
import static me.rapierxbox.shellyelevatev2.settings.SettingDef.TYPE_INT;
import static me.rapierxbox.shellyelevatev2.settings.SettingDef.TYPE_STRING;
import static me.rapierxbox.shellyelevatev2.settings.SettingDef.TYPE_STRING_LIST;
import static me.rapierxbox.shellyelevatev2.settings.SettingDef.Condition.eq;
import static me.rapierxbox.shellyelevatev2.settings.SettingDef.Condition.in;
import static me.rapierxbox.shellyelevatev2.settings.SettingDef.Condition.ne;
import static me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities.BLUETOOTH;
import static me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities.BUTTONS;
import static me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities.INPUTS;
import static me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities.LUX;
import static me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities.MICROPHONE;
import static me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities.POWER_BUTTON;
import static me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities.PROXIMITY;
import static me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities.RELAYS;
import static me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities.TEMPERATURE;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import me.rapierxbox.shellyelevatev2.Constants;
import me.rapierxbox.shellyelevatev2.display.DisplayModule;
import me.rapierxbox.shellyelevatev2.display.DisplayModuleRegistry;
import me.rapierxbox.shellyelevatev2.display.options.ModuleOption;

// every user setting the app keeps in the ShellyElevateV2 prefs with its type and default
// defaults match what the runtime reads so the v1 schema never lies about an unset key
@SuppressWarnings("deprecation")
public final class SettingsRegistry {
    private SettingsRegistry() {}

    public static final String CATEGORY_GENERAL = "general";
    public static final String CATEGORY_DISPLAY = "display";
    public static final String CATEGORY_SCREENSAVER = "screensaver";
    public static final String CATEGORY_INPUTS = "inputs";
    public static final String CATEGORY_MQTT = "mqtt";
    public static final String CATEGORY_VOICE = "voice";
    public static final String CATEGORY_BLUETOOTH = "bluetooth";
    public static final String CATEGORY_MEDIA = "media";
    public static final String CATEGORY_ADVANCED = "advanced";
    public static final String CATEGORY_DEPRECATED = "deprecated";

    // the most buttons inputs and relays any model has
    private static final int MAX_BUTTONS = 4;
    private static final int MAX_INPUTS = 1;
    private static final int MAX_RELAY_INDEX = 1;

    // bookkeeping the app writes for itself. never in the schema and never writable over the api
    private static final Set<String> INTERNAL_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            Constants.SP_DEPRECATED_HA_IP,
            Constants.SP_API_DEVICE_ID,
            Constants.SP_WEBVIEW_UPDATE_PROMPTED,
            Constants.SP_WEBVIEW_UPDATE_PENDING_FROM,
            Constants.SP_DIMMER_LAST_BRIGHTNESS,
            Constants.SP_DIMMER_LAST_STATE,
            "crashHandlerCount",
            "crashHandlerLastMs"
    )));

    private static final Map<String, SettingDef> DEFS = build();

    public static List<SettingDef> all() {
        return Collections.unmodifiableList(new ArrayList<>(DEFS.values()));
    }

    public static SettingDef get(String key) {
        return DEFS.get(key);
    }

    public static boolean isInternal(String key) {
        return INTERNAL_KEYS.contains(key);
    }

    public static Set<String> internalKeys() {
        return INTERNAL_KEYS;
    }

    // ---- table ----

    private static Map<String, SettingDef> build() {
        Map<String, SettingDef> m = new LinkedHashMap<>();

        // general and screen content
        add(m, new SettingDef.Builder(Constants.SP_DISPLAY_MODULE, TYPE_ENUM, DisplayModuleRegistry.DEFAULT_ID,
                CATEGORY_GENERAL, "Show on screen").options(displayModuleOptions())
                .description("What the display shows: the web dashboard or another app"));
        addDisplayModuleOptions(m);
        add(m, bool(Constants.SP_LITE_MODE, false, CATEGORY_GENERAL, "Lite mode")
                .description("Run without the dashboard as a background service only"));
        add(m, new SettingDef.Builder(Constants.SP_APP_SWITCHER_GESTURE, TYPE_ENUM, Constants.APP_SWITCHER_GESTURE_DEFAULT,
                CATEGORY_INPUTS, "App switcher gesture").options(switcherGestureOptions())
                .description("Multi finger swipe that opens the app switcher"));
        add(m, bool(Constants.SP_APP_SWITCHER_PREVIEWS, true, CATEGORY_GENERAL, "App switcher previews")
                .description("Show screenshots of the apps in the switcher")
                .visibleIf(ne(Constants.SP_APP_SWITCHER_GESTURE, Constants.APP_SWITCHER_GESTURE_OFF)));
        add(m, bool(Constants.SP_SETTINGS_EVER_SHOWN, false, CATEGORY_ADVANCED, "Settings shown once")
                .description("The first start already opened the settings").perDevice().hidden());

        // display
        add(m, bool(Constants.SP_AUTOMATIC_BRIGHTNESS, true, CATEGORY_DISPLAY, "Automatic brightness").requires(LUX));
        // without a light sensor automatic brightness counts as off so the fixed level applies
        add(m, integer(Constants.SP_BRIGHTNESS, 255, CATEGORY_DISPLAY, "Brightness").range(0, 255)
                .description("Fixed brightness while automatic brightness is off")
                .visibleIf(eq(Constants.SP_AUTOMATIC_BRIGHTNESS, false)));
        add(m, integer(Constants.SP_MIN_BRIGHTNESS, 48, CATEGORY_DISPLAY, "Minimum brightness").range(0, 255)
                .description("Lowest brightness automatic brightness goes down to")
                .visibleIf(eq(Constants.SP_AUTOMATIC_BRIGHTNESS, true)).requires(LUX));
        add(m, bool(Constants.SP_NIGHT_MODE_ENABLED, false, CATEGORY_DISPLAY, "Night mode")
                .description("Dims the screen below the backlight minimum"));

        // screensaver
        add(m, bool(Constants.SP_SCREEN_SAVER_ENABLED, true, CATEGORY_SCREENSAVER, "Screensaver"));
        SettingDef.Condition saverOn = eq(Constants.SP_SCREEN_SAVER_ENABLED, true);
        add(m, integer(Constants.SP_SCREEN_SAVER_DELAY, 45, CATEGORY_SCREENSAVER, "Screensaver delay")
                .range(5, 86400).unit("s").description("Seconds of inactivity before the screensaver starts")
                .visibleIf(saverOn));
        add(m, new SettingDef.Builder(Constants.SP_SCREEN_SAVER_ID, TYPE_ENUM, 0, CATEGORY_SCREENSAVER, "Screensaver type")
                .options(Arrays.asList(
                        new SettingDef.Option(0, "Screen off"),
                        new SettingDef.Option(1, "Clock"),
                        new SettingDef.Option(2, "Clock and date"),
                        new SettingDef.Option(3, "Always-on display")))
                .visibleIf(saverOn));
        // screen off and aod pin the backlight so only the clocks follow the light sensor
        add(m, integer(Constants.SP_SCREEN_SAVER_MIN_BRIGHTNESS, 48, CATEGORY_SCREENSAVER, "Screensaver brightness")
                .range(0, 255)
                .visibleIf(saverOn, in(Constants.SP_SCREEN_SAVER_ID, 1, 2), eq(Constants.SP_AUTOMATIC_BRIGHTNESS, true))
                .requires(LUX));
        add(m, bool(Constants.SP_WAKE_ON_PROXIMITY, true, CATEGORY_SCREENSAVER, "Wake on proximity")
                .visibleIf(saverOn).requires(PROXIMITY));
        // only pushes the idle deadline back so it needs the automatic screensaver
        add(m, integer(Constants.SP_PROXIMITY_KEEP_AWAKE_SECONDS, 30, CATEGORY_SCREENSAVER, "Keep awake after proximity")
                .range(0, 86400).unit("s")
                .visibleIf(saverOn, eq(Constants.SP_WAKE_ON_PROXIMITY, true))
                .requires(PROXIMITY));
        add(m, bool(Constants.SP_TOUCH_TO_WAKE, true, CATEGORY_SCREENSAVER, "Touch to wake").visibleIf(saverOn));
        add(m, new SettingDef.Builder(Constants.SP_SLEEP_OPTIMIZATION_LEVEL, TYPE_ENUM, Constants.SLEEP_OPT_NONE,
                CATEGORY_SCREENSAVER, "Sleep optimization").options(Arrays.asList(
                        new SettingDef.Option(Constants.SLEEP_OPT_NONE, "None"),
                        new SettingDef.Option(Constants.SLEEP_OPT_STANDARD, "Standard"),
                        new SettingDef.Option(Constants.SLEEP_OPT_AGGRESSIVE, "Aggressive")))
                .description("How much the app throttles itself while the screensaver runs")
                .visibleIf(saverOn));

        // inputs
        add(m, bool(Constants.SP_SWITCH_ON_SWIPE, true, CATEGORY_INPUTS, "Toggle relay on swipe").requires(RELAYS, 1));
        add(m, bool(Constants.SP_PUBLISH_SWIPE_EVENTS, true, CATEGORY_INPUTS, "Publish swipe events"));
        add(m, bool(Constants.SP_POWER_BUTTON_AUTO_REBOOT, true, CATEGORY_INPUTS, "Power button reboots")
                .requires(POWER_BUTTON));
        add(m, bool(Constants.SP_BUTTON_RELAY_ENABLED, false, CATEGORY_INPUTS, "Buttons switch relays")
                .requires(BUTTONS, 1).requires(RELAYS, 1));
        for (int i = 0; i < MAX_BUTTONS; i++) {
            add(m, integer(format(Constants.SP_BUTTON_RELAY_MAP_FORMAT, i), -1, CATEGORY_INPUTS,
                    "Button " + (i + 1) + " relay").range(-1, MAX_RELAY_INDEX)
                    .description("Relay the button toggles or -1 for none")
                    .visibleIf(eq(Constants.SP_BUTTON_RELAY_ENABLED, true))
                    .requires(BUTTONS, i + 1).requires(RELAYS, 1));
        }
        for (int i = 0; i < MAX_INPUTS; i++) {
            String n = String.valueOf(i + 1);
            String modeKey = format(Constants.SP_SW_INPUT_MODE_FORMAT, i);
            add(m, new SettingDef.Builder(modeKey, TYPE_ENUM,
                    Constants.SW_INPUT_MODE_BUTTON, CATEGORY_INPUTS, "Input " + n + " mode").options(Arrays.asList(
                            new SettingDef.Option(Constants.SW_INPUT_MODE_DETACHED, "Detached (report only)"),
                            new SettingDef.Option(Constants.SW_INPUT_MODE_BUTTON, "Button: press toggles relay"),
                            new SettingDef.Option(Constants.SW_INPUT_MODE_SWITCH_EDGE, "Switch: every flip toggles relay"),
                            new SettingDef.Option(Constants.SW_INPUT_MODE_SWITCH_FOLLOW, "Switch: relay follows position")))
                    .requires(INPUTS, i + 1));
            // a detached input only reports so it drives no relay
            add(m, integer(format(Constants.SP_SW_INPUT_RELAY_MAP_FORMAT, i), 0, CATEGORY_INPUTS, "Input " + n + " relay")
                    .range(-1, MAX_RELAY_INDEX).description("Relay the input drives or -1 for none")
                    .visibleIf(ne(modeKey, Constants.SW_INPUT_MODE_DETACHED))
                    .requires(INPUTS, i + 1).requires(RELAYS, 1));
            add(m, bool(format(Constants.SP_SW_INPUT_INVERT_FORMAT, i), false, CATEGORY_INPUTS, "Invert input " + n)
                    .requires(INPUTS, i + 1));
        }

        // mqtt
        add(m, bool(Constants.SP_MQTT_ENABLED, false, CATEGORY_MQTT, "MQTT"));
        SettingDef.Condition mqttOn = eq(Constants.SP_MQTT_ENABLED, true);
        add(m, string(Constants.SP_MQTT_BROKER, "", CATEGORY_MQTT, "MQTT broker").visibleIf(mqttOn));
        add(m, integer(Constants.SP_MQTT_PORT, 1883, CATEGORY_MQTT, "MQTT port").range(1, 65535).visibleIf(mqttOn));
        add(m, string(Constants.SP_MQTT_USERNAME, "", CATEGORY_MQTT, "MQTT username").visibleIf(mqttOn));
        add(m, string(Constants.SP_MQTT_PASSWORD, "", CATEGORY_MQTT, "MQTT password").secret().visibleIf(mqttOn));
        add(m, string(Constants.SP_MQTT_CLIENTID, "", CATEGORY_MQTT, "MQTT device id").perDevice().visibleIf(mqttOn)
                .description("Id used for mqtt topics. generated on first start"));
        add(m, bool(Constants.SP_MQTT_HA_DISCOVERY, true, CATEGORY_MQTT, "MQTT Home Assistant discovery").visibleIf(mqttOn));
        add(m, bool(Constants.SP_MQTT_RETAIN_STATE, true, CATEGORY_MQTT, "Retain MQTT state").visibleIf(mqttOn));

        // voice engine
        add(m, bool(Constants.SP_HA_VOICE_ENABLED, false, CATEGORY_VOICE, "Voice assistant via Home Assistant integration")
                .description("Stream the microphone to the paired Home Assistant after the wake word")
                .visibleIf(eq(Constants.SP_INTEGRATION_API_ENABLED, true)).requires(MICROPHONE));
        SettingDef.Condition voiceOn = eq(Constants.SP_HA_VOICE_ENABLED, true);
        SettingDef.Condition wakeOn = eq(Constants.SP_VOICE_WAKE_ENABLED, true);
        add(m, integer(Constants.SP_VOICE_ASSISTANT_MAX_RECORD_SECONDS, 10, CATEGORY_VOICE, "Max recording")
                .range(1, 60).unit("s").visibleIf(voiceOn));
        add(m, bool(Constants.SP_VOICE_ASSISTANT_MUTED, false, CATEGORY_VOICE, "Microphone muted").visibleIf(voiceOn));
        add(m, bool(Constants.SP_VOICE_WAKE_ENABLED, true, CATEGORY_VOICE, "Wake word").visibleIf(voiceOn));
        add(m, string(Constants.SP_VOICE_WAKE_MODEL_NAME, "", CATEGORY_VOICE, "Wake word model")
                .description("File name of the wake word model without extension").visibleIf(voiceOn, wakeOn));
        add(m, integer(Constants.SP_VOICE_WAKE_SENSITIVITY, 50, CATEGORY_VOICE, "Wake word sensitivity")
                .range(0, 100).description("50 is the published model cutoff").visibleIf(voiceOn, wakeOn));
        add(m, integer(Constants.SP_VOICE_WAKE_COOLDOWN_SEC, 5, CATEGORY_VOICE, "Wake word cooldown")
                .range(1, 10).unit("s").visibleIf(voiceOn, wakeOn));
        // a session the controller starts plays it too so it does not need the wake word
        add(m, bool(Constants.SP_VOICE_WAKE_SOUND_ENABLED, true, CATEGORY_VOICE, "Wake sound").visibleIf(voiceOn));
        add(m, bool(Constants.SP_VOICE_SCORE_BAR_ENABLED, false, CATEGORY_VOICE, "Show wake score bar")
                .visibleIf(voiceOn, wakeOn));
        add(m, bool(Constants.SP_VOICE_WAKE_EXPERIMENTAL_MODELS, false, CATEGORY_VOICE, "Experimental wake words")
                .visibleIf(voiceOn, wakeOn));

        // bluetooth
        add(m, bool(Constants.SP_BLE_SCANNER_ENABLED, false, CATEGORY_BLUETOOTH, "Bluetooth proxy via Home Assistant integration")
                .description("Forward Bluetooth advertisements to the paired Home Assistant")
                .visibleIf(eq(Constants.SP_INTEGRATION_API_ENABLED, true)).requires(BLUETOOTH));

        // media
        add(m, bool(Constants.SP_MEDIA_ENABLED, false, CATEGORY_MEDIA, "Media playback"));

        // advanced
        add(m, bool(Constants.SP_INTEGRATION_API_ENABLED, true, CATEGORY_ADVANCED, "Home Assistant integration API (port 8443)")
                .description("Pairing and the encrypted API the Shelly Elevate integration uses. Turning it off disconnects the integration"));
        add(m, bool(Constants.SP_HTTP_SERVER_ENABLED, true, CATEGORY_ADVANCED, "Legacy HTTP API (port 8080)")
                .description("Unauthenticated HTTP API of older app versions"));
        add(m, bool(Constants.SP_ADB_WIFI_ENABLED, false, CATEGORY_ADVANCED, "ADB over Wi-Fi"));
        add(m, bool(Constants.SP_UPDATE_PRERELEASE, false, CATEGORY_ADVANCED, "Include pre-releases")
                .description("The in-app updater also offers pre-releases"));
        // only the mqtt server publishes the zones
        add(m, bool(Constants.SP_PUBLISH_THERMAL_SENSORS, false, CATEGORY_ADVANCED, "Publish thermal sensors").visibleIf(mqttOn));
        add(m, bool(Constants.SP_DYNAMIC_TEMP_OFFSET_ENABLED, false, CATEGORY_ADVANCED, "Dynamic temperature offset")
                .requires(TEMPERATURE));
        SettingDef.Condition offsetOn = eq(Constants.SP_DYNAMIC_TEMP_OFFSET_ENABLED, true);
        add(m, string(Constants.SP_DYNAMIC_TEMP_OFFSET_ZONE, "", CATEGORY_ADVANCED, "Thermal zone for offset")
                .visibleIf(offsetOn).requires(TEMPERATURE));
        add(m, floating(Constants.SP_DYNAMIC_TEMP_OFFSET_BASELINE, 40.0, CATEGORY_ADVANCED, "Offset baseline")
                .unit("°C").description("Zone temperature at which no correction applies")
                .visibleIf(offsetOn).requires(TEMPERATURE));
        add(m, floating(Constants.SP_DYNAMIC_TEMP_OFFSET_K, 0.3, CATEGORY_ADVANCED, "Offset factor")
                .description("Degrees subtracted per degree the zone is above the baseline")
                .visibleIf(offsetOn).requires(TEMPERATURE));


        return Collections.unmodifiableMap(m);
    }

    private static void add(Map<String, SettingDef> m, SettingDef.Builder builder) {
        SettingDef def = builder.build();
        if (m.put(def.key, def) != null) throw new IllegalStateException("duplicate setting " + def.key);
    }

    private static SettingDef.Builder bool(String key, boolean def, String category, String label) {
        return new SettingDef.Builder(key, TYPE_BOOL, def, category, label);
    }

    private static SettingDef.Builder integer(String key, int def, String category, String label) {
        return new SettingDef.Builder(key, TYPE_INT, def, category, label);
    }

    private static SettingDef.Builder floating(String key, double def, String category, String label) {
        return new SettingDef.Builder(key, TYPE_FLOAT, def, category, label);
    }

    private static SettingDef.Builder string(String key, String def, String category, String label) {
        return new SettingDef.Builder(key, TYPE_STRING, def, category, label);
    }

    private static String format(String pattern, int index) {
        return String.format(Locale.US, pattern, index);
    }

    private static List<SettingDef.Option> displayModuleOptions() {
        List<SettingDef.Option> options = new ArrayList<>();
        for (DisplayModule module : DisplayModuleRegistry.getModules()) {
            options.add(new SettingDef.Option(module.getId(), moduleLabel(module.getId())));
        }
        return options;
    }

    private static String moduleLabel(String id) {
        switch (id) {
            case Constants.DISPLAY_MODULE_WEBVIEW: return "Web dashboard";
            case "app": return "App";
            default: return id;
        }
    }

    // labels for module option keys since their titles are string resources
    private static String optionLabel(String key) {
        switch (key) {
            case Constants.SP_WEBVIEW_URL: return "Dashboard URL";
            case Constants.SP_IGNORE_SSL_ERRORS: return "Ignore SSL errors";
            case Constants.SP_EXTENDED_JAVASCRIPT_INTERFACE: return "Extended JavaScript interface";
            case "app.package": return "App to show";
            case "app.component": return "App activity";
            case "app.keepInFront": return "Bring the app back when it closes";
            default: return key;
        }
    }

    private static String optionCategory(String key) {
        return Constants.SP_EXTENDED_JAVASCRIPT_INTERFACE.equals(key) ? CATEGORY_ADVANCED : CATEGORY_GENERAL;
    }

    // module options join automatically so a new module needs no change here
    // each one only applies while its module is the one on screen
    private static void addDisplayModuleOptions(Map<String, SettingDef> m) {
        for (DisplayModule module : DisplayModuleRegistry.getModules()) {
            SettingDef.Condition shown = eq(Constants.SP_DISPLAY_MODULE, module.getId());
            for (ModuleOption option : module.getOptions()) {
                String key = option.getKey();
                String category = optionCategory(key);
                String label = optionLabel(key);
                SettingDef.Builder b = null;
                if (option instanceof ModuleOption.Toggle) {
                    b = bool(key, ((ModuleOption.Toggle) option).getDefault(), category, label);
                } else if (option instanceof ModuleOption.Text) {
                    b = string(key, ((ModuleOption.Text) option).getDefault(), category, label);
                } else if (option instanceof ModuleOption.Url) {
                    b = string(key, ((ModuleOption.Url) option).getDefault(), category, label);
                } else if (option instanceof ModuleOption.IntNumber) {
                    ModuleOption.IntNumber o = (ModuleOption.IntNumber) option;
                    b = integer(key, o.getDefault(), category, label);
                    if (o.getMin() != Integer.MIN_VALUE) b.min(o.getMin());
                    if (o.getMax() != Integer.MAX_VALUE) b.max(o.getMax());
                } else if (option instanceof ModuleOption.FloatNumber) {
                    ModuleOption.FloatNumber o = (ModuleOption.FloatNumber) option;
                    b = floating(key, o.getDefault(), category, label);
                    if (o.getMin() != -Float.MAX_VALUE) b.min(o.getMin());
                    if (o.getMax() != Float.MAX_VALUE) b.max(o.getMax());
                } else if (option instanceof ModuleOption.Slider) {
                    ModuleOption.Slider o = (ModuleOption.Slider) option;
                    b = integer(key, o.getDefault(), category, label).range(o.getMin(), o.getMax()).step(o.getStep());
                } else if (option instanceof ModuleOption.Choice) {
                    ModuleOption.Choice o = (ModuleOption.Choice) option;
                    List<SettingDef.Option> options = new ArrayList<>();
                    for (ModuleOption.Choice.Entry entry : o.getEntries()) {
                        options.add(new SettingDef.Option(entry.getId(), entry.getId()));
                    }
                    b = new SettingDef.Builder(key, TYPE_ENUM, o.getDefault(), category, label).options(options);
                } else if (option instanceof ModuleOption.AppPicker) {
                    b = string(key, "", category, label).description("Package name of the app");
                    // follows the picked app so a ui shows it but never edits it
                    String componentKey = ((ModuleOption.AppPicker) option).getComponentKey();
                    add(m, string(componentKey, "", category, optionLabel(componentKey))
                            .description("Launcher activity of the app as package/class").visibleIf(shown).readOnly());
                }
                // actions store nothing
                if (b != null) add(m, b.visibleIf(shown));
            }
        }
    }

    private static List<SettingDef.Option> switcherGestureOptions() {
        List<SettingDef.Option> options = new ArrayList<>();
        options.add(new SettingDef.Option(Constants.APP_SWITCHER_GESTURE_OFF, "Off"));
        String[] directions = {"up", "down", "left", "right"};
        for (int fingers = 2; fingers <= 5; fingers++) {
            for (String direction : directions) {
                options.add(new SettingDef.Option("swipe_" + fingers + "_" + direction,
                        fingers + " fingers " + direction));
            }
        }
        return options;
    }

    // ---- values ----

    // validates a json or java value against the def and returns it in the stored java type
    // Boolean Integer Double String or List of String
    public static Object coerce(SettingDef def, Object value) {
        if (value == null || value == JSONObject.NULL) throw new IllegalArgumentException(def.key + ": null");
        switch (def.type) {
            case TYPE_BOOL:
                if (value instanceof Boolean) return value;
                if ("true".equals(value)) return Boolean.TRUE;
                if ("false".equals(value)) return Boolean.FALSE;
                throw new IllegalArgumentException(def.key + ": expected a boolean");
            case TYPE_INT: {
                double d = number(def, value);
                if (Math.floor(d) != d) throw new IllegalArgumentException(def.key + ": expected a whole number");
                checkRange(def, d);
                return (int) d;
            }
            case TYPE_FLOAT: {
                double d = number(def, value);
                checkRange(def, d);
                return d;
            }
            case TYPE_STRING:
                if (value instanceof String) return value;
                if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
                throw new IllegalArgumentException(def.key + ": expected a string");
            case TYPE_ENUM:
                return enumValue(def, value);
            case TYPE_STRING_LIST:
                return stringList(def, value);
            default:
                throw new IllegalArgumentException(def.key + ": unknown type " + def.type);
        }
    }

    private static double number(SettingDef def, Object value) {
        double d;
        if (value instanceof Number) {
            d = ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                d = Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(def.key + ": expected a number");
            }
        } else {
            throw new IllegalArgumentException(def.key + ": expected a number");
        }
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException(def.key + ": expected a number");
        return d;
    }

    private static void checkRange(SettingDef def, double d) {
        if (def.min != null && d < def.min) throw new IllegalArgumentException(def.key + ": below " + fmt(def.min));
        if (def.max != null && d > def.max) throw new IllegalArgumentException(def.key + ": above " + fmt(def.max));
        if (def.type.equals(TYPE_INT) && (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException(def.key + ": out of range");
        }
    }

    private static String fmt(double d) {
        return Math.floor(d) == d ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static Object enumValue(SettingDef def, Object value) {
        if (def.hasIntOptions()) {
            double d = number(def, value);
            for (SettingDef.Option option : def.options) {
                if (((Integer) option.value) == d) return option.value;
            }
        } else {
            String s = value instanceof String ? (String) value : String.valueOf(value);
            for (SettingDef.Option option : def.options) {
                if (option.value.equals(s)) return option.value;
            }
        }
        throw new IllegalArgumentException(def.key + ": not one of the options");
    }

    private static List<String> stringList(SettingDef def, Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (!(item instanceof String)) throw new IllegalArgumentException(def.key + ": expected strings");
                out.add((String) item);
            }
        } else if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                if (!(item instanceof String)) throw new IllegalArgumentException(def.key + ": expected strings");
                out.add((String) item);
            }
        } else {
            throw new IllegalArgumentException(def.key + ": expected a list");
        }
        return out;
    }

    // stored value or default in the java type of the def. a stored value of the wrong type
    // such as a port the legacy api wrote as a string is coerced or falls back to the default
    public static Object resolve(SettingDef def, Map<String, ?> stored) {
        Object raw = stored.get(def.key);
        if (raw == null) return def.defaultValue;
        try {
            Object value = raw instanceof Set ? new ArrayList<>((Set<?>) raw) : raw;
            if (def.type.equals(TYPE_STRING_LIST)) {
                List<String> list = stringList(def, value);
                // string sets have no order so sort for stable output and comparisons
                return new ArrayList<>(new TreeSet<>(list));
            }
            return coerce(def, value);
        } catch (IllegalArgumentException e) {
            // the device uses an out of range number as stored so report it clamped not as the default
            Double number = raw instanceof Number ? Double.valueOf(((Number) raw).doubleValue()) : null;
            if (number != null && (def.type.equals(TYPE_INT) || def.type.equals(TYPE_FLOAT))) {
                double clamped = number;
                if (def.min != null) clamped = Math.max(def.min, clamped);
                if (def.max != null) clamped = Math.min(def.max, clamped);
                try {
                    return coerce(def, clamped);
                } catch (IllegalArgumentException ignored) {
                    // fall through to the default
                }
            }
            return def.defaultValue;
        }
    }

    // every schema key with its resolved value in java types. secrets included
    public static Map<String, Object> resolvedValues(Map<String, ?> stored) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (SettingDef def : DEFS.values()) {
            values.put(def.key, resolve(def, stored));
        }
        return values;
    }

    // settings as the v1 api reports them. unknown stored keys pass through unless internal
    public static JSONObject resolvedSettings(SharedPreferences prefs, boolean includeSecrets) {
        Map<String, ?> stored = prefs.getAll();
        JSONObject out = new JSONObject();
        try {
            for (SettingDef def : DEFS.values()) {
                if (def.secret && !includeSecrets) continue;
                out.put(def.key, toJson(resolve(def, stored)));
            }
            for (Map.Entry<String, ?> entry : stored.entrySet()) {
                String key = entry.getKey();
                if (DEFS.containsKey(key) || isInternal(key) || entry.getValue() == null) continue;
                // a removed feature written back over the legacy api is not a setting anymore
                if (Arrays.asList(RemovedSettings.KEYS).contains(key)) continue;
                Object value = entry.getValue();
                if (value instanceof Set) {
                    value = new ArrayList<>(new TreeSet<>(stringSet((Set<?>) value)));
                } else if (value instanceof Float) {
                    value = ((Float) value).doubleValue();
                }
                out.put(key, toJson(value));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static List<String> stringSet(Set<?> set) {
        List<String> out = new ArrayList<>();
        for (Object o : set) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    // java value from resolve to a json value
    public static Object toJson(Object value) {
        if (value instanceof List) {
            JSONArray array = new JSONArray();
            for (Object item : (List<?>) value) array.put(item);
            return array;
        }
        return value == null ? JSONObject.NULL : value;
    }

    // ---- schema ----

    public static JSONObject schemaJson() {
        JSONArray schema = new JSONArray();
        try {
            for (SettingDef def : DEFS.values()) schema.put(defJson(def));
            return new JSONObject().put("schema", schema);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject defJson(SettingDef def) throws JSONException {
        JSONObject json = new JSONObject()
                .put("key", def.key)
                .put("type", def.type)
                .put("default", toJson(def.defaultValue))
                .put("category", def.category)
                .put("label", def.label);
        if (def.description != null) json.put("description", def.description);
        if (def.min != null) json.put("min", number(def.min));
        if (def.max != null) json.put("max", number(def.max));
        if (def.step != null) json.put("step", number(def.step));
        if (def.unit != null) json.put("unit", def.unit);
        if (def.options != null) {
            JSONArray options = new JSONArray();
            for (SettingDef.Option option : def.options) {
                options.put(new JSONObject().put("value", option.value).put("label", option.label));
            }
            json.put("options", options);
        }
        json.put("secret", def.secret)
                .put("per_device", def.perDevice)
                .put("requires_restart", def.requiresRestart)
                .put("deprecated", def.deprecated);
        if (def.replacedBy != null) json.put("replaced_by", def.replacedBy);
        if (!def.visibleIf.isEmpty()) {
            JSONArray conditions = new JSONArray();
            for (SettingDef.Condition condition : def.visibleIf) {
                JSONObject c = new JSONObject().put("key", condition.key);
                if (condition.op.equals(SettingDef.Condition.IN)) {
                    JSONArray values = new JSONArray();
                    for (Object value : condition.values) values.put(value);
                    c.put(condition.op, values);
                } else {
                    c.put(condition.op, condition.values.get(0));
                }
                conditions.put(c);
            }
            json.put("visible_if", conditions);
        }
        if (!def.requires.isEmpty()) {
            JSONArray requires = new JSONArray();
            for (SettingDef.Requirement requirement : def.requires) {
                JSONObject r = new JSONObject().put("cap", requirement.cap);
                if (requirement.min != null) r.put("min", (int) requirement.min);
                requires.put(r);
            }
            json.put("requires", requires);
        }
        if (def.hidden) json.put("hidden", true);
        if (def.readOnly) json.put("read_only", true);
        return json;
    }

    // whole numbers as longs so the json shows 255 and not 255.0
    private static Object number(double d) {
        return Math.floor(d) == d ? (Object) (long) d : (Object) d;
    }
}
