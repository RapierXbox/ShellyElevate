package me.rapierxbox.shellyelevatev2.mqtt;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.os.SystemClock;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.helper.ThermalZoneReader;

public class MQTTServer {

    private MqttClient mMqttClient;
    private final MemoryPersistence mMemoryPersistence;
    private final ShellyElevateMQTTCallback mShellyElevateMQTTCallback;
    private final MqttConnectionOptions mMqttConnectionsOptions;
    private final ScheduledExecutorService scheduler;
    private volatile boolean periodicScheduled = false;
    private String clientId;
    private boolean validForConnection;
    private volatile boolean connecting = false;
    private volatile int lastPublishedBrightness = Integer.MIN_VALUE;
    private volatile long lastBrightnessSentAtMs = 0L;
    private static final long MIN_BRIGHTNESS_PUBLISH_INTERVAL_MS = 500;

    // Lightweight coalescing for bursty publishes (switches/buttons/relays)
    private static final long COALESCE_WINDOW_MS = 40L;
    private final Object coalesceLock = new Object();
    private final java.util.HashMap<String, String> pendingPayloads = new java.util.HashMap<>();
    private final java.util.HashMap<String, Integer> pendingQos = new java.util.HashMap<>();
    private final java.util.HashMap<String, Boolean> pendingRetained = new java.util.HashMap<>();
    private volatile boolean flushScheduled = false;

    public MQTTServer() {
        mMemoryPersistence = new MemoryPersistence();
        mShellyElevateMQTTCallback = new ShellyElevateMQTTCallback();
        mMqttConnectionsOptions = new MqttConnectionOptions();
        scheduler = Executors.newScheduledThreadPool(1);

        setupClientId();
        registerSettingsReceiver();

        checkCredsAndConnect();
    }

    private void setupClientId() {
        clientId = mSharedPreferences.getString(SP_MQTT_CLIENTID, "shellywalldisplay");
        if (clientId.equals("shellyelevate") || clientId.equals("shellywalldisplay") || clientId.length() <= 2) {
            clientId = "shellyelevate-" + UUID.randomUUID().toString().replaceAll("-", "").substring(2, 6);
            mSharedPreferences.edit().putString(SP_MQTT_CLIENTID, clientId).apply();
        }
    }

    private void registerSettingsReceiver() {
        BroadcastReceiver settingsChangedBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d("MQTT", "Settings changed - reconnecting with new config");
                // Disconnect existing connection before reconnecting with new settings
                reconnectWithNewSettings();
            }
        };
        LocalBroadcastManager.getInstance(mApplicationContext)
                .registerReceiver(settingsChangedBroadcastReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));
    }

    /**
     * Disconnect and reconnect with new settings.
     * Called when settings are changed via HTTP API or settings UI.
     */
    private void reconnectWithNewSettings() {
        scheduler.execute(() -> {
            try {
                // Disconnect existing client if connected
                if (mMqttClient != null && mMqttClient.isConnected()) {
                    Log.d("MQTT", "Disconnecting old MQTT connection before applying new settings");
                    try {
                        mMqttClient.disconnect();
                        mMqttClient.close();
                    } catch (MqttException e) {
                        Log.w("MQTT", "Error disconnecting during settings change", e);
                    }
                    mMqttClient = null;
                }
                
                // Update clientId from settings (mqttDeviceId)
                setupClientId();
                Log.d("MQTT", "Updated MQTT client ID to: " + clientId);
                
                // Small delay to ensure clean disconnection
                Thread.sleep(500);
                
                // Now check credentials and connect with new settings
                checkCredsAndConnect();
            } catch (InterruptedException e) {
                Log.e("MQTT", "Interrupted during reconnect", e);
                Thread.currentThread().interrupt();
            }
        });
    }

    private void schedulePeriodicTempHum() {
        if (periodicScheduled) return;
        scheduler.scheduleWithFixedDelay(this::publishTempAndHum, 0, 30, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::publishThermalZones, 5, 30, TimeUnit.SECONDS);
        periodicScheduled = true;
    }

    public void checkCredsAndConnect() {
        if (!isEnabled()) {
            // If MQTT is disabled in settings, disconnect if connected
            if (mMqttClient != null && mMqttClient.isConnected()) {
                Log.d("MQTT", "MQTT disabled in settings - disconnecting");
                disconnect();
            }
            return;
        }

        validForConnection =
                !mSharedPreferences.getString(SP_MQTT_PASSWORD, "").isEmpty() &&
                        !mSharedPreferences.getString(SP_MQTT_USERNAME, "").isEmpty() &&
                        !mSharedPreferences.getString(SP_MQTT_BROKER, "").isEmpty();

        if (!validForConnection) {
            Log.w("MQTT", "Invalid connection credentials - broker, username, or password missing");
            return;
        }

        schedulePeriodicTempHum();

        connect();
    }

    public void connect() {
        if (!validForConnection || connecting || (mMqttClient != null && mMqttClient.isConnected())) return;

        connecting = true;
        Log.d("MQTT", "Connecting...");
        scheduler.execute(this::doConnect);
    }

    private void doConnect() {
        if (mMqttClient != null && mMqttClient.isConnected()) return;

        try {
            mMqttConnectionsOptions.setUserName(mSharedPreferences.getString(SP_MQTT_USERNAME, ""));
            mMqttConnectionsOptions.setPassword(mSharedPreferences.getString(SP_MQTT_PASSWORD, "").getBytes());
            mMqttConnectionsOptions.setAutomaticReconnect(true);
            mMqttConnectionsOptions.setConnectionTimeout(5);
            mMqttConnectionsOptions.setCleanStart(true);

            mMqttClient = new MqttClient(
                mSharedPreferences.getString(SP_MQTT_BROKER, "") + ":" + mSharedPreferences.getInt(SP_MQTT_PORT, 1883),
                clientId, mMemoryPersistence
            );

            // Set callback only once
            mMqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    Log.i("MQTT", "Connected to " + serverURI + ", reconnect: " + reconnect);
                    connecting = false;
                    safeOnConnected();
                }

                @Override
                public void disconnected(MqttDisconnectResponse disconnectResponse) {
                    Log.w("MQTT", "Disconnected: " + disconnectResponse.getReasonString());
                    connecting = false;
                    // automatically handled by reconnect
                }

                @Override
                public void mqttErrorOccurred(MqttException exception) {
                    Log.e("MQTT", "MQTT error occurred", exception);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    mShellyElevateMQTTCallback.messageArrived(topic, message);
                }

                @Override
                public void deliveryComplete(IMqttToken token) {}

                @Override
                public void authPacketArrived(int reasonCode, MqttProperties properties) {}
            });

            // LWT
            MqttMessage lwtMessage = new MqttMessage("offline".getBytes());
            lwtMessage.setQos(1);
            lwtMessage.setRetained(true);
            mMqttConnectionsOptions.setWill(parseTopic(MQTT_TOPIC_STATUS), lwtMessage);

            mMqttClient.connect(mMqttConnectionsOptions);
        } catch (MqttException e) {
            Log.e("MQTT", "Connect failed, scheduling retry in 60s: ", e);
            connecting = false;
            scheduler.schedule(this::connect, 60, TimeUnit.SECONDS);
        }
    }

    private void safeOnConnected() {
        scheduler.schedule(() -> {
            if (mMqttClient != null && mMqttClient.isConnected()) {
                try {
                    // Subscriptions
                    mMqttClient.subscribe("shellyelevatev2/#", 1);
                    mMqttClient.subscribe(MQTT_TOPIC_HOME_ASSISTANT_STATUS, 1);

                    publishStatus();
                } catch (Exception e) {
                    Log.e("MQTT", "onConnected error", e);
                }
            }
        }, 150, TimeUnit.MILLISECONDS);
    }

    public void publishStatus() {
        if (mMqttClient == null || !mMqttClient.isConnected()) return;

        scheduler.execute(() -> {
            try {
                // Publish hello info
                publishHello();

                // Publish config
                publishConfig();

                // Publish online status last
                publishInternal(parseTopic(MQTT_TOPIC_STATUS), "online", 1, true);

                // Stagger sensor publishes; consolidate Runnable allocations
                scheduler.schedule(this::publishTempAndHum, 50, TimeUnit.MILLISECONDS);
                
                // Batch relay publishes to reduce lambda allocations
                scheduler.schedule(() -> {
                    for (int num = 0; num < DeviceModel.getReportedDevice().inputs; num++) {
                        publishRelay(num, mDeviceHelper.getRelay(num));
                    }
                }, 100, TimeUnit.MILLISECONDS);
                
                // Batch remaining sensor publishes
                scheduler.schedule(() -> {
                    publishLux(mDeviceSensorManager.getLastMeasuredLux());
                    publishScreenBrightness(mDeviceHelper.getScreenBrightness());
                    if (DeviceModel.getReportedDevice().hasProximitySensor) {
                        publishProximity(mDeviceSensorManager.getLastMeasuredDistance());
                    }
                    publishSleeping(mScreenSaverManager.isScreenSaverRunning());
                }, 150, TimeUnit.MILLISECONDS);

                scheduler.schedule(this::publishThermalZones, 2, TimeUnit.SECONDS);

            } catch (Exception e) {
                Log.e("MQTT", "publishStatus failed", e);
            }
        });
    }

    public void disconnect() {
        Log.d("MQTT", "Disconnecting");
        if (mMqttClient != null && mMqttClient.isConnected()) {
            try {
                deleteConfig();
                mMqttClient.publish(parseTopic(MQTT_TOPIC_STATUS), "offline".getBytes(), 1, true);
                mMqttClient.disconnect();
            } catch (MqttException e) {
                Log.e("MQTT", "Error disconnecting MQTT client", e);
            }
        }
    }

    public boolean isEnabled() {
        return mSharedPreferences.getBoolean(SP_MQTT_ENABLED, false);
    }

    public boolean shouldSend() {
        return isEnabled() && mMqttClient != null && mMqttClient.isConnected();
    }

    public void publishInternal(String topic, String payload, int qos, boolean retained) {
        if (scheduler.isShutdown()) return;
        scheduler.execute(() -> publishInternalSync(topic, payload, qos, retained));
    }

    private void publishInternalCoalesced(String topic, String payload, int qos, boolean retained) {
        if (scheduler.isShutdown()) return;
        synchronized (coalesceLock) {
            pendingPayloads.put(topic, payload);
            pendingQos.put(topic, qos);
            pendingRetained.put(topic, retained);
            if (!flushScheduled) {
                flushScheduled = true;
                scheduler.schedule(this::flushPendingPublishes, COALESCE_WINDOW_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void flushPendingPublishes() {
        java.util.Map<String, String> toSend;
        java.util.Map<String, Integer> qosMap;
        java.util.Map<String, Boolean> retainedMap;
        synchronized (coalesceLock) {
            toSend = new java.util.HashMap<>(pendingPayloads);
            qosMap = new java.util.HashMap<>(pendingQos);
            retainedMap = new java.util.HashMap<>(pendingRetained);
            pendingPayloads.clear();
            pendingQos.clear();
            pendingRetained.clear();
            flushScheduled = false;
        }

        if (toSend.isEmpty()) return;
        if (!shouldSend()) return;

        // Publish all pending in the scheduler thread to avoid excess context switches
        for (java.util.Map.Entry<String, String> e : toSend.entrySet()) {
            String topic = e.getKey();
            String payload = e.getValue();
            int qos = qosMap.getOrDefault(topic, 1);
            boolean retained = retainedMap.getOrDefault(topic, false);
            publishInternalSync(topic, payload, qos, retained);
        }
    }

    private void publishInternalSync(String topic, String payload, int qos, boolean retained) {
        if (!shouldSend()) {
            Log.w("MQTT", "publishInternal skipped — client not connected: " + topic);
            return;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes());
            message.setQos(qos);
            message.setRetained(retained);
            mMqttClient.publish(topic, message);
        } catch (MqttException e) {
            Log.e("MQTT", "Failed to publish to " + topic, e);
        }
    }

    public void publishTempAndHum() {
        float temp = (float) mDeviceHelper.getTemperature();
        float hum = (float) mDeviceHelper.getHumidity();
        // Batch both publishes in one executor call to reduce thread handoffs
        if (temp != -999 || hum != -999) {
            scheduler.execute(() -> {
                if (temp != -999) publishInternal(parseTopic(MQTT_TOPIC_TEMP_SENSOR), String.valueOf(temp), 1, false);
                if (hum != -999) publishInternal(parseTopic(MQTT_TOPIC_HUM_SENSOR), String.valueOf(hum), 1, false);
            });
        }
    }

    public void publishTemp(float temp) {
        if (temp == -999) return;
        publishInternal(parseTopic(MQTT_TOPIC_TEMP_SENSOR), String.valueOf(temp), 1, false);
    }

    public void publishHum(float hum) {
        if (hum == -999) return;
        publishInternal(parseTopic(MQTT_TOPIC_HUM_SENSOR), String.valueOf(hum), 1, false);
    }

    public void publishLux(float lux) {
        publishInternal(parseTopic(MQTT_TOPIC_LUX_SENSOR), String.valueOf(lux), 1, false);
    }

    public void publishScreenBrightness(int brightness) {
        long now = SystemClock.elapsedRealtime();

        synchronized (this) {
            if (brightness == lastPublishedBrightness && (now - lastBrightnessSentAtMs) < MIN_BRIGHTNESS_PUBLISH_INTERVAL_MS) {
                return;
            }
            lastPublishedBrightness = brightness;
            lastBrightnessSentAtMs = now;
        }

        publishInternal(parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS), String.valueOf(brightness), 1, false);
    }
    public void publishProximity(float distance) {
        publishInternal(parseTopic(MQTT_TOPIC_PROXIMITY_SENSOR), String.valueOf(distance), 1, false);
    }

    public void publishRelay(int num, boolean state) {
        var mqttSuffix = (num >0 ? ("_" + num): "");
        publishInternalCoalesced(parseTopic(MQTT_TOPIC_RELAY_STATE) + mqttSuffix, state ? "ON" : "OFF", 1, false);
    }

    public void publishSwitch(int num, boolean state) {
        var mqttSuffix = (num >0 ? ("_" + num): "");
        publishInternalCoalesced(parseTopic(MQTT_TOPIC_BUTTON_STATE) + mqttSuffix, state?"PRESS":"RELEASE", 1, false);
    }

    public void publishSleeping(boolean state) {
        publishInternal(parseTopic(MQTT_TOPIC_SLEEPING_BINARY_SENSOR), state ? "ON" : "OFF", 1, false);
    }

    /**
     * Publish a button press event with press type (short, long, double, triple).
     * For power button (ID 140), publishes to MQTT_TOPIC_POWER_BUTTON; for others to MQTT_TOPIC_BUTTON_STATE.
     */
    public void publishButton(int number, String pressType) {
        long epochMillis = System.currentTimeMillis();
        JSONObject json = new JSONObject();
        try {
            json.put("last_update", epochMillis);
            json.put("press_type", pressType);
            // Add event_type for Home Assistant MQTT event standard
            json.put("event_type", pressType);
        } catch (Exception e) {
            Log.e("MQTT", "Error creating button JSON", e);
        }

        String topic;
        if (number == 140) {
            // Power button has its own dedicated topic
            topic = parseTopic(MQTT_TOPIC_POWER_BUTTON);
        } else {
            // Regular buttons (0-3)
            topic = parseTopic(MQTT_TOPIC_BUTTON_STATE) + "/" + number;
        }

        publishInternalCoalesced(topic, json.toString(), 1, false);
    }

    /**
     * Legacy method for backward compatibility - assumes short press type.
     */
    @Deprecated
    public void publishButton(int number) {
        publishButton(number, BUTTON_PRESS_TYPE_SHORT);
    }

    public void publishSwipeEvent() {
        publishInternal(parseTopic(MQTT_TOPIC_SWIPE_EVENT), "{\"event_type\": \"swipe\"}", 1, false);
    }

    public void publishHello() {
        if (!shouldSend()) return;
        try {
            JSONObject json = new JSONObject();
            json.put("name", mApplicationContext.getPackageName());

            String version = "unknown";
            try {
                PackageInfo pInfo = mApplicationContext.getPackageManager()
                        .getPackageInfo(mApplicationContext.getPackageName(), 0);
                version = pInfo.versionName;
            } catch (PackageManager.NameNotFoundException ignored) {}

            json.put("version", version);
            json.put("startTime", getApplicationStartTime());
            json.put("buildType", BuildConfig.BUILD_TYPE);
            var device = DeviceModel.getReportedDevice();
            json.put("modelName", device.name());
            json.put("proximity", device.hasProximitySensor ? "true" : "false");

            publishInternal(parseTopic(MQTT_TOPIC_HELLO), json.toString(), 1, false);
        } catch (JSONException e) {
            Log.e("MQTT", "Error publishing hello", e);
        }
    }

    private JSONObject createButtonEventConfig(String name, String stateTopic, String uniqueId) throws JSONException {
        JSONObject eventPayload = new JSONObject();
        eventPayload.put("p", "event");
        eventPayload.put("name", name);
        eventPayload.put("state_topic", stateTopic);
        eventPayload.put("device_class", "button");
        eventPayload.put("event_types", new JSONArray()
                .put(BUTTON_PRESS_TYPE_SHORT)
                .put(BUTTON_PRESS_TYPE_LONG)
                .put(BUTTON_PRESS_TYPE_DOUBLE)
                .put(BUTTON_PRESS_TYPE_TRIPLE));
        eventPayload.put("unique_id", uniqueId);
        eventPayload.put("object_id", "shelly_walldisplay_" + uniqueId);
        return eventPayload;
    }

    private JSONObject createButtonTimestampConfig(String name, String stateTopic, String uniqueId) throws JSONException {
        JSONObject sensorPayload = new JSONObject();
        sensorPayload.put("p", "sensor");
        sensorPayload.put("name", name);
        sensorPayload.put("state_topic", stateTopic);
        sensorPayload.put("unique_id", uniqueId);
        sensorPayload.put("object_id", "shelly_walldisplay_" + uniqueId);
        sensorPayload.put("device_class", "timestamp");
        sensorPayload.put(
                "value_template",
                "{{ (value_json.last_update / 1000) | timestamp_custom('%Y-%m-%dT%H:%M:%S%z', true) }}"
        );
        return sensorPayload;
    }

    private void publishConfig() throws JSONException, MqttException {
        JSONObject configPayload = new JSONObject();

        DeviceModel deviceModel = DeviceModel.getReportedDevice();
        
        JSONObject device = new JSONObject();
        device.put("ids", clientId);
        device.put("name", deviceModel.friendlyName + " (" + clientId + ")" );
        device.put("mf", "Shelly");
        device.put("mdl", deviceModel.modelName);
        device.put("sw", BuildConfig.VERSION_NAME);
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
        tempSensorPayload.put("object_id", "shelly_walldisplay_" + clientId + "_temp");
        components.put(clientId + "_temp", tempSensorPayload);

        JSONObject humSensorPayload = new JSONObject();
        humSensorPayload.put("p", "sensor");
        humSensorPayload.put("name", "Humidity");
        humSensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_HUM_SENSOR));
        humSensorPayload.put("device_class", "humidity");
        humSensorPayload.put("unit_of_measurement", "%");
        humSensorPayload.put("unique_id", clientId + "_hum");
        humSensorPayload.put("object_id", "shelly_walldisplay_" + clientId + "_hum");
        components.put(clientId + "_hum", humSensorPayload);

        JSONObject luxSensorPayload = new JSONObject();
        luxSensorPayload.put("p", "sensor");
        luxSensorPayload.put("name", "Light");
        luxSensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_LUX_SENSOR));
        luxSensorPayload.put("device_class", "illuminance");
        luxSensorPayload.put("unit_of_measurement", "lx");
        luxSensorPayload.put("unique_id", clientId + "_lux");
        luxSensorPayload.put("object_id", "shelly_walldisplay_" + clientId + "_lux");
        components.put(clientId + "_lux", luxSensorPayload);

        if (DeviceModel.getReportedDevice().hasProximitySensor) {
            JSONObject proximitySensorPayload = new JSONObject();
            proximitySensorPayload.put("p", "sensor");
            proximitySensorPayload.put("name", "Proximity");
            proximitySensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_PROXIMITY_SENSOR));
            proximitySensorPayload.put("device_class", "distance");
            proximitySensorPayload.put("unit_of_measurement", "cm");
            proximitySensorPayload.put("unique_id", clientId + "_proximity");
            proximitySensorPayload.put("object_id", "shelly_walldisplay_" + clientId + "_proximity");
            components.put(clientId + "_proximity", proximitySensorPayload);
        }

        // power button (button 140) - only for V2 devices that have it
        if (DeviceModel.getReportedDevice().hasPowerButton) {
            String powerButtonTopic = parseTopic(MQTT_TOPIC_POWER_BUTTON);
            components.put(clientId + "_power_button", 
                    createButtonEventConfig("Power Button", powerButtonTopic, clientId + "_power_button"));
            components.put(clientId + "_power_button_lastpress", 
                    createButtonTimestampConfig("Power Button Last Press", powerButtonTopic, clientId + "_power_button_lastpress"));
        }

        // buttons
        var buttons = DeviceModel.getReportedDevice().buttons;
        if (buttons > 0) {
            for (int i = 0; i < buttons; i++) {
                String buttonTopic = parseTopic(MQTT_TOPIC_BUTTON_STATE) + "/" + i;
                String buttonId = clientId + "_button_" + i;
                
                components.put(buttonId, 
                        createButtonEventConfig("Button " + i, buttonTopic, buttonId));
                components.put(buttonId + "_lastpress", 
                        createButtonTimestampConfig("Button " + i + " Last Press", buttonTopic, buttonId + "_lastpress"));
            }
        }

        for (int num = 0; num < DeviceModel.getReportedDevice().inputs; num++) {
            String mqttSuffix = (num >0 ? ("_" + num): "");
            // relay
            JSONObject relaySwitchPayload = new JSONObject();
            relaySwitchPayload.put("p", "switch");
            relaySwitchPayload.put("name", ("Relay " + (num >0 ? (" " + num): "")).trim());
            relaySwitchPayload.put("state_topic", parseTopic(MQTT_TOPIC_RELAY_STATE) + mqttSuffix);
            relaySwitchPayload.put("command_topic", parseTopic(MQTT_TOPIC_RELAY_COMMAND) + mqttSuffix);
            relaySwitchPayload.put("device_class", "outlet");
            relaySwitchPayload.put("unique_id", clientId + "_relay" + (num >0 ? ("_" + num): ""));
            relaySwitchPayload.put("object_id", "shelly_walldisplay_" + clientId + "_relay" + (num >0 ? ("_" + num): ""));
            components.put(clientId + "_relay" + (num >0 ? ("_" + num): ""), relaySwitchPayload);

            JSONObject buttonPayload = new JSONObject();
            buttonPayload.put("p", "button");
            buttonPayload.put("name", ("Switch " + (num > 0 ? (" " + num) : "")).trim());
            buttonPayload.put("command_topic", parseTopic(MQTT_TOPIC_SWITCH_STATE) + mqttSuffix);
            buttonPayload.put("payload_press", "PRESS");
            buttonPayload.put("payload_release", "RELEASE");
            buttonPayload.put("value_template", "{{ value }}");
            buttonPayload.put("unique_id", clientId + "_switch" + (num > 0 ? ("_" + num) : ""));
            buttonPayload.put("object_id", "shelly_walldisplay_" + clientId + "_switch" + (num > 0 ? ("_" + num) : ""));
            buttonPayload.put("device_class", "restart"); // optional: or "none"
            components.put(clientId + "_switch" + (num > 0 ? ("_" + num) : ""), buttonPayload);
        }

        JSONObject sleepButtonPayload = new JSONObject();
        sleepButtonPayload.put("p", "button");
        sleepButtonPayload.put("name", "Sleep");
        sleepButtonPayload.put("command_topic", parseTopic(MQTT_TOPIC_SLEEP_BUTTON));
        sleepButtonPayload.put("unique_id", clientId + "_sleep");
        sleepButtonPayload.put("object_id", "shelly_walldisplay_" + clientId + "_sleep");
        components.put(clientId + "_sleep", sleepButtonPayload);

        JSONObject wakeButtonPayload = new JSONObject();
        wakeButtonPayload.put("p", "button");
        wakeButtonPayload.put("name", "Wake");
        wakeButtonPayload.put("command_topic", parseTopic(MQTT_TOPIC_WAKE_BUTTON));
        wakeButtonPayload.put("unique_id", clientId + "_wake");
        wakeButtonPayload.put("object_id", "shelly_walldisplay_" + clientId + "_wake");
        components.put(clientId + "_wake", wakeButtonPayload);

        JSONObject refreshWebviewButtonPayload = new JSONObject();
        refreshWebviewButtonPayload.put("p", "button");
        refreshWebviewButtonPayload.put("name", "Refresh Webview");
        refreshWebviewButtonPayload.put("command_topic", parseTopic(MQTT_TOPIC_REFRESH_WEBVIEW_BUTTON));
        refreshWebviewButtonPayload.put("device_class", "restart");
        refreshWebviewButtonPayload.put("unique_id", clientId + "_refresh_webview");
        refreshWebviewButtonPayload.put("object_id", "shelly_walldisplay_" + clientId + "_refresh_webview");
        components.put(clientId + "_refresh_webview", refreshWebviewButtonPayload);

        JSONObject rebootButtonPayload = new JSONObject();
        rebootButtonPayload.put("p", "button");
        rebootButtonPayload.put("name", "Reboot");
        rebootButtonPayload.put("command_topic", parseTopic(MQTT_TOPIC_REBOOT_BUTTON));
        rebootButtonPayload.put("device_class", "restart");
        rebootButtonPayload.put("unique_id", clientId + "_reboot");
        rebootButtonPayload.put("object_id", "shelly_walldisplay_" + clientId + "_reboot");
        components.put(clientId + "_reboot", rebootButtonPayload);

        JSONObject swipeEventPayload = new JSONObject();
        swipeEventPayload.put("p", "event");
        swipeEventPayload.put("name", "Swipe Event");
        swipeEventPayload.put("state_topic", parseTopic(MQTT_TOPIC_SWIPE_EVENT));
        swipeEventPayload.put("device_class", "button");
        swipeEventPayload.put("event_types", new JSONArray().put("swipe"));
        swipeEventPayload.put("unique_id", clientId + "_swipe_event");
        swipeEventPayload.put("object_id", "shelly_walldisplay_" + clientId + "_swipe_event");
        components.put(clientId + "_swipe_event", swipeEventPayload);

        JSONObject sleepingBinarySensorPayload = new JSONObject();
        sleepingBinarySensorPayload.put("p", "binary_sensor");
        sleepingBinarySensorPayload.put("name", "Sleeping");
        sleepingBinarySensorPayload.put("state_topic", parseTopic(MQTT_TOPIC_SLEEPING_BINARY_SENSOR));
        sleepingBinarySensorPayload.put("unique_id", clientId + "_sleeping");
        sleepingBinarySensorPayload.put("object_id", "shelly_walldisplay_" + clientId + "_sleeping");
        components.put(clientId + "_sleeping", sleepingBinarySensorPayload);

        if (mSharedPreferences.getBoolean(SP_PUBLISH_THERMAL_SENSORS, false)) {
            for (ThermalZoneReader.Zone zone : ThermalZoneReader.discoverZones()) {
                String zoneId = clientId + "_thermal_" + zone.type;
                JSONObject thermalPayload = new JSONObject();
                thermalPayload.put("p", "sensor");
                thermalPayload.put("name", "Thermal " + zone.type.replace("_", " "));
                thermalPayload.put("state_topic", String.format(MQTT_TOPIC_THERMAL_ZONE, clientId, zone.type));
                thermalPayload.put("device_class", "temperature");
                thermalPayload.put("unit_of_measurement", "°C");
                thermalPayload.put("state_class", "measurement");
                thermalPayload.put("unique_id", zoneId);
                thermalPayload.put("object_id", "shelly_walldisplay_" + zoneId);
                components.put(zoneId, thermalPayload);
            }
        }

        // TODO: brightness as both state and control

        configPayload.put("cmps", components);

        configPayload.put("state_topic", MQTT_TOPIC_STATUS);

        mMqttClient.publish(parseTopic(MQTT_TOPIC_CONFIG_DEVICE), configPayload.toString().getBytes(), 1, true);
    }

    private void publishThermalZones() {
        if (!mSharedPreferences.getBoolean(SP_PUBLISH_THERMAL_SENSORS, false)) return;
        for (ThermalZoneReader.Zone z : ThermalZoneReader.discoverZones()) {
            Float t = ThermalZoneReader.readZoneTempC(z);
            if (t == null) continue;
            String topic = String.format(MQTT_TOPIC_THERMAL_ZONE, clientId, z.type);
            publishInternal(topic, String.valueOf(Math.round(t * 10f) / 10f), 1, false);
        }
    }

    private void deleteConfig() throws MqttException {
        mMqttClient.publish(parseTopic(MQTT_TOPIC_CONFIG_DEVICE), "".getBytes(), 1, false);
    }

    private String parseTopic(String topic) {
        return topic.replace("%s", clientId);
    }

    public String getClientId() {
        return clientId;
    }

    public void onDestroy() {
        disconnect();
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdown();
    }
}
