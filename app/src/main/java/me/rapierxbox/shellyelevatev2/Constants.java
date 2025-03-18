package me.rapierxbox.shellyelevatev2;

public class Constants {
    public static final String SHARED_PREFERENCES_NAME = "ShellyElevateV2";

    public static final String SP_WEBVIEW_URL = "webviewUrl";
    public static final String SP_HTTP_SERVER_ENABLED = "httpServer";
    public static final String SP_SWITCH_ON_SWIPE = "switchOnSwipe";
    public static final String SP_AUTOMATIC_BRIGHTNESS = "automaticBrightness";
    public static final String SP_BRIGHTNESS = "brightness";
    public static final String SP_SCREEN_SAVER_ENABLED = "screenSaver";
    public static final String SP_SCREEN_SAVER_DELAY = "screenSaverDelay";
    public static final String SP_SCREEN_SAVER_ID = "screenSaverId";
    public static final String SP_LITE_MODE = "liteMode";
    public static final String SP_EXTENDED_JAVASCRIPT_INTERFACE = "extendedJavascriptInterface";

    public static final String SP_MQTT_ENABLED = "mqttEnabled";
    public static final String SP_MQTT_BROKER = "mqttBroker";
    public static final String SP_MQTT_PORT = "mqttPort";
    public static final String SP_MQTT_USERNAME = "mqttUsername";
    public static final String SP_MQTT_PASSWORD = "mqttPassword";
    public static final String SP_MQTT_DEVICE_ID = "mqttDeviceId";

    public static final String SP_DEPRECATED_HA_IP = "homeAssistantIp";

    public static final String INTENT_WEBVIEW_REFRESH = "me.rapierxbox.shellyelevatev2.REFRESH_WEBVIEW";
    public static final String INTENT_WEBVIEW_INJECT_JAVASCRIPT = "me.rapierxbox.shellyelevatev2.WEBVIEW_INJECT_JAVASCRIPT";

    public static final String MQTT_TOPIC_CONFIG_DEVICE = "homeassistant/device/%s/config";
    public static final String MQTT_TOPIC_STATUS = "shellyelevatev2/%s/status";

    public static final String MQTT_TOPIC_TEMP_SENSOR = "shellyelevatev2/%s/temp";
    public static final String MQTT_TOPIC_HUM_SENSOR = "shellyelevatev2/%s/hum";
    public static final String MQTT_TOPIC_LUX_SENSOR = "shellyelevatev2/%s/lux";
    public static final String MQTT_TOPIC_RELAY_STATE = "shellyelevatev2/%s/relay_state";
    public static final String MQTT_TOPIC_RELAY_COMMAND = "shellyelevatev2/%s/relay_command";
    public static final String MQTT_TOPIC_SLEEP_BUTTON = "shellyelevatev2/%s/sleep";
    public static final String MQTT_TOPIC_WAKE_BUTTON = "shellyelevatev2/%s/wake";
    public static final String MQTT_TOPIC_REFRESH_WEBVIEW_BUTTON = "shellyelevatev2/%s/refresh_webview";
    public static final String MQTT_TOPIC_REBOOT_BUTTON = "shellyelevatev2/%s/reboot";
    public static final String MQTT_TOPIC_SWIPE_EVENT = "shellyelevatev2/%s/swipe_event";
    public static final String MQTT_TOPIC_SLEEPING_BINARY_SENSOR = "shellyelevatev2/%s/sleeping";

    public static final String MQTT_TOPIC_HOME_ASSISTANT_STATUS = "homeassistant/status";

}
