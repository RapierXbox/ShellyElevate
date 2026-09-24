package me.rapierxbox.shellyelevatev2.mqtt;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mNightModeManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mVoiceAssistantManager;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.eclipse.paho.mqttv5.common.MqttMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import me.rapierxbox.shellyelevatev2.ShellyElevateApplication;

// dispatches inbound command topics to the matching device helper
public class ShellyElevateMQTTCallback {
    private static final String TAG = "MQTTCallback";
    // a retained reboot command would otherwise boot loop the device
    private static final long REBOOT_GRACE_SECONDS = 20;

    private final MQTTServer server;
    // toasts must run on a looper thread not the paho comms thread
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    ShellyElevateMQTTCallback(MQTTServer server) {
        this.server = server;
    }

    public void messageArrived(String topic, MqttMessage message) {
        if (MQTT_TOPIC_UPDATE_GENERIC.equals(topic)) {
            server.publishStatus();
            return;
        }

        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        switch (topic.replace(server.getClientId(), "%s")) {
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
                if (mVoiceAssistantManager != null) mVoiceAssistantManager.trigger();
                break;
            case MQTT_TOPIC_VOICE_MUTE_COMMAND:
                if (mVoiceAssistantManager != null) {
                    mVoiceAssistantManager.setMuted("ON".equalsIgnoreCase(payload.trim()));
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
        long uptimeSec = (System.currentTimeMillis() - ShellyElevateApplication.getApplicationStartTime()) / 1000;
        if (uptimeSec > REBOOT_GRACE_SECONDS) {
            try {
                Runtime.getRuntime().exec("reboot");
            } catch (IOException e) {
                Log.e(TAG, "Error rebooting", e);
            }
        } else {
            String waitMessage = "Please wait " + (REBOOT_GRACE_SECONDS - uptimeSec) + " seconds before rebooting";
            mainHandler.post(() ->
                    Toast.makeText(mApplicationContext, waitMessage, Toast.LENGTH_LONG).show());
        }
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
