package me.rapierxbox.shellyelevatev2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import me.rapierxbox.shellyelevatev2.api.ClientTokenStore;
import me.rapierxbox.shellyelevatev2.api.TlsIdentity;
import me.rapierxbox.shellyelevatev2.settings.SettingsRegistry;

import java.util.Iterator;

// hands a controller token and optional settings to the app over adb without the on screen code
// the manifest guards it with a permission only the shell user and root hold
// the result data is the certificate fingerprint which am broadcast prints for the installer to pin
public class ProvisionReceiver extends BroadcastReceiver {
    private static final String TAG = "ProvisionReceiver";
    public static final String ACTION = "me.rapierxbox.shellyelevatev2.PROVISION";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        String token = intent.getStringExtra("token");
        String clientId = intent.getStringExtra("client_id");
        String clientName = intent.getStringExtra("client_name");
        if (token == null || token.length() < 16 || clientId == null || clientId.isEmpty()) {
            Log.w(TAG, "Provisioning without token or client id ignored");
            setResultCode(1);
            return;
        }
        String settings = intent.getStringExtra("settings");
        if (settings != null && !settings.isEmpty()) {
            try {
                // applied like PATCH /settings. keys this version does not know are skipped
                // so a profile from a newer display still provisions
                JSONObject patch = new JSONObject();
                JSONObject given = new JSONObject(settings);
                for (Iterator<String> it = given.keys(); it.hasNext(); ) {
                    String key = it.next();
                    if (SettingsRegistry.get(key) != null) patch.put(key, given.get(key));
                    else Log.w(TAG, "Skipping unknown setting " + key);
                }
                new SettingsParser().applyPatch(patch);
            } catch (JSONException | IllegalArgumentException e) {
                Log.w(TAG, "Provisioned settings rejected", e);
                setResultCode(2);
                return;
            }
        }
        ClientTokenStore.get(context).store(clientId, clientName != null ? clientName : "Controller", token);
        // the keystore key may still be generating on first start so wait for it off the main thread
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                result.setResultData(TlsIdentity.get().fingerprint());
                result.setResultCode(0);
                Log.i(TAG, "Provisioned " + clientName);
            } catch (Exception e) {
                Log.e(TAG, "No TLS identity for the provisioning answer", e);
                result.setResultCode(3);
            } finally {
                result.finish();
            }
        }, "Provision").start();
    }
}
