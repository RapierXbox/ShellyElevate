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
    private java.util.HashMap<String, Pending> pending = new java.util.HashMap<>();
    private volatile boolean flushScheduled = false;

    private static final class Pending {
        final String payload;
        final int qos;
        final boolean retained;
        Pending(String payload, int qos, boolean retained) {
            this.payload = payload; this.qos = qos; this.retained = retained;
        }
    }

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
        scheduler.scheduleWithFixedDelay(() -> {
            publishTempAndHum();
            publishThermalZones();
        }, 0, 30, TimeUnit.SECONDS);
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
            mMqttConnectionsOptions.setAutomaticReconnect(false);
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
                    if (!scheduler.isShutdown() && isEnabled() && validForConnection) {
                        scheduler.schedule(MQTTServer.this::connect, 5, TimeUnit.SECONDS);
                    }
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
                    for (int num = 0; num < DeviceModel.getReportedDevice().relays; num++) {
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
            pending.put(topic, new Pending(payload, qos, retained));
            if (!flushScheduled) {
                flushScheduled = true;
                try {
                    scheduler.schedule(this::flushPendingPublishes, COALESCE_WINDOW_MS, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    flushScheduled = false;
                    Log.w("MQTT", "Coalesce flush rejected; scheduler shutting down");
                }
            }
        }
    }

    private void flushPendingPublishes() {
        java.util.HashMap<String, Pending> toSend;
        synchronized (coalesceLock) {
            toSend = pending;
            pending = new java.util.HashMap<>();
            flushScheduled = false;
        }

        if (toSend.isEmpty()) return;
        if (!shouldSend()) return;

        for (java.util.Map.Entry<String, Pending> e : toSend.entrySet()) {
            Pending p = e.getValue();
            publishInternalSync(e.getKey(), p.payload, p.qos, p.retained);
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
        if (temp != -999) publishInternal(parseTopic(MQTT_TOPIC_TEMP_SENSOR), String.valueOf(temp), 1, false);
        if (hum != -999) publishInternal(parseTopic(MQTT_TOPIC_HUM_SENSOR), String.valueOf(hum), 1, false);
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

    private void publishConfig() throws JSONException, MqttException {
        JSONObject payload = new MqttDiscoveryConfigBuilder(
                clientId, DeviceModel.getReportedDevice(), mSharedPreferences).build();
        mMqttClient.publish(parseTopic(MQTT_TOPIC_CONFIG_DEVICE), payload.toString().getBytes(), 1, true);
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
