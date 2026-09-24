package me.rapierxbox.shellyelevatev2.mqtt;

import static me.rapierxbox.shellyelevatev2.Constants.*;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.helper.DeviceHelper;
import me.rapierxbox.shellyelevatev2.helper.ThermalZoneReader;
import me.rapierxbox.shellyelevatev2.stes.StesProtocolHandler;

// builds the single device based home assistant discovery payload
// unique ids and object ids are part of the ha entity registry so never change them
class MqttDiscoveryConfigBuilder {
    private static final String TAG = "MqttDiscoveryConfigBuilder";
    private static final String OBJECT_ID_PREFIX = "shelly_walldisplay_";

    private final String clientId;
    private final DeviceModel device;
    private final SharedPreferences prefs;

    MqttDiscoveryConfigBuilder(String clientId, DeviceModel device, SharedPreferences prefs) {
        this.clientId = clientId;
        this.device = device;
        this.prefs = prefs;
    }

    JSONObject build() throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("dev", buildDevice());
        payload.put("o", buildOrigin());

        JSONObject components = new JSONObject();
        addSensorComponents(components);
        addButtonComponents(components);
        addRelayAndSwitchComponents(components);
        addDimmerComponent(components);
        addScreenBrightnessComponent(components);
        addControlButtonComponents(components);
        addMiscComponents(components);
        addThermalComponents(components);
        addVoiceComponents(components);
        addNightModeComponent(components);
        payload.put("cmps", components);

        // shared availability so every entity follows the lwt status topic
        JSONObject availability = new JSONObject();
        availability.put("topic", parseTopic(MQTT_TOPIC_STATUS));
        availability.put("payload_available", "online");
        availability.put("payload_not_available", "offline");
        payload.put("availability", new JSONArray().put(availability));
        return payload;
    }

    private String parseTopic(String topic) {
        return topic.replace("%s", clientId);
    }

    private static String indexSuffix(int num) {
        return num > 0 ? "_" + num : "";
    }

    private static String nameTrailer(int num) {
        return num > 0 ? " " + num : "";
    }

    private JSONObject buildDevice() throws JSONException {
        JSONObject d = new JSONObject();
        d.put("ids", clientId);
        d.put("name", device.displayName + " (" + clientId + ")");
        d.put("mf", "Shelly");
        d.put("mdl", device.sku);
        d.put("sw", BuildConfig.VERSION_NAME);
        return d;
    }

    private JSONObject buildOrigin() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", "ShellyElevateV2");
        o.put("url", "https://github.com/RapierXbox/ShellyElevate");
        return o;
    }

    private void addSensorComponents(JSONObject components) throws JSONException {
        // newer hardware has no sht3x so skip entities that would stay unavailable forever #104
        if (DeviceHelper.hasTempAndHumSensor()) {
            add(components, clientId + "_temp", sensor("Temperature", MQTT_TOPIC_TEMP_SENSOR, "temperature", "°C"));
            add(components, clientId + "_hum", sensor("Humidity", MQTT_TOPIC_HUM_SENSOR, "humidity", "%"));
        }
        add(components, clientId + "_lux", sensor("Light", MQTT_TOPIC_LUX_SENSOR, "illuminance", "lx"));

        if (device.hasProximitySensor) {
            add(components, clientId + "_proximity", sensor("Proximity", MQTT_TOPIC_PROXIMITY_SENSOR, "distance", "cm"));
        }
    }

    private void addButtonComponents(JSONObject components) throws JSONException {
        if (device.hasPowerButton) {
            String topic = parseTopic(MQTT_TOPIC_POWER_BUTTON);
            String id = clientId + "_power_button";
            add(components, id, buttonEvent("Power Button", topic));
            add(components, id + "_lastpress", buttonTimestamp("Power Button Last Press", topic));
        }

        for (int i = 0; i < device.buttons; i++) {
            String topic = parseTopic(MQTT_TOPIC_BUTTON_STATE) + "/" + i;
            String id = clientId + "_button_" + i;
            add(components, id, buttonEvent("Button " + i, topic));
            add(components, id + "_lastpress", buttonTimestamp("Button " + i + " Last Press", topic));
        }
    }

    private void addRelayAndSwitchComponents(JSONObject components) throws JSONException {
        for (int num = 0; num < device.relays; num++) {
            String suffix = indexSuffix(num);
            JSONObject relay = component("switch", "Relay" + nameTrailer(num));
            relay.put("state_topic", parseTopic(MQTT_TOPIC_RELAY_STATE) + suffix);
            relay.put("command_topic", parseTopic(MQTT_TOPIC_RELAY_COMMAND) + suffix);
            relay.put("device_class", "outlet");
            add(components, clientId + "_relay" + suffix, relay);
        }

        for (int num = 0; num < device.inputs; num++) {
            String suffix = indexSuffix(num);
            String name = "Switch" + nameTrailer(num);

            // physical inputs only report press or release and are never commanded by ha
            JSONObject sw = component("binary_sensor", name);
            sw.put("state_topic", parseTopic(MQTT_TOPIC_SWITCH_STATE) + suffix);
            sw.put("payload_on", "PRESS");
            sw.put("payload_off", "RELEASE");
            add(components, clientId + "_switch" + suffix, sw);

            // in button mode the input also emits press gestures on button/10x like the touch buttons
            int mode = prefs.getInt(String.format(Locale.US, SP_SW_INPUT_MODE_FORMAT, num), SW_INPUT_MODE_BUTTON);
            if (mode == SW_INPUT_MODE_BUTTON) {
                String eventTopic = parseTopic(MQTT_TOPIC_BUTTON_STATE) + "/" + (100 + num);
                add(components, clientId + "_switch" + suffix + "_events", buttonEvent(name + " Events", eventTopic));
            }
        }
    }

    private void addDimmerComponent(JSONObject components) throws JSONException {
        if (!StesProtocolHandler.isOperational()) return;
        JSONObject dimmer = component("light", "Dimmer");
        dimmer.put("state_topic", parseTopic(MQTT_TOPIC_DIMMER_STATE));
        dimmer.put("command_topic", parseTopic(MQTT_TOPIC_DIMMER_COMMAND));
        dimmer.put("brightness_state_topic", parseTopic(MQTT_TOPIC_DIMMER_BRI));
        dimmer.put("brightness_command_topic", parseTopic(MQTT_TOPIC_DIMMER_COMMAND));
        dimmer.put("brightness_scale", 100);
        dimmer.put("on_command_type", "brightness");
        add(components, clientId + "_dimmer", dimmer);
    }

    private void addScreenBrightnessComponent(JSONObject components) throws JSONException {
        JSONObject bri = component("number", "Screen Brightness");
        bri.put("state_topic", parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS));
        bri.put("command_topic", parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS_COMMAND));
        bri.put("min", 0);
        bri.put("max", 255);
        bri.put("step", 1);
        bri.put("mode", "slider");
        bri.put("icon", "mdi:brightness-6");
        add(components, clientId + "_screen_brightness", bri);
    }

    private void addControlButtonComponents(JSONObject components) throws JSONException {
        add(components, clientId + "_sleep", commandButton("Sleep", MQTT_TOPIC_SLEEP_BUTTON, null));
        add(components, clientId + "_wake", commandButton("Wake", MQTT_TOPIC_WAKE_BUTTON, null));
        add(components, clientId + "_refresh_webview", commandButton("Refresh Webview", MQTT_TOPIC_REFRESH_WEBVIEW_BUTTON, "restart"));
        add(components, clientId + "_reboot", commandButton("Reboot", MQTT_TOPIC_REBOOT_BUTTON, "restart"));
    }

    private void addMiscComponents(JSONObject components) throws JSONException {
        JSONObject swipe = component("event", "Swipe Event");
        swipe.put("state_topic", parseTopic(MQTT_TOPIC_SWIPE_EVENT));
        swipe.put("device_class", "button");
        swipe.put("event_types", new JSONArray()
                .put(SWIPE_EVENT_TYPE_SINGLE)
                .put(SWIPE_EVENT_TYPE_TWO_FINGER_UP)
                .put(SWIPE_EVENT_TYPE_TWO_FINGER_DOWN)
                .put(SWIPE_EVENT_TYPE_TWO_FINGER_LEFT)
                .put(SWIPE_EVENT_TYPE_TWO_FINGER_RIGHT)
                .put(SWIPE_EVENT_TYPE_THREE_FINGER_UP)
                .put(SWIPE_EVENT_TYPE_THREE_FINGER_DOWN)
                .put(SWIPE_EVENT_TYPE_THREE_FINGER_LEFT)
                .put(SWIPE_EVENT_TYPE_THREE_FINGER_RIGHT)
                .put(SWIPE_EVENT_TYPE_FOUR_FINGER_UP)
                .put(SWIPE_EVENT_TYPE_FOUR_FINGER_DOWN)
                .put(SWIPE_EVENT_TYPE_FOUR_FINGER_LEFT)
                .put(SWIPE_EVENT_TYPE_FOUR_FINGER_RIGHT)
                .put(SWIPE_EVENT_TYPE_FIVE_FINGER_UP)
                .put(SWIPE_EVENT_TYPE_FIVE_FINGER_DOWN)
                .put(SWIPE_EVENT_TYPE_FIVE_FINGER_LEFT)
                .put(SWIPE_EVENT_TYPE_FIVE_FINGER_RIGHT));
        add(components, clientId + "_swipe_event", swipe);

        JSONObject sleeping = component("binary_sensor", "Sleeping");
        sleeping.put("state_topic", parseTopic(MQTT_TOPIC_SLEEPING_BINARY_SENSOR));
        add(components, clientId + "_sleeping", sleeping);
    }

    private void addThermalComponents(JSONObject components) throws JSONException {
        if (!prefs.getBoolean(SP_PUBLISH_THERMAL_SENSORS, false)) return;
        for (ThermalZoneReader.Zone zone : ThermalZoneReader.discoverZones()) {
            JSONObject thermal = component("sensor", "Thermal " + zone.type.replace("_", " "));
            thermal.put("state_topic", String.format(MQTT_TOPIC_THERMAL_ZONE, clientId, zone.type));
            thermal.put("device_class", "temperature");
            thermal.put("unit_of_measurement", "°C");
            thermal.put("state_class", "measurement");
            add(components, clientId + "_thermal_" + zone.type, thermal);
        }
    }

    // voice entities only exist while the assist pipeline is enabled in settings
    private void addVoiceComponents(JSONObject components) throws JSONException {
        boolean enabled = prefs.getBoolean(SP_VOICE_ASSISTANT_ENABLED, false);
        Log.d(TAG, "addVoiceComponents: SP_VOICE_ASSISTANT_ENABLED=" + enabled);
        if (!enabled) return;

        JSONObject status = component("sensor", "Voice Assistant");
        status.put("state_topic", parseTopic(MQTT_TOPIC_VOICE_STATUS));
        status.put("device_class", "enum");
        status.put("options", new JSONArray()
                .put(VOICE_STATUS_READY)
                .put(VOICE_STATUS_MUTED)
                .put(VOICE_STATUS_LISTENING)
                .put(VOICE_STATUS_ANSWERING));
        status.put("icon", "mdi:microphone-message");
        add(components, clientId + "_voice_status", status);

        JSONObject mute = onOffSwitch("Voice Assistant Mute", MQTT_TOPIC_VOICE_MUTE_STATE, MQTT_TOPIC_VOICE_MUTE_COMMAND);
        mute.put("icon", "mdi:microphone-off");
        add(components, clientId + "_voice_mute", mute);

        JSONObject trigger = component("button", "Voice Assistant Listen");
        trigger.put("command_topic", parseTopic(MQTT_TOPIC_VOICE_TRIGGER));
        trigger.put("icon", "mdi:microphone");
        add(components, clientId + "_voice_trigger", trigger);
    }

    private void addNightModeComponent(JSONObject components) throws JSONException {
        JSONObject nightMode = onOffSwitch("Night Mode", MQTT_TOPIC_NIGHT_MODE_STATE, MQTT_TOPIC_NIGHT_MODE_COMMAND);
        nightMode.put("icon", "mdi:weather-night");
        add(components, clientId + "_night_mode", nightMode);
    }

    // every component is keyed by its unique id and gets the matching object id
    private static void add(JSONObject components, String uniqueId, JSONObject component) throws JSONException {
        component.put("unique_id", uniqueId);
        component.put("object_id", OBJECT_ID_PREFIX + uniqueId);
        components.put(uniqueId, component);
    }

    private static JSONObject component(String platform, String name) throws JSONException {
        JSONObject c = new JSONObject();
        c.put("p", platform);
        c.put("name", name);
        return c;
    }

    private JSONObject sensor(String name, String stateTopic, String deviceClass, String unit) throws JSONException {
        JSONObject s = component("sensor", name);
        s.put("state_topic", parseTopic(stateTopic));
        s.put("device_class", deviceClass);
        s.put("unit_of_measurement", unit);
        return s;
    }

    private JSONObject onOffSwitch(String name, String stateTopic, String commandTopic) throws JSONException {
        JSONObject s = component("switch", name);
        s.put("state_topic", parseTopic(stateTopic));
        s.put("command_topic", parseTopic(commandTopic));
        s.put("payload_on", "ON");
        s.put("payload_off", "OFF");
        return s;
    }

    private JSONObject commandButton(String name, String commandTopic, String deviceClass) throws JSONException {
        JSONObject b = component("button", name);
        b.put("command_topic", parseTopic(commandTopic));
        if (deviceClass != null) b.put("device_class", deviceClass);
        return b;
    }

    private static JSONObject buttonEvent(String name, String stateTopic) throws JSONException {
        JSONObject e = component("event", name);
        e.put("state_topic", stateTopic);
        e.put("device_class", "button");
        e.put("event_types", new JSONArray()
                .put(BUTTON_PRESS_TYPE_SHORT)
                .put(BUTTON_PRESS_TYPE_LONG)
                .put(BUTTON_PRESS_TYPE_DOUBLE)
                .put(BUTTON_PRESS_TYPE_TRIPLE));
        return e;
    }

    private static JSONObject buttonTimestamp(String name, String stateTopic) throws JSONException {
        JSONObject s = component("sensor", name);
        s.put("state_topic", stateTopic);
        s.put("device_class", "timestamp");
        s.put("value_template",
                "{{ (value_json.last_update / 1000) | timestamp_custom('%Y-%m-%dT%H:%M:%S%z', true) }}");
        return s;
    }
}
