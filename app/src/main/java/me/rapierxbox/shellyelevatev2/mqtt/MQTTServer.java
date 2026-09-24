package me.rapierxbox.shellyelevatev2.mqtt;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.DeviceModel;
import me.rapierxbox.shellyelevatev2.helper.ThermalZoneReader;
import me.rapierxbox.shellyelevatev2.stes.StesProtocolHandler;

public class MQTTServer {
    private static final String TAG = "MQTTServer";

    private static final long PERIODIC_INTERVAL_NORMAL_SEC = 30;
    private static final long PERIODIC_INTERVAL_LOW_POWER_SEC = 120;
    private static final long MIN_BRIGHTNESS_PUBLISH_INTERVAL_MS = 500;
    // relays and dimmer state are coalesced over this window so rapid flips send one publish
    private static final long COALESCE_WINDOW_MS = 40L;
    private static final long RECONNECT_DELAY_SEC = 5;
    private static final long CONNECT_RETRY_DELAY_SEC = 60;
    private static final int CONNECTION_TIMEOUT_SEC = 5;

    private final MemoryPersistence mMemoryPersistence = new MemoryPersistence();
    private final ShellyElevateMQTTCallback mShellyElevateMQTTCallback = new ShellyElevateMQTTCallback(this);
    private final MqttConnectionOptions mMqttConnectionsOptions = new MqttConnectionOptions();
    // single thread so connects and publishes never interleave
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    // written on the scheduler thread but read from sensor and ui threads
    private volatile MqttClient mMqttClient;
    private volatile String clientId;
    private volatile boolean validForConnection;
    // connection settings the current client was built from
    private volatile String appliedConnectionKey = "";

    // guarded by this
    private ScheduledFuture<?> periodicFuture;
    private long periodicIntervalSec = PERIODIC_INTERVAL_NORMAL_SEC;

    // guarded by this
    private int lastPublishedBrightness = Integer.MIN_VALUE;
    private long lastBrightnessSentAtMs = 0L;
    private int pendingBrightness;
    // trailing publish so the final value of a fade always goes out
    private ScheduledFuture<?> brightnessTrailingFuture;

    private final Object coalesceLock = new Object();
    // guarded by coalesce lock
    private Map<String, PendingPublish> pendingPublishes = new LinkedHashMap<>();
    private boolean flushScheduled = false;

    private BroadcastReceiver settingsChangedReceiver;
    private BroadcastReceiver voiceStateReceiver;

    private static final class PendingPublish {
        final String payload;
        final int qos;
        final boolean retained;

        PendingPublish(String payload, int qos, boolean retained) {
            this.payload = payload;
            this.qos = qos;
            this.retained = retained;
        }
    }

    public MQTTServer() {
        setupClientId();
        registerReceivers();
        checkCredsAndConnect();
    }

    private void setupClientId() {
        String id = mSharedPreferences.getString(SP_MQTT_CLIENTID, "shellywalldisplay");
        // legacy defaults would collide when several displays share one broker
        if (id.equals("shellyelevate") || id.equals("shellywalldisplay") || id.length() <= 2) {
            id = "shellyelevate-" + UUID.randomUUID().toString().replace("-", "").substring(2, 6);
            mSharedPreferences.edit().putString(SP_MQTT_CLIENTID, id).apply();
        }
        clientId = id;
    }

    private void registerReceivers() {
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(mApplicationContext);

        settingsChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Settings changed - reconnecting with new config");
                reconnectWithNewSettings();
            }
        };
        lbm.registerReceiver(settingsChangedReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));

        voiceStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                publishVoiceState();
            }
        };
        lbm.registerReceiver(voiceStateReceiver, new IntentFilter(INTENT_VOICE_STATE_CHANGED));
    }

    // scheduler helpers that never throw once the scheduler is shut down

    private boolean execute(Runnable task) {
        if (scheduler.isShutdown()) return false;
        try {
            scheduler.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Task rejected, scheduler is shut down");
            return false;
        }
    }

    private ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        if (scheduler.isShutdown()) return null;
        try {
            return scheduler.schedule(task, delay, unit);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Delayed task rejected, scheduler is shut down");
            return null;
        }
    }

    private void reconnectWithNewSettings() {
        execute(() -> {
            // settings the connection does not depend on only need a fresh state and discovery sync
            if (connectionKey().equals(appliedConnectionKey) && isClientConnected()) {
                Log.d(TAG, "Connection settings unchanged - republishing state only");
                publishStatus();
                return;
            }
            MqttClient old = mMqttClient;
            if (old != null) {
                Log.d(TAG, "Tearing down old MQTT connection before applying new settings");
                // mqtt was switched off so mark entities unavailable but keep the ha device and its customizations
                if (!isEnabled()) publishOffline(old);
                closeClient(old);
                mMqttClient = null;
            }
            // clear a stuck connecting flag so the fresh attempt is not blocked
            connecting.set(false);

            setupClientId();
            Log.d(TAG, "Updated MQTT client ID to: " + clientId);

            try {
                // give the broker a moment to drop the old session before we reuse the same client id
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted during reconnect", e);
                Thread.currentThread().interrupt();
                return;
            }
            checkCredsAndConnect();
        });
    }

    public void checkCredsAndConnect() {
        appliedConnectionKey = connectionKey();
        if (!isEnabled()) {
            // stop the periodic task so no sensor reads fire while disabled
            stopPeriodicPublish();
            if (isClientConnected()) {
                Log.d(TAG, "MQTT disabled in settings - disconnecting");
                disconnect();
            }
            return;
        }

        validForConnection =
                !mSharedPreferences.getString(SP_MQTT_PASSWORD, "").isEmpty() &&
                        !mSharedPreferences.getString(SP_MQTT_USERNAME, "").isEmpty() &&
                        !mSharedPreferences.getString(SP_MQTT_BROKER, "").isEmpty();

        if (!validForConnection) {
            Log.w(TAG, "Invalid connection credentials - broker, username, or password missing");
            return;
        }

        startPeriodicPublish();
        connect();
    }

    public void connect() {
        // the enabled flag is rechecked since a delayed retry may fire after mqtt got disabled
        if (!isEnabled() || !validForConnection || isClientConnected()) return;
        if (!connecting.compareAndSet(false, true)) return;

        Log.d(TAG, "Connecting...");
        if (!execute(this::doConnect)) connecting.set(false);
    }

    private void doConnect() {
        if (isClientConnected()) {
            connecting.set(false);
            return;
        }

        try {
            configureConnectionOptions();

            // release the previous client so its network threads go away
            MqttClient previous = mMqttClient;
            if (previous != null) {
                closeClient(previous);
                mMqttClient = null;
            }

            MqttClient client = new MqttClient(buildServerUri(), clientId, mMemoryPersistence);
            client.setCallback(createClientCallback(client));
            mMqttClient = client;
            client.connect(mMqttConnectionsOptions);
        } catch (MqttException e) {
            Log.e(TAG, "Connect failed, scheduling retry in " + CONNECT_RETRY_DELAY_SEC + "s", e);
            connecting.set(false);
            schedule(this::connect, CONNECT_RETRY_DELAY_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            // a bad broker uri throws here so reset the flag and let a fixed setting reconnect without reboot
            Log.e(TAG, "Connect failed with unexpected error", e);
            connecting.set(false);
        }
    }

    private void configureConnectionOptions() {
        mMqttConnectionsOptions.setUserName(mSharedPreferences.getString(SP_MQTT_USERNAME, ""));
        mMqttConnectionsOptions.setPassword(
                mSharedPreferences.getString(SP_MQTT_PASSWORD, "").getBytes(StandardCharsets.UTF_8));
        mMqttConnectionsOptions.setAutomaticReconnect(false);
        mMqttConnectionsOptions.setConnectionTimeout(CONNECTION_TIMEOUT_SEC);
        mMqttConnectionsOptions.setCleanStart(true);

        // the broker publishes offline for us if we drop without a clean disconnect
        MqttMessage will = new MqttMessage("offline".getBytes(StandardCharsets.UTF_8));
        will.setQos(1);
        will.setRetained(true);
        will.setProperties(payloadProperties("offline"));
        mMqttConnectionsOptions.setWill(parseTopic(MQTT_TOPIC_STATUS), will);
    }

    // every setting the live connection was built from so a change forces a reconnect
    private String connectionKey() {
        return isEnabled() + "\n" + buildServerUri() + "\n"
                + mSharedPreferences.getString(SP_MQTT_USERNAME, "") + "\n"
                + mSharedPreferences.getString(SP_MQTT_PASSWORD, "") + "\n"
                + mSharedPreferences.getString(SP_MQTT_CLIENTID, "");
    }

    private String buildServerUri() {
        String broker = mSharedPreferences.getString(SP_MQTT_BROKER, "").trim();
        // default to plain tcp when the scheme is omitted
        if (!broker.contains("://")) broker = "tcp://" + broker;
        return broker + ":" + mSharedPreferences.getInt(SP_MQTT_PORT, 1883);
    }

    private MqttCallback createClientCallback(MqttClient client) {
        return new MqttCallback() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.i(TAG, "Connected to " + serverURI + ", reconnect: " + reconnect);
                connecting.set(false);
                onConnected();
            }

            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {
                Log.w(TAG, "Disconnected: " + disconnectResponse.getReasonString());
                // a replaced client must not reset state that belongs to its successor
                if (client != mMqttClient) return;
                connecting.set(false);
                if (isEnabled() && validForConnection) {
                    schedule(MQTTServer.this::connect, RECONNECT_DELAY_SEC, TimeUnit.SECONDS);
                }
            }

            @Override
            public void mqttErrorOccurred(MqttException exception) {
                Log.e(TAG, "MQTT error occurred", exception);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // paho only logs handler exceptions at fine level so surface them here
                try {
                    mShellyElevateMQTTCallback.messageArrived(topic, message);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Failed to handle message on " + topic, e);
                }
            }

            @Override
            public void deliveryComplete(IMqttToken token) {}

            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {}
        };
    }

    private void onConnected() {
        // some brokers reject a subscribe that shares the tcp write with the connack so wait a moment
        schedule(() -> {
            MqttClient client = mMqttClient;
            if (client == null || !client.isConnected()) return;
            try {
                for (String topic : inboundTopics()) client.subscribe(topic, 1);
                publishStatus();
            } catch (Exception e) {
                Log.e(TAG, "onConnected error", e);
            }
        }, 150, TimeUnit.MILLISECONDS);
    }

    // only command topics so we never echo our own state back
    private String[] inboundTopics() {
        return new String[]{
                parseTopic(MQTT_TOPIC_UPDATE),
                MQTT_TOPIC_UPDATE_GENERIC,
                parseTopic(MQTT_TOPIC_RELAY_COMMAND),
                parseTopic(MQTT_TOPIC_RELAY_COMMAND) + "_1",
                parseTopic(MQTT_TOPIC_DIMMER_COMMAND),
                parseTopic(MQTT_TOPIC_SLEEP_BUTTON),
                parseTopic(MQTT_TOPIC_WAKE_BUTTON),
                parseTopic(MQTT_TOPIC_REBOOT_BUTTON),
                parseTopic(MQTT_TOPIC_REFRESH_WEBVIEW_BUTTON),
                parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS_COMMAND),
                parseTopic(MQTT_TOPIC_NIGHT_MODE_COMMAND),
                parseTopic(MQTT_TOPIC_VOICE_TRIGGER),
                parseTopic(MQTT_TOPIC_VOICE_MUTE_COMMAND),
                MQTT_TOPIC_HOME_ASSISTANT_STATUS
        };
    }

    public void disconnect() {
        Log.d(TAG, "Disconnecting");
        MqttClient client = mMqttClient;
        if (client == null || !client.isConnected()) return;
        try {
            deleteConfig(client);
            // sent directly since the send guard already fails when mqtt was just disabled
            publishSync(client, parseTopic(MQTT_TOPIC_STATUS), "offline", 1, true);
            client.disconnect();
        } catch (MqttException e) {
            Log.e(TAG, "Error disconnecting MQTT client", e);
        }
    }

    private void publishOffline(MqttClient client) {
        if (!client.isConnected()) return;
        // sent directly since the send guard already fails when mqtt was just disabled
        publishSync(client, parseTopic(MQTT_TOPIC_STATUS), "offline", 1, true);
    }

    private static void closeClient(MqttClient client) {
        try {
            if (client.isConnected()) client.disconnect();
        } catch (MqttException e) {
            Log.w(TAG, "Clean disconnect failed, forcing it", e);
            // close refuses a client that still counts as connected
            try {
                client.disconnectForcibly();
            } catch (MqttException forced) {
                Log.w(TAG, "Forced disconnect failed", forced);
            }
        }
        try {
            client.close();
        } catch (MqttException e) {
            Log.w(TAG, "Error closing MQTT client", e);
        }
    }

    public boolean isEnabled() {
        return mSharedPreferences.getBoolean(SP_MQTT_ENABLED, false);
    }

    private boolean isClientConnected() {
        MqttClient client = mMqttClient;
        return client != null && client.isConnected();
    }

    public boolean shouldSend() {
        return isEnabled() && isClientConnected();
    }

    private boolean isHaDiscoveryEnabled() {
        return mSharedPreferences.getBoolean(SP_MQTT_HA_DISCOVERY, true);
    }

    private boolean shouldRetainState() {
        return mSharedPreferences.getBoolean(SP_MQTT_RETAIN_STATE, true);
    }

    private synchronized void startPeriodicPublish() {
        if (periodicFuture != null) return;
        periodicFuture = schedulePeriodic(0, periodicIntervalSec);
    }

    private synchronized void stopPeriodicPublish() {
        if (periodicFuture == null) return;
        periodicFuture.cancel(false);
        periodicFuture = null;
    }

    public synchronized void setLowPowerMode(boolean low) {
        long target = low ? PERIODIC_INTERVAL_LOW_POWER_SEC : PERIODIC_INTERVAL_NORMAL_SEC;
        if (target == periodicIntervalSec) return;
        periodicIntervalSec = target;
        Log.i(TAG, "Periodic publish interval -> " + target + "s (lowPower=" + low + ")");
        if (periodicFuture != null) {
            periodicFuture.cancel(false);
            periodicFuture = schedulePeriodic(target, target);
        }
    }

    private ScheduledFuture<?> schedulePeriodic(long initialDelaySec, long delaySec) {
        if (scheduler.isShutdown()) return null;
        try {
            return scheduler.scheduleWithFixedDelay(this::runPeriodicPublish,
                    initialDelaySec, delaySec, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Periodic publish rejected, scheduler is shut down");
            return null;
        }
    }

    private void runPeriodicPublish() {
        // skip sensor and uart reads while there is nothing to publish to
        if (!isClientConnected()) return;
        try {
            publishTempAndHum();
            publishThermalZones();
            if (mDeviceHelper.isDimmerAttached()) {
                StesProtocolHandler.getStatus(s -> publishDimmer(s.on, s.actualBrightness / 10));
                StesProtocolHandler.getPowerMeter(p -> publishDimmerPower(p.powerW, p.voltageV, p.currentA));
            }
        } catch (RuntimeException e) {
            // an escaping exception would silently cancel the fixed delay schedule for good
            Log.e(TAG, "Periodic publish failed", e);
        }
    }

    // full state sync after connect or when ha asks for it

    public void publishStatus() {
        if (!isClientConnected()) return;

        execute(() -> {
            try {
                publishHello();
                if (isHaDiscoveryEnabled()) {
                    publishConfig();
                } else {
                    clearStaleConfig();
                }
                publishInternal(parseTopic(MQTT_TOPIC_STATUS), "online", 1, true);
                publishNightModeState();

                // staggered so the discovery burst does not swamp a slow broker or starve the scheduler
                schedule(this::publishTempAndHum, 50, TimeUnit.MILLISECONDS);
                schedule(this::publishRelaysAndInputs, 100, TimeUnit.MILLISECONDS);
                schedule(this::publishDisplayState, 150, TimeUnit.MILLISECONDS);
                if (mDeviceHelper.isDimmerAttached()) {
                    schedule(() -> StesProtocolHandler.getStatus(s -> publishDimmer(s.on, s.actualBrightness / 10)),
                            200, TimeUnit.MILLISECONDS);
                }
                schedule(this::publishVoiceState, 250, TimeUnit.MILLISECONDS);
                schedule(this::publishThermalZones, 2, TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.e(TAG, "publishStatus failed", e);
            }
        });
    }

    private void publishRelaysAndInputs() {
        DeviceModel device = DeviceModel.getReportedDevice();
        for (int num = 0; num < device.relays; num++) {
            publishRelay(num, mDeviceHelper.getRelay(num));
        }
        // re-sync the input binary sensors after a reconnect since the level stays unknown until the first edge
        if (mSwInputHandler != null) {
            for (int num = 0; num < device.inputs; num++) {
                Boolean level = mSwInputHandler.getLevel(num);
                if (level != null) publishSwitch(num, level);
            }
        }
    }

    private void publishDisplayState() {
        publishLux(mDeviceSensorManager.getLastMeasuredLux());
        publishScreenBrightness(mDeviceHelper.getScreenBrightness());
        if (DeviceModel.getReportedDevice().hasProximitySensor) {
            publishProximity(mDeviceSensorManager.getLastMeasuredDistance());
        }
        publishSleeping(mScreenSaverManager.isScreenSaverRunning());
    }

    private void publishConfig() throws JSONException {
        JSONObject payload = new MqttDiscoveryConfigBuilder(
                clientId, DeviceModel.getReportedDevice(), mSharedPreferences).build();
        String topic = parseTopic(MQTT_TOPIC_CONFIG_DEVICE);
        String json = payload.toString();
        Log.i(TAG, "publishConfig: topic=" + topic + " bytes=" + json.length()
                + " components=" + payload.optJSONObject("cmps").length());
        // sync so the discovery blob lands before the state topics it describes
        publishInternalSync(topic, json, 1, true);
    }

    private void clearStaleConfig() {
        MqttClient client = mMqttClient;
        if (client == null) return;
        try {
            deleteConfig(client);
        } catch (MqttException e) {
            Log.w(TAG, "Failed to clear stale discovery topic", e);
        }
    }

    // an empty retained payload removes the device from ha and wipes the retained blob on the broker
    private void deleteConfig(MqttClient client) throws MqttException {
        client.publish(parseTopic(MQTT_TOPIC_CONFIG_DEVICE), new byte[0], 1, true);
    }

    public void publishInternal(String topic, String payload, int qos, boolean retained) {
        execute(() -> publishInternalSync(topic, payload, qos, retained));
    }

    // last write wins per topic so a fast toggling relay produces one publish per window
    private void publishInternalCoalesced(String topic, String payload, int qos, boolean retained) {
        synchronized (coalesceLock) {
            pendingPublishes.put(topic, new PendingPublish(payload, qos, retained));
            if (flushScheduled) return;
            flushScheduled = schedule(this::flushPendingPublishes, COALESCE_WINDOW_MS, TimeUnit.MILLISECONDS) != null;
        }
    }

    private void flushPendingPublishes() {
        Map<String, PendingPublish> toSend;
        synchronized (coalesceLock) {
            toSend = pendingPublishes;
            pendingPublishes = new LinkedHashMap<>();
            flushScheduled = false;
        }

        if (toSend.isEmpty() || !shouldSend()) return;
        for (Map.Entry<String, PendingPublish> entry : toSend.entrySet()) {
            PendingPublish p = entry.getValue();
            publishInternalSync(entry.getKey(), p.payload, p.qos, p.retained);
        }
    }

    private void publishInternalSync(String topic, String payload, int qos, boolean retained) {
        MqttClient client = mMqttClient;
        if (!isEnabled() || client == null || !client.isConnected()) {
            Log.w(TAG, "publishInternal skipped, client not connected: " + topic);
            return;
        }
        publishSync(client, topic, payload, qos, retained);
    }

    private static void publishSync(MqttClient client, String topic, String payload, int qos, boolean retained) {
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(retained);
            message.setProperties(payloadProperties(payload));
            client.publish(topic, message);
        } catch (MqttException e) {
            Log.e(TAG, "Failed to publish to " + topic, e);
        }
    }

    // mark payloads as utf8 text or json so v5 brokers do not render them as binary
    private static MqttProperties payloadProperties(String payload) {
        MqttProperties props = new MqttProperties();
        props.setPayloadFormat(true);
        String trimmed = payload.trim();
        boolean looksLikeJson = trimmed.startsWith("{") || trimmed.startsWith("[");
        props.setContentType(looksLikeJson ? "application/json" : "text/plain; charset=utf-8");
        return props;
    }

    private static String indexSuffix(int num) {
        return num > 0 ? "_" + num : "";
    }

    public void publishTempAndHum() {
        float temp = (float) mDeviceHelper.getTemperature();
        float hum = (float) mDeviceHelper.getHumidity();
        boolean retain = shouldRetainState();
        // -999 marks a failed sensor read
        if (temp != -999) publishInternal(parseTopic(MQTT_TOPIC_TEMP_SENSOR), String.valueOf(temp), 1, retain);
        if (hum != -999) publishInternal(parseTopic(MQTT_TOPIC_HUM_SENSOR), String.valueOf(hum), 1, retain);
    }

    public void publishLux(float lux) {
        publishInternal(parseTopic(MQTT_TOPIC_LUX_SENSOR), String.valueOf(lux), 1, shouldRetainState());
    }

    public void publishScreenBrightness(int brightness) {
        long now = SystemClock.elapsedRealtime();

        // the fade animator calls this many times per second so rate limit it
        synchronized (this) {
            long since = now - lastBrightnessSentAtMs;
            if (since < MIN_BRIGHTNESS_PUBLISH_INTERVAL_MS) {
                if (brightness == lastPublishedBrightness) return;
                // throttled during a fade so arm a trailing publish of the latest value
                pendingBrightness = brightness;
                if (brightnessTrailingFuture != null) brightnessTrailingFuture.cancel(false);
                brightnessTrailingFuture = schedule(this::publishTrailingBrightness,
                        MIN_BRIGHTNESS_PUBLISH_INTERVAL_MS - since, TimeUnit.MILLISECONDS);
                return;
            }
            if (brightnessTrailingFuture != null) {
                brightnessTrailingFuture.cancel(false);
                brightnessTrailingFuture = null;
            }
            lastPublishedBrightness = brightness;
            lastBrightnessSentAtMs = now;
        }

        publishInternal(parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS), String.valueOf(brightness), 1, shouldRetainState());
    }

    private void publishTrailingBrightness() {
        int value;
        synchronized (this) {
            brightnessTrailingFuture = null;
            value = pendingBrightness;
            if (value == lastPublishedBrightness) return;
            lastPublishedBrightness = value;
            lastBrightnessSentAtMs = SystemClock.elapsedRealtime();
        }
        publishInternal(parseTopic(MQTT_TOPIC_SCREEN_BRIGHTNESS), String.valueOf(value), 1, shouldRetainState());
    }

    public void publishProximity(float distance) {
        publishInternal(parseTopic(MQTT_TOPIC_PROXIMITY_SENSOR), String.valueOf(distance), 1, shouldRetainState());
    }

    public void publishRelay(int num, boolean state) {
        publishInternalCoalesced(parseTopic(MQTT_TOPIC_RELAY_STATE) + indexSuffix(num),
                state ? "ON" : "OFF", 1, shouldRetainState());
    }

    public void publishDimmer(boolean on, int brightness0to100) {
        boolean retain = shouldRetainState();
        publishInternalCoalesced(parseTopic(MQTT_TOPIC_DIMMER_STATE), on ? "ON" : "OFF", 1, retain);
        publishInternalCoalesced(parseTopic(MQTT_TOPIC_DIMMER_BRI), String.valueOf(brightness0to100), 1, retain);
    }

    public void publishDimmerPower(float watts, int volts, float amps) {
        String json = "{\"power\":" + watts + ",\"voltage\":" + volts + ",\"current\":" + amps + "}";
        publishInternalCoalesced(parseTopic(MQTT_TOPIC_DIMMER_POWER), json, 1, shouldRetainState());
    }

    public void publishSwitch(int num, boolean state) {
        // momentary events are never retained and skip coalescing so a fast press then release both go out
        publishInternal(parseTopic(MQTT_TOPIC_SWITCH_STATE) + indexSuffix(num),
                state ? "PRESS" : "RELEASE", 1, false);
    }

    public void publishSleeping(boolean state) {
        publishInternal(parseTopic(MQTT_TOPIC_SLEEPING_BINARY_SENSOR), state ? "ON" : "OFF", 1, shouldRetainState());
    }

    // button 140 is the dedicated power button and 0 to 3 are the touch buttons
    public void publishButton(int number, String pressType) {
        JSONObject json = new JSONObject();
        try {
            json.put("last_update", System.currentTimeMillis());
            json.put("press_type", pressType);
            // event_type is the field the ha mqtt event entity reads
            json.put("event_type", pressType);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating button JSON", e);
        }

        String topic = (number == 140)
                ? parseTopic(MQTT_TOPIC_POWER_BUTTON)
                : parseTopic(MQTT_TOPIC_BUTTON_STATE) + "/" + number;

        // not coalesced so rapid presses all reach ha
        publishInternal(topic, json.toString(), 1, false);
    }

    public void publishVoiceState() {
        if (mVoiceAssistantManager == null) return;
        if (!mVoiceAssistantManager.isEnabled() && !mSharedPreferences.getBoolean(SP_VOICE_ASSISTANT_ENABLED, false)) return;
        publishInternal(parseTopic(MQTT_TOPIC_VOICE_STATUS),
                mVoiceAssistantManager.getPublishedStatus(), 1, true);
        publishInternal(parseTopic(MQTT_TOPIC_VOICE_MUTE_STATE),
                mVoiceAssistantManager.isMuted() ? "ON" : "OFF", 1, true);
    }

    public void publishNightModeState() {
        if (mNightModeManager == null) return;
        publishInternal(parseTopic(MQTT_TOPIC_NIGHT_MODE_STATE),
                mNightModeManager.isEnabled() ? "ON" : "OFF", 1, true);
    }

    public void publishSwipeEvent(String eventType) {
        publishInternal(parseTopic(MQTT_TOPIC_SWIPE_EVENT),
                "{\"event_type\": \"" + eventType + "\"}", 1, false);
    }

    public void publishHello() {
        if (!shouldSend()) return;
        try {
            JSONObject json = new JSONObject();
            json.put("name", mApplicationContext.getPackageName());
            json.put("version", readVersionName());
            json.put("startTime", getApplicationStartTime());
            json.put("buildType", BuildConfig.BUILD_TYPE);
            DeviceModel device = DeviceModel.getReportedDevice();
            json.put("modelName", device.name());
            json.put("proximity", device.hasProximitySensor ? "true" : "false");

            publishInternal(parseTopic(MQTT_TOPIC_HELLO), json.toString(), 1, false);
        } catch (JSONException e) {
            Log.e(TAG, "Error publishing hello", e);
        }
    }

    private static String readVersionName() {
        try {
            PackageInfo info = mApplicationContext.getPackageManager()
                    .getPackageInfo(mApplicationContext.getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private void publishThermalZones() {
        if (!mSharedPreferences.getBoolean(SP_PUBLISH_THERMAL_SENSORS, false)) return;
        boolean retain = shouldRetainState();
        for (ThermalZoneReader.Zone zone : ThermalZoneReader.discoverZones()) {
            Float temp = ThermalZoneReader.readZoneTempC(zone);
            if (temp == null) continue;
            String topic = String.format(MQTT_TOPIC_THERMAL_ZONE, clientId, zone.type);
            publishInternal(topic, String.valueOf(Math.round(temp * 10f) / 10f), 1, retain);
        }
    }

    private String parseTopic(String topic) {
        return topic.replace("%s", clientId);
    }

    public String getClientId() {
        return clientId;
    }

    public void onDestroy() {
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(mApplicationContext);
        if (settingsChangedReceiver != null) lbm.unregisterReceiver(settingsChangedReceiver);
        if (voiceStateReceiver != null) lbm.unregisterReceiver(voiceStateReceiver);
        stopPeriodicPublish();
        disconnect();
        MqttClient client = mMqttClient;
        if (client != null) {
            closeClient(client);
            mMqttClient = null;
        }
        scheduler.shutdown();
    }
}
