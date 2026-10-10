package me.rapierxbox.shellyelevatev2.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

// the home assistant session a paired controller hands over so the dashboard logs in by itself
// a separate file so it never shows up in the settings export or any settings route
// the refresh token is sealed with a keystore aes key and only ever used on the origin it was made for
public final class HaLoginStore {
    private static final String TAG = "HaLoginStore";
    public static final String PREFS_NAME = "ShellyElevateV2.halogin";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "shellyelevate-ha-login";
    private static final int GCM_TAG_BITS = 128;

    private static final String K_ORIGIN = "origin";
    private static final String K_TOKEN = "token";
    private static final String K_USER = "user";
    private static final String K_REFUSED = "refused";
    // set by a new session so the open dashboard switches to it once
    private static final String K_APPLY = "apply";
    // the origin whose stored frontend session still has to be dropped after a logout
    private static final String K_LOGOUT_ORIGIN = "logout_origin";

    public static final String STATE_NONE = "none";
    public static final String STATE_OK = "ok";
    public static final String STATE_INVALID = "invalid";

    public static final class Session {
        public final String origin;
        public final String clientId;
        public final String refreshToken;
        public final String userName;

        Session(String origin, String refreshToken, String userName) {
            this.origin = origin;
            this.clientId = HaLoginRules.clientIdFor(origin);
            this.refreshToken = refreshToken;
            this.userName = userName;
        }
    }

    private static volatile HaLoginStore instance;

    private final SharedPreferences prefs;
    // the opened token so page loads do not hit the keystore every time
    private Session cached;
    private long lastHandOverMs;

    private HaLoginStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static HaLoginStore get(Context context) {
        HaLoginStore current = instance;
        if (current != null) return current;
        synchronized (HaLoginStore.class) {
            if (instance == null) instance = new HaLoginStore(context);
            return instance;
        }
    }

    public synchronized void set(String origin, String refreshToken, String userName) throws GeneralSecurityException, IOException {
        String sealed = seal(refreshToken);
        prefs.edit()
                .putString(K_ORIGIN, origin)
                .putString(K_TOKEN, sealed)
                .putString(K_USER, userName)
                .putBoolean(K_APPLY, true)
                .remove(K_REFUSED)
                .remove(K_LOGOUT_ORIGIN)
                .apply();
        cached = new Session(origin, refreshToken, userName);
        lastHandOverMs = 0;
        Log.i(TAG, "Stored the dashboard login for " + origin + " as " + userName);
    }

    public synchronized void clear() {
        String origin = prefs.getString(K_ORIGIN, null);
        SharedPreferences.Editor editor = prefs.edit().clear();
        if (origin != null) editor.putString(K_LOGOUT_ORIGIN, origin);
        editor.apply();
        cached = null;
        lastHandOverMs = 0;
        if (origin != null) Log.i(TAG, "Cleared the dashboard login for " + origin);
    }

    // the usable session for this dashboard or null
    // a dashboard on another origin wipes the session so the token never travels there
    public synchronized Session session(String dashboardUrl) {
        String origin = prefs.getString(K_ORIGIN, null);
        if (origin == null) return null;
        if (!HaLoginRules.sameOrigin(dashboardUrl, origin)) {
            Log.i(TAG, "Dashboard moved away from " + origin);
            clear();
            return null;
        }
        if (prefs.getBoolean(K_REFUSED, false)) return null;
        if (cached != null) return cached;
        try {
            cached = new Session(origin, open(prefs.getString(K_TOKEN, "")), prefs.getString(K_USER, ""));
            return cached;
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            // a lost keystore key cannot be repaired here. the controller sends a new token
            Log.w(TAG, "Could not open the stored login", e);
            clear();
            return null;
        }
    }

    public synchronized String state(String dashboardUrl) {
        if (session(dashboardUrl) != null) return STATE_OK;
        return prefs.contains(K_ORIGIN) && prefs.getBoolean(K_REFUSED, false) ? STATE_INVALID : STATE_NONE;
    }

    public synchronized boolean hasLogin() {
        return prefs.contains(K_ORIGIN);
    }

    public synchronized String userName() {
        return prefs.getString(K_USER, null);
    }

    // the session to hand to the page now or null
    // a login page right after the last hand over means ha refused the token and it is marked invalid
    public synchronized Session handOver(String pageUrl, String dashboardUrl) {
        Session session = session(dashboardUrl);
        if (session == null || !HaLoginRules.sameOrigin(pageUrl, session.origin)) return null;
        boolean authorize = HaLoginRules.isAuthorizePage(pageUrl, session.origin);
        boolean apply = prefs.getBoolean(K_APPLY, false);
        if (!authorize && !apply) return null;
        long now = SystemClock.elapsedRealtime();
        if (authorize && HaLoginRules.refused(lastHandOverMs, now)) {
            Log.w(TAG, "Home Assistant refused the login for " + session.origin);
            prefs.edit().putBoolean(K_REFUSED, true).apply();
            cached = null;
            lastHandOverMs = 0;
            ApiHub.stateChanged();
            return null;
        }
        lastHandOverMs = now;
        if (apply) prefs.edit().putBoolean(K_APPLY, false).apply();
        return session;
    }

    // the origin to drop the frontend session from when the page is on it. cleared once taken
    public synchronized String takeLogout(String pageUrl) {
        String origin = prefs.getString(K_LOGOUT_ORIGIN, null);
        if (origin == null || !HaLoginRules.sameOrigin(pageUrl, origin)) return null;
        prefs.edit().remove(K_LOGOUT_ORIGIN).apply();
        return origin;
    }

    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    // iv length then iv then ciphertext
    private static String seal(String plain) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[1 + iv.length + body.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(body, 0, out, 1 + iv.length, body.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static String open(String sealed) throws GeneralSecurityException, IOException {
        byte[] raw = Base64.decode(sealed, Base64.NO_WRAP);
        if (raw.length < 2) throw new IllegalArgumentException("empty");
        int ivLength = raw[0] & 0xff;
        if (raw.length < 1 + ivLength) throw new IllegalArgumentException("short");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, raw, 1, ivLength));
        byte[] plain = cipher.doFinal(raw, 1 + ivLength, raw.length - 1 - ivLength);
        return new String(plain, StandardCharsets.UTF_8);
    }
}
