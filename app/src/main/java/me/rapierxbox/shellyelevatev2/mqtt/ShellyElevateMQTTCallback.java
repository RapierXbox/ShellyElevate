package me.rapierxbox.shellyelevatev2.mqtt;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager;

import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import me.rapierxbox.shellyelevatev2.ShellyElevateApplication;

public class ShellyElevateMQTTCallback implements MqttCallback {
    @Override
    public void disconnected(MqttDisconnectResponse disconnectResponse) {
        Log.i("MQTT", "Disconnected");
        Toast.makeText(mApplicationContext, "MQTT disconnected", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void mqttErrorOccurred(MqttException exception) {
        Log.e("MQTT", "Error occurred: " + exception);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        if (MQTT_TOPIC_UPDATE_GENERIC.equals(topic)) {
            mMQTTServer.publishStatus();
            return;
        }
        switch (topic.replace(mMQTTServer.getClientId(), "%s")) {
            case MQTT_TOPIC_UPDATE:
	            mMQTTServer.publishStatus();
	            break;
            case MQTT_TOPIC_HOME_ASSISTANT_STATUS:
                // Republish discovery config when Home Assistant comes online
                String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                if ("online".equals(payload)) {
                    Log.i("MQTT", "Home Assistant online, republishing discovery");
                    mMQTTServer.publishStatus();
                }
                break;
                // IMPRV: new logic to handle infinite relays
            case MQTT_TOPIC_RELAY_COMMAND:
                mDeviceHelper.setRelay(0, new String(message.getPayload(), StandardCharsets.UTF_8).contains("ON"));
                break;
            case MQTT_TOPIC_RELAY_COMMAND + "_1":
                mDeviceHelper.setRelay(1, new String(message.getPayload(), StandardCharsets.UTF_8).contains("ON"));
                break;
            case MQTT_TOPIC_REFRESH_WEBVIEW_BUTTON:
                Intent intent = new Intent(INTENT_SETTINGS_CHANGED);
                LocalBroadcastManager.getInstance(ShellyElevateApplication.mApplicationContext).sendBroadcast(intent);
                break;
            case MQTT_TOPIC_SLEEP_BUTTON:
                mScreenSaverManager.startScreenSaver();
                break;
            case MQTT_TOPIC_WAKE_BUTTON:
                mScreenSaverManager.stopScreenSaver();
                break;
            case MQTT_TOPIC_REBOOT_BUTTON:
                long deltaTime = System.currentTimeMillis() - ShellyElevateApplication.getApplicationStartTime();
                deltaTime /= 1000;
                if (deltaTime > 20) {
                    try {
                        Runtime.getRuntime().exec("reboot");
                    } catch (IOException e) {
                        Log.e("MQTT", "Error rebooting:", e);
                    }
                } else {
                    Toast.makeText(mApplicationContext, "Please wait %s seconds before rebooting".replace("%s",String.valueOf(20-deltaTime) ), Toast.LENGTH_LONG).show();
                }
                break;
            case MQTT_TOPIC_POWER_BUTTON:
                // Power button commands from Home Assistant
                // Payload is JSON with press_type field. No default action on incoming power button;
                // power button behavior is configured on the device (auto-reboot on long press).
                Log.i("MQTT", "Power button event received from HA: " + new String(message.getPayload(), StandardCharsets.UTF_8));
                break;
        }
    }

    @Override
    public void deliveryComplete(IMqttToken token) {

    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        Log.i("MQTT", "Connected to: " + serverURI);
    }

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {

    }
}
