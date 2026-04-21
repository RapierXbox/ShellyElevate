package me.rapierxbox.shellyelevatev2.mqtt;

import static me.rapierxbox.shellyelevatev2.Constants.*;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.helper.ThermalZoneReader;

class MqttDiscoveryConfigBuilder {
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
        addControlButtonComponents(components);
        addMiscComponents(components);
        addThermalComponents(components);

        payload.put("cmps", components);
        payload.put("state_topic", MQTT_TOPIC_STATUS);
        return payload;
    }

    private String parseTopic(String topic) {
        return topic.replace("%s", clientId);
    }

    private JSONObject buildDevice() throws JSONException {
        JSONObject d = new JSONObject();
        d.put("ids", clientId);
        d.put("name", device.friendlyName + " (" + clientId + ")");
        d.put("mf", "Shelly");
        d.put("mdl", device.modelName);
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
        components.put(clientId + "_temp", sensor("Temperature", parseTopic(MQTT_TOPIC_TEMP_SENSOR), "temperature", "°C", "_temp"));
        components.put(clientId + "_hum",  sensor("Humidity",    parseTopic(MQTT_TOPIC_HUM_SENSOR),  "humidity",    "%",  "_hum"));
        components.put(clientId + "_lux",  sensor("Light",       parseTopic(MQTT_TOPIC_LUX_SENSOR),  "illuminance", "lx", "_lux"));

        if (device.hasProximitySensor) {
            components.put(clientId + "_proximity",
                    sensor("Proximity", parseTopic(MQTT_TOPIC_PROXIMITY_SENSOR), "distance", "cm", "_proximity"));
        }
    }

    private void addButtonComponents(JSONObject components) throws JSONException {
        if (device.hasPowerButton) {
            String topic = parseTopic(MQTT_TOPIC_POWER_BUTTON);
            components.put(clientId + "_power_button",
                    buttonEventConfig("Power Button", topic, clientId + "_power_button"));
            components.put(clientId + "_power_button_lastpress",
                    buttonTimestampConfig("Power Button Last Press", topic, clientId + "_power_button_lastpress"));
        }

        for (int i = 0; i < device.buttons; i++) {
            String topic = parseTopic(MQTT_TOPIC_BUTTON_STATE) + "/" + i;
            String id = clientId + "_button_" + i;
            components.put(id,
                    buttonEventConfig("Button " + i, topic, id));
            components.put(id + "_lastpress",
                    buttonTimestampConfig("Button " + i + " Last Press", topic, id + "_lastpress"));
        }
    }

    private void addRelayAndSwitchComponents(JSONObject components) throws JSONException {
        for (int num = 0; num < device.inputs; num++) {
            String suffix = num > 0 ? "_" + num : "";
            String nameTrailer = num > 0 ? " " + num : "";

            JSONObject relay = new JSONObject();
            relay.put("p", "switch");
            relay.put("name", ("Relay" + nameTrailer).trim());
            relay.put("state_topic", parseTopic(MQTT_TOPIC_RELAY_STATE) + suffix);
            relay.put("command_topic", parseTopic(MQTT_TOPIC_RELAY_COMMAND) + suffix);
            relay.put("device_class", "outlet");
            relay.put("unique_id", clientId + "_relay" + suffix);
            relay.put("object_id", "shelly_walldisplay_" + clientId + "_relay" + suffix);
            components.put(clientId + "_relay" + suffix, relay);

            JSONObject sw = new JSONObject();
            sw.put("p", "button");
            sw.put("name", ("Switch" + nameTrailer).trim());
            sw.put("command_topic", parseTopic(MQTT_TOPIC_SWITCH_STATE) + suffix);
            sw.put("payload_press", "PRESS");
            sw.put("payload_release", "RELEASE");
            sw.put("value_template", "{{ value }}");
            sw.put("unique_id", clientId + "_switch" + suffix);
            sw.put("object_id", "shelly_walldisplay_" + clientId + "_switch" + suffix);
            sw.put("device_class", "restart");
            components.put(clientId + "_switch" + suffix, sw);
        }
    }

    private void addControlButtonComponents(JSONObject components) throws JSONException {
        components.put(clientId + "_sleep",            simpleButton("Sleep",           parseTopic(MQTT_TOPIC_SLEEP_BUTTON),            "_sleep",            null));
        components.put(clientId + "_wake",             simpleButton("Wake",            parseTopic(MQTT_TOPIC_WAKE_BUTTON),             "_wake",             null));
        components.put(clientId + "_refresh_webview",  simpleButton("Refresh Webview", parseTopic(MQTT_TOPIC_REFRESH_WEBVIEW_BUTTON),  "_refresh_webview",  "restart"));
        components.put(clientId + "_reboot",           simpleButton("Reboot",          parseTopic(MQTT_TOPIC_REBOOT_BUTTON),           "_reboot",           "restart"));
    }

    private void addMiscComponents(JSONObject components) throws JSONException {
        JSONObject swipe = new JSONObject();
        swipe.put("p", "event");
        swipe.put("name", "Swipe Event");
        swipe.put("state_topic", parseTopic(MQTT_TOPIC_SWIPE_EVENT));
        swipe.put("device_class", "button");
        swipe.put("event_types", new JSONArray().put("swipe"));
        swipe.put("unique_id", clientId + "_swipe_event");
        swipe.put("object_id", "shelly_walldisplay_" + clientId + "_swipe_event");
        components.put(clientId + "_swipe_event", swipe);

        JSONObject sleeping = new JSONObject();
        sleeping.put("p", "binary_sensor");
        sleeping.put("name", "Sleeping");
        sleeping.put("state_topic", parseTopic(MQTT_TOPIC_SLEEPING_BINARY_SENSOR));
        sleeping.put("unique_id", clientId + "_sleeping");
        sleeping.put("object_id", "shelly_walldisplay_" + clientId + "_sleeping");
        components.put(clientId + "_sleeping", sleeping);
    }

    private void addThermalComponents(JSONObject components) throws JSONException {
        if (!prefs.getBoolean(SP_PUBLISH_THERMAL_SENSORS, false)) return;
        for (ThermalZoneReader.Zone zone : ThermalZoneReader.discoverZones()) {
            String zoneId = clientId + "_thermal_" + zone.type;
            JSONObject thermal = new JSONObject();
            thermal.put("p", "sensor");
            thermal.put("name", "Thermal " + zone.type.replace("_", " "));
            thermal.put("state_topic", String.format(MQTT_TOPIC_THERMAL_ZONE, clientId, zone.type));
            thermal.put("device_class", "temperature");
            thermal.put("unit_of_measurement", "°C");
            thermal.put("state_class", "measurement");
            thermal.put("unique_id", zoneId);
            thermal.put("object_id", "shelly_walldisplay_" + zoneId);
            components.put(zoneId, thermal);
        }
    }

    private JSONObject sensor(String name, String stateTopic, String deviceClass, String unit, String idSuffix) throws JSONException {
        JSONObject s = new JSONObject();
        s.put("p", "sensor");
        s.put("name", name);
        s.put("state_topic", stateTopic);
        s.put("device_class", deviceClass);
        s.put("unit_of_measurement", unit);
        s.put("unique_id", clientId + idSuffix);
        s.put("object_id", "shelly_walldisplay_" + clientId + idSuffix);
        return s;
    }

    private JSONObject simpleButton(String name, String commandTopic, String idSuffix, String deviceClass) throws JSONException {
        JSONObject b = new JSONObject();
        b.put("p", "button");
        b.put("name", name);
        b.put("command_topic", commandTopic);
        b.put("unique_id", clientId + idSuffix);
        b.put("object_id", "shelly_walldisplay_" + clientId + idSuffix);
        if (deviceClass != null) b.put("device_class", deviceClass);
        return b;
    }

    private JSONObject buttonEventConfig(String name, String stateTopic, String uniqueId) throws JSONException {
        JSONObject e = new JSONObject();
        e.put("p", "event");
        e.put("name", name);
        e.put("state_topic", stateTopic);
        e.put("device_class", "button");
        e.put("event_types", new JSONArray()
                .put(BUTTON_PRESS_TYPE_SHORT)
                .put(BUTTON_PRESS_TYPE_LONG)
                .put(BUTTON_PRESS_TYPE_DOUBLE)
                .put(BUTTON_PRESS_TYPE_TRIPLE));
        e.put("unique_id", uniqueId);
        e.put("object_id", "shelly_walldisplay_" + uniqueId);
        return e;
    }

    private JSONObject buttonTimestampConfig(String name, String stateTopic, String uniqueId) throws JSONException {
        JSONObject s = new JSONObject();
        s.put("p", "sensor");
        s.put("name", name);
        s.put("state_topic", stateTopic);
        s.put("unique_id", uniqueId);
        s.put("object_id", "shelly_walldisplay_" + uniqueId);
        s.put("device_class", "timestamp");
        s.put("value_template",
                "{{ (value_json.last_update / 1000) | timestamp_custom('%Y-%m-%dT%H:%M:%S%z', true) }}");
        return s;
    }
}
