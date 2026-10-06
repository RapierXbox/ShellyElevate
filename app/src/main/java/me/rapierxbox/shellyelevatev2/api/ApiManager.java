package me.rapierxbox.shellyelevatev2.api;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import me.rapierxbox.shellyelevatev2.SettingsParser;

// owns the v1 api: tls identity server state hub mdns and the settings push
// the server always runs. it is idle until a controller pairs and the display never depends on it
public final class ApiManager {
    private static final String TAG = "ApiManager";
    // a connection stays open this long without traffic. controllers ping every 30 s
    private static final int SOCKET_TIMEOUT_MS = 65_000;
    private static final long WATCHDOG_PERIOD_S = 30;
    private static final long RETRY_MAX_S = 60;

    private static volatile ApiManager instance;

    private final Context context;
    private final ClientTokenStore tokens;
    private final Pairing pairing = new Pairing();
    private final StateHub stateHub;
    private final ApiDiscovery discovery;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "ApiManager"));
    private final ClientTokenStore.Listener clientsListener = this::onClientsChanged;
    private final SettingsParser.ChangeListener settingsListener = this::onSettingsChanged;
    private volatile ApiServer server;
    private long retryDelayS = 5;

    private ApiManager(Context context) {
        this.context = context.getApplicationContext();
        tokens = ClientTokenStore.get(this.context);
        stateHub = new StateHub(this.context, message -> {
            ApiServer s = server;
            if (s != null) s.sendText(message.toString());
        });
        discovery = new ApiDiscovery(this.context);
    }

    public static synchronized void start(Context context) {
        if (instance != null) return;
        instance = new ApiManager(context);
        instance.startInternal();
    }

    public static synchronized void stop() {
        if (instance == null) return;
        instance.stopInternal();
        instance = null;
    }

    public static void cancelPairing(String pairingId) {
        ApiManager m = instance;
        if (m != null) m.pairing.cancel(pairingId);
    }

    // true when the v1 server listens
    public static boolean isRunning() {
        ApiManager m = instance;
        ApiServer s = m != null ? m.server : null;
        return s != null && s.isAlive();
    }

    private void startInternal() {
        CoreCommands.register();
        stateHub.start();
        tokens.addListener(clientsListener);
        SettingsParser.addChangeListener(settingsListener);
        // the keystore key is slow on first start so the server comes up on the background thread
        executor.execute(this::startServer);
        executor.scheduleWithFixedDelay(() -> {
            ApiServer s = server;
            if (s == null || !s.isAlive()) {
                Log.w(TAG, "v1 server not alive, restarting");
                startServer();
            }
        }, WATCHDOG_PERIOD_S, WATCHDOG_PERIOD_S, TimeUnit.SECONDS);
    }

    private void stopInternal() {
        tokens.removeListener(clientsListener);
        SettingsParser.removeChangeListener(settingsListener);
        executor.shutdownNow();
        discovery.unregister();
        ApiHub.setSink(null);
        ApiServer s = server;
        server = null;
        if (s != null) {
            s.closeAllSockets();
            s.stop();
        }
        stateHub.stop();
    }

    private synchronized void startServer() {
        ApiServer old = server;
        if (old != null && old.isAlive()) return;
        if (old != null) {
            old.closeAllSockets();
            old.stop();
        }
        try {
            TlsIdentity identity = TlsIdentity.get();
            ApiServer fresh = new ApiServer(context, tokens, pairing, stateHub);
            fresh.makeSecure(identity.serverSocketFactory(), null);
            fresh.start(SOCKET_TIMEOUT_MS, false);
            server = fresh;
            ApiHub.setSink(fresh);
            discovery.register(tokens.hasClients());
            retryDelayS = 5;
            Log.i(TAG, "v1 API listening on " + ApiInfo.TLS_PORT);
        } catch (IOException e) {
            Log.e(TAG, "v1 server failed to start, retrying in " + retryDelayS + " s", e);
            scheduleRetry();
        } catch (Exception e) {
            // a keystore failure is not going to fix itself quickly
            Log.e(TAG, "v1 server cannot start", e);
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        long delay = retryDelayS;
        retryDelayS = Math.min(retryDelayS * 2, RETRY_MAX_S);
        try {
            executor.schedule(this::startServer, delay, TimeUnit.SECONDS);
        } catch (RuntimeException ignored) {
            // shutting down
        }
    }

    private void onClientsChanged() {
        discovery.register(tokens.hasClients());
        ApiServer s = server;
        if (s != null) s.dropStaleSockets();
    }

    private void onSettingsChanged(JSONObject changes) {
        if (changes.length() == 0 || !ApiHub.hasController()) return;
        try {
            ApiHub.send(new JSONObject().put("type", "settings_changed").put("changes", changes));
        } catch (JSONException e) {
            Log.w(TAG, "Could not build settings_changed", e);
        }
        stateHub.poke();
    }
}
