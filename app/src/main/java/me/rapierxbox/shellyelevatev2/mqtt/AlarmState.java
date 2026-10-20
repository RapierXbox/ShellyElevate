package me.rapierxbox.shellyelevatev2.mqtt;

import static me.rapierxbox.shellyelevatev2.Constants.INTENT_ALARM_STATE_CHANGED;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;

import android.content.Intent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

// Last alarm_control_panel state received over MQTT (disarmed, armed_home, armed_away, ...).
public final class AlarmState {
    private static volatile String state = "";

    private AlarmState() {}

    public static String get() {
        return state;
    }

    public static void set(String newState) {
        state = newState == null ? "" : newState.trim().toLowerCase();
        LocalBroadcastManager.getInstance(mApplicationContext)
                .sendBroadcast(new Intent(INTENT_ALARM_STATE_CHANGED));
    }
}
