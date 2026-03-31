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
        // Keep the connected flag accurate so shouldSend() returns false until
        // Paho reconnects and connectComplete fires (issue #35).
        mMQTTServer.setConnected(false);
        Toast.makeText(mApplicationContext, "MQTT disconnected", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void mqttErrorOccurred(MqttException exception) {
        Log.e("MQTT", "Error occurred: " + exception);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        switch (topic.replace(mMQTTServer.getClientId(), "%s")) {
            case MQTT_TOPIC_RELAY_COMMAND:
                mDeviceHelper.setRelay(new String(message.getPayload(), StandardCharsets.UTF_8).contains("ON"));
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
        }
    }

    @Override
    public void deliveryComplete(IMqttToken token) {

    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        Log.i("MQTT", "Connected to: " + serverURI);
        if (reconnect) {
            // A clean-session reconnect drops all broker-side subscriptions, so we
            // must re-subscribe and republish the HA discovery config + device state.
            // The connected flag must be set first so shouldSend() returns true
            // for the publish calls inside onReconnected() (issue #35).
            mMQTTServer.setConnected(true);
            mMQTTServer.onReconnected();
        }
    }

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {

    }
}
