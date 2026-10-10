package me.rapierxbox.shellyelevatev2.mqtt;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mNightModeManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mVoiceEngine;

import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.eclipse.paho.mqttv5.common.MqttMessage;

import java.nio.charset.StandardCharsets;

import me.rapierxbox.shellyelevatev2.helper.RebootHelper;

// dispatches inbound command topics to the matching device helper
public class ShellyElevateMQTTCallback {
    private static final String TAG = "MQTTCallback";

    private final MQTTServer server;

    ShellyElevateMQTTCallback(MQTTServer server) {
        this.server = server;
    }

    // only the id segment becomes %s since an id like shelly or relay also appears in the fixed parts
    static String topicPattern(String topic, String clientId) {
        String[] parts = topic.split("/", -1);
        if (parts.length < 2 || !parts[1].equals(clientId)) return topic;
        parts[1] = "%s";
        return String.join("/", parts);
    }

    public void messageArrived(String topic, MqttMessage message) {
        if (MQTT_TOPIC_UPDATE_GENERIC.equals(topic)) {
            server.publishStatus();
            return;
        }

        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        switch (topicPattern(topic, server.getClientId())) {
            case MQTT_TOPIC_UPDATE:
                server.publishStatus();
                break;
            case MQTT_TOPIC_HOME_ASSISTANT_STATUS:
                // ha restarted so republish discovery for it to pick us up again
                if ("online".equals(payload)) {
                    Log.i(TAG, "Home Assistant online, republishing discovery");
                    server.publishStatus();
                }
                break;
            // todo match the relay index generically instead of one case per channel
            case MQTT_TOPIC_RELAY_COMMAND:
                mDeviceHelper.setRelay(0, payload.contains("ON"));
                break;
            case MQTT_TOPIC_RELAY_COMMAND + "_1":
                mDeviceHelper.setRelay(1, payload.contains("ON"));
                break;
            case MQTT_TOPIC_REFRESH_WEBVIEW_BUTTON:
                LocalBroadcastManager.getInstance(mApplicationContext)
                        .sendBroadcast(new Intent(INTENT_WEBVIEW_REFRESH));
                break;
            case MQTT_TOPIC_SLEEP_BUTTON:
                mScreenSaverManager.startScreenSaver();
                break;
            case MQTT_TOPIC_WAKE_BUTTON:
                mScreenSaverManager.stopScreenSaver();
                break;
            case MQTT_TOPIC_REBOOT_BUTTON:
                handleReboot();
                break;
            case MQTT_TOPIC_VOICE_TRIGGER:
                if (mVoiceEngine != null) mVoiceEngine.trigger();
                break;
            case MQTT_TOPIC_VOICE_MUTE_COMMAND:
                if (mVoiceEngine != null) {
                    mVoiceEngine.setMuted("ON".equalsIgnoreCase(payload.trim()));
                }
                break;
            case MQTT_TOPIC_SCREEN_BRIGHTNESS_COMMAND:
                handleScreenBrightness(payload.trim());
                break;
            case MQTT_TOPIC_NIGHT_MODE_COMMAND:
                if (mNightModeManager != null) {
                    mNightModeManager.setEnabled("ON".equalsIgnoreCase(payload.trim()));
                }
                break;
            case MQTT_TOPIC_DIMMER_COMMAND:
                handleDimmer(payload.trim());
                break;
            default:
                break;
        }
    }

    private void handleReboot() {
        RebootHelper.rebootUnlessJustStarted(mApplicationContext);
    }

    private void handleScreenBrightness(String payload) {
        try {
            int brightness = Integer.parseInt(payload);
            mDeviceHelper.setScreenBrightness(Math.max(0, Math.min(255, brightness)));
        } catch (NumberFormatException e) {
            Log.w(TAG, "Ignoring invalid screen brightness: " + payload);
        }
    }

    private void handleDimmer(String payload) {
        if (!mDeviceHelper.isDimmerAttached()) return;
        if ("ON".equalsIgnoreCase(payload)) {
            mDeviceHelper.setDimmerOn(true);
        } else if ("OFF".equalsIgnoreCase(payload)) {
            mDeviceHelper.setDimmerOn(false);
        } else {
            try {
                int brightness = Integer.parseInt(payload);
                mDeviceHelper.setDimmerBrightness(Math.max(0, Math.min(100, brightness)), null);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Ignoring invalid dimmer command: " + payload);
            }
        }
    }
}
