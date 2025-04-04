package me.rapierxbox.shellyelevatev2.mqtt;

import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_CONFIG_DEVICE;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_HOME_ASSISTANT_STATUS;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_HUM_SENSOR;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_LUX_SENSOR;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_REBOOT_BUTTON;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_REFRESH_WEBVIEW_BUTTON;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_RELAY_COMMAND;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_RELAY_STATE;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_SLEEPING_BINARY_SENSOR;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_SLEEP_BUTTON;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_STATUS;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_SWIPE_EVENT;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_TEMP_SENSOR;
import static me.rapierxbox.shellyelevatev2.Constants.MQTT_TOPIC_WAKE_BUTTON;
import static me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_BROKER;
import static me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_DEVICE_ID;
import static me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_ENABLED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_PASSWORD;
import static me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_PORT;
import static me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_USERNAME;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.util.Log;

import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManagerHolder;

public class MQTTServer {
    private MqttClient mMqttClient;
    private final MemoryPersistence mMemoryPersistence;
    private final ShellyElevateMQTTCallback mShellyElevateMQTTCallback;
    private final MqttConnectionOptions mMqttConnectionsOptions;
    private final ScheduledExecutorService scheduler;

    private boolean enabled;
    private boolean connected;
    private byte[] password;
    private String username;
    private String broker;
    private String clientId;
    private int port;

    private boolean validForConnection;

    public MQTTServer() {
        mMemoryPersistence = new MemoryPersistence();
        mShellyElevateMQTTCallback = new ShellyElevateMQTTCallback();
        mMqttConnectionsOptions = new MqttConnectionOptions();

        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleWithFixedDelay(this::publishTempAndHum, 0, 5, TimeUnit.SECONDS);

        connected = false;

        clientId = mSharedPreferences.getString(SP_MQTT_DEVICE_ID, "shellywalldisplay");
        if (clientId.equals("shellyelevate") || clientId.equals("shellywalldisplay") || clientId.length() <= 2) {
            clientId = "shellyelevate-" + UUID.randomUUID().toString().replaceAll("-", "").substring(2, 6);
            mSharedPreferences.edit().putString(SP_MQTT_DEVICE_ID, clientId).apply();
        }

        updateValues();
    }

    public void updateValues() {
        password = mSharedPreferences.getString(SP_MQTT_PASSWORD, "").getBytes();
        username = mSharedPreferences.getString(SP_MQTT_USERNAME, "");
        broker = mSharedPreferences.getString(SP_MQTT_BROKER, "");
        port = mSharedPreferences.getInt(SP_MQTT_PORT, 1883);
        clientId = mSharedPreferences.getString(SP_MQTT_DEVICE_ID, "shellywalldisplay");
        enabled = mSharedPreferences.getBoolean(SP_MQTT_ENABLED, false);

        validForConnection = password.length > 0 && !username.isEmpty() && !broker.isEmpty();

        connect();
    }

    public void disconnect() {
        if (mMqttClient != null && mMqttClient.isConnected()) {
            try {
                deleteConfig();
                mMqttClient.publish(parseTopic(MQTT_TOPIC_STATUS), "offline".getBytes(), 1, true);
                mMqttClient.disconnect();

                connected = false;
            } catch (MqttException e) {
                Log.e("MQTT", "Error disconnecting MQTT client", e);
            }
        }
    }

    public void connect() {
        if (validForConnection) {
            try {
                mMqttConnectionsOptions.setUserName(username);
                mMqttConnectionsOptions.setPassword(password);

                if (connected) {
                    disconnect();
                }

                mMqttClient = new MqttClient(broker + ":" + port, clientId, mMemoryPersistence);
                mMqttClient.setCallback(mShellyElevateMQTTCallback);
                mMqttClient.connect(mMqttConnectionsOptions);

                publishConfig();

                mMqttClient.publish(parseTopic(MQTT_TOPIC_STATUS), "online".getBytes(), 1, true);

                mMqttClient.subscribe("shellyelevatev2/#", 0);
                mMqttClient.subscribe("shellyelevatev2/#", 1);
                mMqttClient.subscribe("shellyelevatev2/#", 2);

                mMqttClient.subscribe(MQTT_TOPIC_HOME_ASSISTANT_STATUS, 0);
                mMqttClient.subscribe(MQTT_TOPIC_HOME_ASSISTANT_STATUS, 1);
                mMqttClient.subscribe(MQTT_TOPIC_HOME_ASSISTANT_STATUS, 2);

                connected = true;

                publishTempAndHum();
                publishRelay(mDeviceHelper.getRelay());
                publishLux(mDeviceSensorManager.getLastMeasuredLux());
                publishSleeping(ScreenSaverManagerHolder.getInstance().isScreenSaverRunning());

            } catch (MqttException | JSONException e) {
                Log.e("MQTT", "Error connecting:", e);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean shouldSend() {
        return connected && enabled;
    }

    public void publishTempAndHum() {
        if (this.shouldSend()) {
            this.publishTemp((float) mDeviceHelper.getTemperature());
            this.publishHum((float) mDeviceHelper.getHumidity());
        }
    }

    public void publishTemp(float temp) {
        try {
            mMqttClient.publish(parseTopic(MQTT_TOPIC_TEMP_SENSOR), String.valueOf(temp).getBytes(), 1, false);
        } catch (MqttException e) {
            Log.e("MQTT", "Error publishing temperature", e);
        }
    }

    public void publishHum(float hum) {
        try {
            mMqttClient.publish(parseTopic(MQTT_TOPIC_HUM_SENSOR), String.valueOf(hum).getBytes(), 1, false);
        } catch (MqttException e) {
            Log.e("MQTT", "Error publishing humidity", e);
        }
    }

    public void publishLux(float lux) {
        try {
            mMqttClient.publish(parseTopic(MQTT_TOPIC_LUX_SENSOR), String.valueOf(lux).getBytes(), 1, false);
        } catch (MqttException e) {
            Log.e("MQTT", "Error publishing lux", e);
        }
    }

    public void publishRelay(boolean state) {
        try {
            mMqttClient.publish(parseTopic(MQTT_TOPIC_RELAY_STATE), (state ? "ON" : "OFF").getBytes(), 1, false);
        } catch (MqttException e) {
            Log.e("MQTT", "Error publishing relay state", e);
        }
    }

    public void publishSleeping(boolean state) {
        try {
            mMqttClient.publish(parseTopic(MQTT_TOPIC_SLEEPING_BINARY_SENSOR), (state ? "ON" : "OFF").getBytes(), 1, false);
        } catch (MqttException e) {
            Log.e("MQTT", "Error publishing sleeping state", e);
        }
    }

    public void publishSwipeEvent() {
        try {
            mMqttClient.publish(parseTopic(MQTT_TOPIC_SWIPE_EVENT), "{\"event_type\": \"swipe\"}".getBytes(), 1, false);
        } catch (MqttException e) {
            Log.e("MQTT", "Error publishing swipe event", e);
        }
    }

    private void deleteConfig() throws MqttException {
        mMqttClient.publish(parseTopic(MQTT_TOPIC_CONFIG_DEVICE), "".getBytes(), 1, false);
    }

    private void publishConfig() throws JSONException, MqttException {
        JSONObject configPayload = new JSONObject();

        JSONObject device = new JSONObject();
        device.put("ids", clientId);
        device.put("name", "Shelly Wall Display");
        device.put("mf", "Shelly");
        configPayload.put("dev", device);

        JSONObject origin = new JSONObject();
        origin.put("name", "ShellyElevateV2");
        origin.put("url", "https://github.com/RapierXbox/ShellyElevate");
        configPayload.put("o", origin);

        JSONObject components = new JSONObject();

        JSONObject tempSensorPayload = new JSONObject();
        tempSensorPayload.put("p", "sensor");
        tempSensorPayload.put("name", "Temperature");
        tempSensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_TEMP_SENSOR));
        tempSensorPayload.put("device_class", "temperature");
        tempSensorPayload.put("unit_of_measurement", "°C");
        tempSensorPayload.put("unique_id", clientId + "_temp");
        components.put(clientId + "_temp", tempSensorPayload);

        JSONObject humSensorPayload = new JSONObject();
        humSensorPayload.put("p", "sensor");
        humSensorPayload.put("name", "Humidity");
        humSensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_HUM_SENSOR));
        humSensorPayload.put("device_class", "humidity");
        humSensorPayload.put("unit_of_measurement", "%");
        humSensorPayload.put("unique_id", clientId + "_hum");
        components.put(clientId + "_hum", humSensorPayload);

        JSONObject luxSensorPayload = new JSONObject();
        luxSensorPayload.put("p", "sensor");
        luxSensorPayload.put("name", "Light");
        luxSensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_LUX_SENSOR));
        luxSensorPayload.put("device_class", "illuminance");
        luxSensorPayload.put("unit_of_measurement", "lx");
        luxSensorPayload.put("unique_id", clientId + "_lux");
        components.put(clientId + "_lux", luxSensorPayload);

        JSONObject relaySwitchPayload = new JSONObject();
        relaySwitchPayload.put("p", "switch");
        relaySwitchPayload.put("name", "Relay");
        relaySwitchPayload.put("state_topic", parseTopic(MQTT_TOPIC_RELAY_STATE));
        relaySwitchPayload.put("command_topic", parseTopic(MQTT_TOPIC_RELAY_COMMAND));
        relaySwitchPayload.put("device_class", "outlet");
        relaySwitchPayload.put("unique_id", clientId + "_relay");
        components.put(clientId + "_relay", relaySwitchPayload);

        JSONObject sleepButtonPayload = new JSONObject();
        sleepButtonPayload.put("p", "button");
        sleepButtonPayload.put("name", "Sleep");
        sleepButtonPayload.put("command_topic", parseTopic(MQTT_TOPIC_SLEEP_BUTTON));
        sleepButtonPayload.put("unique_id", clientId + "_sleep");
        components.put(clientId + "_sleep", sleepButtonPayload);

        JSONObject wakeButtonPayload = new JSONObject();
        wakeButtonPayload.put("p", "button");
        wakeButtonPayload.put("name", "Wake");
        wakeButtonPayload.put("command_topic", parseTopic(MQTT_TOPIC_WAKE_BUTTON));
        wakeButtonPayload.put("unique_id", clientId + "_wake");
        components.put(clientId + "_wake", wakeButtonPayload);

        JSONObject refreshWebviewButtonPayload = new JSONObject();
        refreshWebviewButtonPayload.put("p", "button");
        refreshWebviewButtonPayload.put("name", "Refresh Webview");
        refreshWebviewButtonPayload.put("command_topic", parseTopic(MQTT_TOPIC_REFRESH_WEBVIEW_BUTTON));
        refreshWebviewButtonPayload.put("device_class", "restart");
        refreshWebviewButtonPayload.put("unique_id", clientId + "_refresh_webview");
        components.put(clientId + "_refresh_webview", refreshWebviewButtonPayload);

        JSONObject rebootButtonPayload = new JSONObject();
        rebootButtonPayload.put("p", "button");
        rebootButtonPayload.put("name", "Reboot");
        rebootButtonPayload.put("command_topic", parseTopic(MQTT_TOPIC_REBOOT_BUTTON));
        rebootButtonPayload.put("device_class", "restart");
        rebootButtonPayload.put("unique_id", clientId + "_reboot");
        components.put(clientId + "_reboot", rebootButtonPayload);

        JSONObject swipeEventPayload = new JSONObject();
        swipeEventPayload.put("p", "event");
        swipeEventPayload.put("name", "Swipe Event");
        swipeEventPayload.put("state_topic", parseTopic(MQTT_TOPIC_SWIPE_EVENT));
        swipeEventPayload.put("device_class", "button");
        swipeEventPayload.put("event_types", new JSONArray().put("swipe"));
        swipeEventPayload.put("unique_id", clientId + "_swipe_event");
        components.put(clientId + "_swipe_event", swipeEventPayload);

        JSONObject sleepingBinarySensorPayload = new JSONObject();
        sleepingBinarySensorPayload.put("p", "binary_sensor");
        sleepingBinarySensorPayload.put("name", "Sleeping");
        sleepingBinarySensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_SLEEPING_BINARY_SENSOR));
        sleepingBinarySensorPayload.put("unique_id", clientId + "_sleeping");
        components.put(clientId + "_sleeping", sleepingBinarySensorPayload);

        configPayload.put("cmps", components);

        configPayload.put("state_topic", MQTT_TOPIC_STATUS);

        mMqttClient.publish(parseTopic(MQTT_TOPIC_CONFIG_DEVICE), configPayload.toString().getBytes(), 1, true);
    }

    private String parseTopic(String topic) {
        return topic.replace("%s", clientId);
    }

    public String getClientId() {
        return clientId;
    }

    public void onDestroy() {
        disconnect();

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }
}
