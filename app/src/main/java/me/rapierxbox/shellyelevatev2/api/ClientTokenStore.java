package me.rapierxbox.shellyelevatev2.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

// paired controllers and their tokens. only a sha-256 of each token is stored
// a separate file so tokens never show up in the settings export or the legacy /settings route
public final class ClientTokenStore {
    private static final String TAG = "ClientTokenStore";
    public static final String PREFS_NAME = "ShellyElevateV2.clients";
    private static final String KEY_PREFIX = "client.";
    // last seen is written at most this often so every request does not hit the disk
    private static final long LAST_SEEN_WRITE_MS = 60_000;

    public static final class Client {
        public final String id;
        public final String name;
        public final long created;
        public final long lastSeen;
        final String tokenHash;

        Client(String id, String name, long created, long lastSeen, String tokenHash) {
            this.id = id;
            this.name = name;
            this.created = created;
            this.lastSeen = lastSeen;
            this.tokenHash = tokenHash;
        }
    }

    public interface Listener {
        void onClientsChanged();
    }

    private static volatile ClientTokenStore instance;

    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private ClientTokenStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static ClientTokenStore get(Context context) {
        ClientTokenStore current = instance;
        if (current != null) return current;
        synchronized (ClientTokenStore.class) {
            if (instance == null) instance = new ClientTokenStore(context);
            return instance;
        }
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // a new token for the client. replaces the one it had so pairing again never piles up tokens
    public String issue(String clientId, String clientName) {
        String token = newToken();
        long now = System.currentTimeMillis();
        synchronized (this) {
            write(new Client(clientId, clientName, now, now, hash(token)));
        }
        notifyChanged();
        return token;
    }

    // stores a token handed over by adb provisioning
    public void store(String clientId, String clientName, String token) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            write(new Client(clientId, clientName, now, now, hash(token)));
        }
        notifyChanged();
    }

    // the client the token belongs to or null
    public Client authenticate(String token) {
        if (token == null || token.isEmpty()) return null;
        byte[] presented = hash(token).getBytes(StandardCharsets.US_ASCII);
        Client match = null;
        for (Client client : list()) {
            // constant time per entry so the hash comparison leaks nothing
            if (MessageDigest.isEqual(presented, client.tokenHash.getBytes(StandardCharsets.US_ASCII))) {
                match = client;
            }
        }
        if (match != null && System.currentTimeMillis() - match.lastSeen > LAST_SEEN_WRITE_MS) {
            touch(match);
        }
        return match;
    }

    // replaces the token of the client and returns the new one
    public String rotate(Client client) {
        String token = newToken();
        synchronized (this) {
            Client current = find(client.id);
            long created = current != null ? current.created : System.currentTimeMillis();
            write(new Client(client.id, client.name, created, System.currentTimeMillis(), hash(token)));
        }
        notifyChanged();
        return token;
    }

    public void revoke(String clientId) {
        synchronized (this) {
            if (!prefs.contains(KEY_PREFIX + clientId)) return;
            prefs.edit().remove(KEY_PREFIX + clientId).apply();
        }
        Log.i(TAG, "Revoked client " + clientId);
        notifyChanged();
    }

    public void revokeAll() {
        synchronized (this) {
            prefs.edit().clear().apply();
        }
        notifyChanged();
    }

    public boolean hasClients() {
        return !list().isEmpty();
    }

    // newest first
    public List<Client> list() {
        List<Client> clients = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(KEY_PREFIX) || !(entry.getValue() instanceof String)) continue;
            Client client = parse(entry.getKey().substring(KEY_PREFIX.length()), (String) entry.getValue());
            if (client != null) clients.add(client);
        }
        Collections.sort(clients, (a, b) -> Long.compare(b.created, a.created));
        return clients;
    }

    private Client find(String clientId) {
        String raw = prefs.getString(KEY_PREFIX + clientId, null);
        return raw != null ? parse(clientId, raw) : null;
    }

    private synchronized void touch(Client client) {
        Client current = find(client.id);
        // skip when the token was rotated or revoked meanwhile
        if (current == null || !current.tokenHash.equals(client.tokenHash)) return;
        write(new Client(current.id, current.name, current.created, System.currentTimeMillis(), current.tokenHash));
    }

    private void write(Client client) {
        try {
            JSONObject json = new JSONObject();
            json.put("name", client.name);
            json.put("created", client.created);
            json.put("last_seen", client.lastSeen);
            json.put("hash", client.tokenHash);
            prefs.edit().putString(KEY_PREFIX + client.id, json.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Could not store client " + client.id, e);
        }
    }

    private static Client parse(String id, String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            String hash = json.optString("hash", "");
            if (hash.isEmpty()) return null;
            return new Client(id, json.optString("name", id), json.optLong("created"),
                    json.optLong("last_seen"), hash);
        } catch (JSONException e) {
            return null;
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    static String hash(String token) {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void notifyChanged() {
        for (Listener listener : listeners) {
            try {
                listener.onClientsChanged();
            } catch (RuntimeException e) {
                Log.w(TAG, "Client listener failed", e);
            }
        }
    }
}
