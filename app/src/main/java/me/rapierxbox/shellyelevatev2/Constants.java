package me.rapierxbox.shellyelevatev2;

public class Constants {
    public static final String SHARED_PREFERENCES_NAME = "ShellyElevateV2";

    //Generic SP Keys
    public static final String SP_DEVICE = "device";
    public static final String SP_LITE_MODE = "liteMode";
    public static final String SP_SETTINGS_EVER_SHOWN = "settingEverShown";

    //Media SP keys
    public static final String SP_MEDIA_ENABLED = "mediaEnabled";

    //IO SP Keys
    public static final String SP_SWITCH_ON_SWIPE = "switchOnSwipe";
    public static final String SP_POWER_BUTTON_AUTO_REBOOT = "powerButtonAutoReboot";
    public static final String SP_BUTTON_RELAY_ENABLED = "buttonRelayEnabled";
    public static final String SP_BUTTON_RELAY_MAP_FORMAT = "buttonRelayMap%d";

    //Webserver SP Keys
    public static final String SP_HTTP_SERVER_ENABLED = "httpServer";
    public static final String SP_EXTENDED_JAVASCRIPT_INTERFACE = "extendedJavascriptInterface";

    //HA SP Keys
    public static final String SP_WEBVIEW_URL = "webviewUrl";
    public static final String SP_DEPRECATED_HA_IP = "homeAssistantIp";
    public static final String SP_IGNORE_SSL_ERRORS = "ignoreSslErrors";

    //Screen SP Keys
    public static final String SP_AUTOMATIC_BRIGHTNESS = "automaticBrightness";
    public static final String SP_MIN_BRIGHTNESS = "minBrightness";
    public static final String SP_BRIGHTNESS = "brightness";

    //Screen Saver SP Keys
    public static final String SP_SCREEN_SAVER_ENABLED = "screenSaver";
    public static final String SP_SCREEN_SAVER_DELAY = "screenSaverDelay";
    public static final String SP_SCREEN_SAVER_ID = "screenSaverId";
    public static final String SP_WAKE_ON_PROXIMITY = "wakeOnProximity";
    public static final String SP_PROXIMITY_KEEP_AWAKE_SECONDS = "proximityKeepAwakeSeconds";
    public static final String SP_SCREEN_SAVER_MIN_BRIGHTNESS = "screenSaverMinBrightness";
    public static final String SP_TOUCH_TO_WAKE = "touchToWake";

    //Bluetooth Proxy SP Keys
    public static final String SP_BLUETOOTH_PROXY_ENABLED = "bluetoothProxyEnabled";
    public static final String SP_BLUETOOTH_PROXY_NAME    = "bluetoothProxyName";

    //MQTT SP Keys
    public static final String SP_MQTT_ENABLED = "mqttEnabled";
    public static final String SP_MQTT_BROKER = "mqttBroker";
    public static final String SP_MQTT_PORT = "mqttPort";
    public static final String SP_MQTT_USERNAME = "mqttUsername";
    public static final String SP_MQTT_PASSWORD = "mqttPassword";
    public static final String SP_MQTT_CLIENTID = "mqttDeviceId";

    //ScreenSaver intents
    public static final String INTENT_SCREEN_SAVER_STARTED = "me.rapierxbox.shellyelevatev2.SCREEN_SAVER_STARTED";
    public static final String INTENT_SCREEN_SAVER_STOPPED = "me.rapierxbox.shellyelevatev2.SCREEN_SAVER_STOPPED";
    public static final String INTENT_END_SCREENSAVER = "me.rapierxbox.shellyelevatev2.END_SCREENSAVER";

    //IO Intents
    public static final String INTENT_LIGHT_UPDATED = "me.rapierxbox.shellyelevatev2.LIGHT_UPDATED";
    public static final String INTENT_LIGHT_KEY = "lightValue";

    public static final String INTENT_PROXIMITY_UPDATED = "me.rapierxbox.shellyelevatev2.PROXIMITY_UPDATED";
    public static final String INTENT_PROXIMITY_KEY = "proximityValue";

    //Screen Intents
    public static final String INTENT_TURN_SCREEN_ON = "me.rapierxbox.shellyelevatev2.INTENT_TURN_SCREEN_ON";
    public static final String INTENT_TURN_SCREEN_OFF = "me.rapierxbox.shellyelevatev2.INTENT_TURN_SCREEN_OFF";

    //User Actions Intents
    public static final String ACTION_USER_INTERACTION = "shellyelevate.ACTION_USER_INTERACTION";
    public static final String INTENT_SETTINGS_CHANGED = "me.rapierxbox.shellyelevatev2.SETTINGS_CHANGED";
    public static final String INTENT_WEBVIEW_INJECT_JAVASCRIPT = "me.rapierxbox.shellyelevatev2.WEBVIEW_INJECT_JAVASCRIPT";

    //MQTT Topics
    public static final String MQTT_TOPIC_CONFIG_DEVICE = "homeassistant/device/%s/config";
    public static final String MQTT_TOPIC_STATUS = "shellyelevatev2/%s/status";
    public static final String MQTT_TOPIC_TEMP_SENSOR = "shellyelevatev2/%s/temp";
    public static final String MQTT_TOPIC_HUM_SENSOR = "shellyelevatev2/%s/hum";
    public static final String MQTT_TOPIC_LUX_SENSOR = "shellyelevatev2/%s/lux";
    public static final String MQTT_TOPIC_SCREEN_BRIGHTNESS = "shellyelevatev2/%s/bri";
    public static final String MQTT_TOPIC_PROXIMITY_SENSOR = "shellyelevatev2/%s/proximity";
    public static final String MQTT_TOPIC_RELAY_STATE = "shellyelevatev2/%s/relay_state";
    public static final String MQTT_TOPIC_RELAY_COMMAND = "shellyelevatev2/%s/relay_command";
    public static String MQTT_TOPIC_SWITCH_STATE = "shellyelevatev2/%s/switch_state";
    public static final String MQTT_TOPIC_UPDATE = "shellyelevatev2/%s/update";
    public static final String MQTT_TOPIC_UPDATE_GENERIC = "shellyelevatev2/update";
    public static final String MQTT_TOPIC_HELLO = "shellyelevatev2/%s/hello";
    public static final String MQTT_TOPIC_BUTTON_STATE = "shellyelevatev2/%s/button";
    public static final String MQTT_TOPIC_SLEEP_BUTTON = "shellyelevatev2/%s/sleep";
    public static final String MQTT_TOPIC_WAKE_BUTTON = "shellyelevatev2/%s/wake";
    public static final String MQTT_TOPIC_REFRESH_WEBVIEW_BUTTON = "shellyelevatev2/%s/refresh_webview";
    public static final String MQTT_TOPIC_REBOOT_BUTTON = "shellyelevatev2/%s/reboot";
    public static final String MQTT_TOPIC_POWER_BUTTON = "shellyelevatev2/%s/power_button";
    public static final String MQTT_TOPIC_SWIPE_EVENT = "shellyelevatev2/%s/swipe_event";
    public static final String MQTT_TOPIC_SLEEPING_BINARY_SENSOR = "shellyelevatev2/%s/sleeping";
    public static final String MQTT_TOPIC_HOME_ASSISTANT_STATUS = "homeassistant/status";

    //Button Press Types
    public static final String BUTTON_PRESS_TYPE_SHORT = "short";
    public static final String BUTTON_PRESS_TYPE_LONG = "long";
    public static final String BUTTON_PRESS_TYPE_DOUBLE = "double";
    public static final String BUTTON_PRESS_TYPE_TRIPLE = "triple";
}
