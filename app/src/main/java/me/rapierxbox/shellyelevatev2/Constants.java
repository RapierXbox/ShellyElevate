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

    //WebView OTA SP Keys
    public static final String SP_WEBVIEW_UPDATE_PROMPTED = "webviewUpdatePrompted";

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

    //Voice Assistant SP Keys
    public static final String SP_VOICE_ASSISTANT_ENABLED = "voiceAssistantEnabled";
    public static final String SP_VOICE_ASSISTANT_TOKEN = "voiceAssistantToken";
    public static final String SP_VOICE_ASSISTANT_PIPELINE_ID = "voiceAssistantPipelineId";
    public static final String SP_VOICE_ASSISTANT_MAX_RECORD_SECONDS = "voiceAssistantMaxRecordSeconds";
    public static final String SP_VOICE_WAKE_ENABLED = "voiceWakeEnabled";
    public static final String SP_VOICE_WAKE_MODEL_NAME = "voiceWakeModelName";
    public static final String SP_VOICE_WAKE_SOUND_ENABLED = "voiceWakeSoundEnabled";
    public static final String SP_VOICE_WAKE_SENSITIVITY    = "voiceWakeSensitivity"; // 0..100, 50 = model's published cutoff
    public static final String SP_VOICE_SCORE_BAR_ENABLED   = "voiceScoreBarEnabled";
    public static final String SP_VOICE_WAKE_COOLDOWN_SEC   = "voiceWakeCooldownSec";
    public static final String SP_VOICE_WAKE_EXPERIMENTAL_MODELS = "voiceWakeExperimentalModels";
    public static final String SP_VOICE_ASSISTANT_MUTED = "voiceAssistantMuted";

    //MQTT SP Keys
    public static final String SP_MQTT_ENABLED = "mqttEnabled";
    public static final String SP_MQTT_BROKER = "mqttBroker";
    public static final String SP_MQTT_PORT = "mqttPort";
    public static final String SP_MQTT_USERNAME = "mqttUsername";
    public static final String SP_MQTT_PASSWORD = "mqttPassword";
    public static final String SP_MQTT_CLIENTID = "mqttDeviceId";
    // Disable for non-HA brokers (ioBroker etc.) that can't parse the discovery JSON.
    public static final String SP_MQTT_HA_DISCOVERY = "mqttHomeAssistantDiscovery";
    // Retain per-state topics so a fresh subscriber sees current values immediately.
    public static final String SP_MQTT_RETAIN_STATE = "mqttRetainState";

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

    //Thermal SP Keys
    public static final String SP_PUBLISH_THERMAL_SENSORS      = "publishThermalSensors";
    public static final String SP_DYNAMIC_TEMP_OFFSET_ENABLED  = "dynamicTempOffsetEnabled";
    public static final String SP_DYNAMIC_TEMP_OFFSET_ZONE     = "dynamicTempOffsetZone";
    public static final String SP_DYNAMIC_TEMP_OFFSET_BASELINE = "dynamicTempOffsetBaseline";
    public static final String SP_DYNAMIC_TEMP_OFFSET_K        = "dynamicTempOffsetK";

    //MQTT Topics
    public static final String MQTT_TOPIC_CONFIG_DEVICE = "homeassistant/device/%s/config";
    public static final String MQTT_TOPIC_STATUS = "shellyelevatev2/%s/status";
    public static final String MQTT_TOPIC_TEMP_SENSOR = "shellyelevatev2/%s/temp";
    public static final String MQTT_TOPIC_HUM_SENSOR = "shellyelevatev2/%s/hum";
    public static final String MQTT_TOPIC_LUX_SENSOR = "shellyelevatev2/%s/lux";
    public static final String MQTT_TOPIC_SCREEN_BRIGHTNESS = "shellyelevatev2/%s/bri";
    public static final String MQTT_TOPIC_SCREEN_BRIGHTNESS_COMMAND = "shellyelevatev2/%s/bri_set";
    public static final String MQTT_TOPIC_PROXIMITY_SENSOR = "shellyelevatev2/%s/proximity";
    public static final String MQTT_TOPIC_RELAY_STATE = "shellyelevatev2/%s/relay_state";
    public static final String MQTT_TOPIC_RELAY_COMMAND = "shellyelevatev2/%s/relay_command";
    public static final String MQTT_TOPIC_SWITCH_STATE = "shellyelevatev2/%s/switch_state";
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
    public static final String MQTT_TOPIC_THERMAL_ZONE = "shellyelevatev2/%s/thermal/%s";
    public static final String MQTT_TOPIC_HOME_ASSISTANT_STATUS = "homeassistant/status";

    //Voice Assistant Intents
    public static final String INTENT_VOICE_STATE_CHANGED = "me.rapierxbox.shellyelevatev2.VOICE_STATE_CHANGED";
    public static final String INTENT_VOICE_STATE_KEY     = "voiceState";
    public static final String INTENT_VOICE_TEXT          = "me.rapierxbox.shellyelevatev2.VOICE_TEXT";
    public static final String INTENT_VOICE_TEXT_KEY      = "voiceText";
    public static final String INTENT_VOICE_SCORE         = "me.rapierxbox.shellyelevatev2.VOICE_SCORE";
    public static final String INTENT_VOICE_SCORE_KEY     = "wakeScore";
    public static final String INTENT_VOICE_THRESHOLD_KEY = "wakeThreshold";

    //Voice MQTT Topics
    public static final String MQTT_TOPIC_VOICE_STATUS       = "shellyelevatev2/%s/voice/status";
    public static final String MQTT_TOPIC_VOICE_TRIGGER      = "shellyelevatev2/%s/voice/trigger";
    public static final String MQTT_TOPIC_VOICE_MUTE_STATE   = "shellyelevatev2/%s/voice/mute";
    public static final String MQTT_TOPIC_VOICE_MUTE_COMMAND = "shellyelevatev2/%s/voice/mute_set";

    //Voice status sensor values
    public static final String VOICE_STATUS_READY     = "ready";
    public static final String VOICE_STATUS_MUTED     = "muted";
    public static final String VOICE_STATUS_LISTENING = "listening";
    public static final String VOICE_STATUS_ANSWERING = "answering";

    //Button Press Types
    public static final String BUTTON_PRESS_TYPE_SHORT = "short";
    public static final String BUTTON_PRESS_TYPE_LONG = "long";
    public static final String BUTTON_PRESS_TYPE_DOUBLE = "double";
    public static final String BUTTON_PRESS_TYPE_TRIPLE = "triple";

    //Swipe Event Types
    public static final String SWIPE_EVENT_TYPE_SINGLE              = "swipe";
    public static final String SWIPE_EVENT_TYPE_TWO_FINGER_UP       = "two_finger_swipe_up";
    public static final String SWIPE_EVENT_TYPE_TWO_FINGER_DOWN     = "two_finger_swipe_down";
    public static final String SWIPE_EVENT_TYPE_TWO_FINGER_LEFT     = "two_finger_swipe_left";
    public static final String SWIPE_EVENT_TYPE_TWO_FINGER_RIGHT    = "two_finger_swipe_right";

    //Dimmer MQTT Topics
    public static final String MQTT_TOPIC_DIMMER_STATE   = "shellyelevatev2/%s/dimmer";
    public static final String MQTT_TOPIC_DIMMER_COMMAND = "shellyelevatev2/%s/dimmer_set";
    public static final String MQTT_TOPIC_DIMMER_BRI     = "shellyelevatev2/%s/dimmer_bri";
    public static final String MQTT_TOPIC_DIMMER_POWER   = "shellyelevatev2/%s/dimmer_power";

    //Dimmer SP Keys
    public static final String SP_DIMMER_LAST_BRIGHTNESS = "dimmerLastBrightness";
    public static final String SP_DIMMER_LAST_STATE      = "dimmerLastState";
}
