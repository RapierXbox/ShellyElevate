package me.rapierxbox.shellyelevatev2.api;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED;
import static me.rapierxbox.shellyelevatev2.Constants.SP_BLE_SCANNER_ENABLED;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import me.rapierxbox.shellyelevatev2.bluetooth.BleScanner;

// ble adverts to the paired controller on websocket binary channel 0x02 (protocol-v1 section 5)
// listens to the scanner only while bleScannerEnabled is on and a controller is connected
public final class BleChannel {
    private static final String TAG = "BleChannel";

    public static final int CHANNEL = 0x02;
    // frames stay small so one burst never holds the socket for long
    static final int MAX_FRAME_BYTES = 8 * 1024;
    private static final int MAX_DATA_BYTES = 255;

    private static final Object lock = new Object();
    private static Context appContext;
    private static BroadcastReceiver settingsReceiver;
    private static ApiHub.ControllerListener controllerListener;
    private static boolean listening;

    private static final BleScanner.Listener scanListener = ads -> {
        for (byte[] frame : encode(ads)) {
            ApiHub.sendBinary(CHANNEL, frame, 0, frame.length);
        }
    };

    // a scan that cannot find anything while forwarding is on
    private static final ApiHub.StateProvider stateProvider = state -> {
        if (mSharedPreferences.getBoolean(SP_BLE_SCANNER_ENABLED, false)) {
            state.put("ble.error", BleScanner.get().getScanBlockedReason());
        }
    };

    private BleChannel() {}

    public static void start(Context context) {
        synchronized (lock) {
            if (settingsReceiver != null) return;
            appContext = context.getApplicationContext();
            settingsReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) { apply(); }
            };
            LocalBroadcastManager.getInstance(appContext)
                    .registerReceiver(settingsReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));
            controllerListener = connected -> apply();
            ApiHub.addControllerListener(controllerListener);
            ApiHub.addStateProvider(stateProvider);
        }
        apply();
    }

    public static void stop() {
        synchronized (lock) {
            if (settingsReceiver != null) {
                LocalBroadcastManager.getInstance(appContext).unregisterReceiver(settingsReceiver);
                settingsReceiver = null;
            }
            if (controllerListener != null) {
                ApiHub.removeControllerListener(controllerListener);
                controllerListener = null;
            }
            ApiHub.removeStateProvider(stateProvider);
            setListening(false);
        }
    }

    private static void apply() {
        synchronized (lock) {
            if (settingsReceiver == null) return;
            boolean want = mSharedPreferences.getBoolean(SP_BLE_SCANNER_ENABLED, false) && ApiHub.hasController();
            setListening(want);
        }
    }

    // must hold lock
    private static void setListening(boolean want) {
        if (want == listening) return;
        listening = want;
        if (want) {
            BleScanner.get().addListener(scanListener);
        } else if (mSharedPreferences.getBoolean(SP_BLE_SCANNER_ENABLED, false)) {
            // only the controller went away. a quick reconnect then reuses the scan
            // instead of spending a start under the os scan throttle
            BleScanner.get().removeListenerKeepWarm(scanListener);
        } else {
            BleScanner.get().removeListener(scanListener);
        }
        Log.i(TAG, want ? "forwarding ble adverts" : "ble forwarding stopped");
    }

    // record := address[6] big endian  address_type[1]  rssi[1] int8  length[1]  data[length]
    // returns the payloads without the channel byte split at MAX_FRAME_BYTES
    public static List<byte[]> encode(List<BleScanner.RawAd> ads) {
        List<byte[]> frames = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (BleScanner.RawAd ad : ads) {
            byte[] data = ad.data;
            if (data == null || data.length > MAX_DATA_BYTES) continue;
            int size = 9 + data.length;
            if (out.size() > 0 && out.size() + size > MAX_FRAME_BYTES) {
                frames.add(out.toByteArray());
                out.reset();
            }
            for (int shift = 40; shift >= 0; shift -= 8) out.write((int) (ad.address >> shift) & 0xff);
            // only public and random are defined on the wire
            out.write(ad.addressType == 1 ? 1 : 0);
            out.write(ad.rssi & 0xff);
            out.write(data.length);
            out.write(data, 0, data.length);
        }
        if (out.size() > 0) frames.add(out.toByteArray());
        return frames;
    }
}
