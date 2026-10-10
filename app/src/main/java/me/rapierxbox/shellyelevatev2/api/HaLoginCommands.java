package me.rapierxbox.shellyelevatev2.api;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_HA_LOGIN_CHANGED;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicBoolean;

import me.rapierxbox.shellyelevatev2.helper.ServiceHelper;

// the controller logs the dashboard into home assistant with a refresh token it made for this display
// the token is only accepted for the origin of the configured dashboard url
final class HaLoginCommands {
    private static final String TAG = "HaLoginCommands";
    private static final AtomicBoolean stateProviderAdded = new AtomicBoolean(false);

    private HaLoginCommands() {}

    static void register(Context context) {
        Context app = context.getApplicationContext();
        HaLoginStore store = HaLoginStore.get(app);
        ApiHub.registerCommand("ha_login.status", p -> status(store));
        ApiHub.registerCommand("ha_login.set", p -> set(app, store, p));
        ApiHub.registerCommand("ha_login.clear", p -> {
            store.clear();
            changed(app);
            return status(store);
        });
        // a new lambda per api start would pile up providers since addIfAbsent never matches it
        if (stateProviderAdded.getAndSet(true)) return;
        ApiHub.addStateProvider(state -> {
            String dashboard = ServiceHelper.getWebviewUrl();
            state.put("ha_login.state", store.state(dashboard));
            String user = store.userName();
            state.put("ha_login.user", user != null && !user.isEmpty() ? user : null);
        });
    }

    // the controller reads the origin here and makes the token for it
    private static JSONObject status(HaLoginStore store) throws ApiHub.CommandException {
        String dashboard = ServiceHelper.getWebviewUrl();
        String origin = HaLoginRules.originOf(dashboard);
        try {
            JSONObject out = new JSONObject();
            out.put("state", store.state(dashboard));
            out.put("user", store.userName() != null ? store.userName() : JSONObject.NULL);
            out.put("origin", origin != null ? origin : JSONObject.NULL);
            out.put("client_id", origin != null ? HaLoginRules.clientIdFor(origin) : JSONObject.NULL);
            return out;
        } catch (JSONException e) {
            throw new ApiHub.CommandException("internal", "could not build the status");
        }
    }

    private static JSONObject set(Context app, HaLoginStore store, JSONObject p) throws ApiHub.CommandException {
        String origin = p.optString("origin", "");
        String clientId = p.optString("client_id", "");
        String token = p.optString("refresh_token", "");
        String user = p.optString("user", "");
        if (token.isEmpty()) throw ApiHub.CommandException.invalid("refresh_token required");
        String normalized = HaLoginRules.originOf(origin);
        if (normalized == null || !normalized.equals(origin)) {
            throw ApiHub.CommandException.invalid("origin must be scheme host and port only");
        }
        if (!HaLoginRules.clientIdFor(origin).equals(clientId)) {
            throw ApiHub.CommandException.invalid("client_id must be the origin with a trailing slash");
        }
        // a token for another origin would never be used and must not be stored
        if (!HaLoginRules.sameOrigin(ServiceHelper.getWebviewUrl(), origin)) {
            throw new ApiHub.CommandException("origin_mismatch", "the dashboard url is not on " + origin);
        }
        try {
            store.set(origin, token, user);
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Could not seal the login", e);
            throw new ApiHub.CommandException("internal", "the keystore could not store the login");
        }
        changed(app);
        return status(store);
    }

    private static void changed(Context app) {
        LocalBroadcastManager.getInstance(app).sendBroadcast(new Intent(INTENT_HA_LOGIN_CHANGED));
        ApiHub.stateChanged();
    }
}
