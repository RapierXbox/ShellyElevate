package me.rapierxbox.shellyelevatev2.api;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

import me.rapierxbox.shellyelevatev2.Constants;

// events of protocol-v1 section 5 raised next to the matching mqtt publish
public final class ApiEvents {
    private static final String TAG = "ApiEvents";
    // ButtonHandler uses this id for the dedicated power button
    private static final int POWER_BUTTON_ID = 140;

    private ApiEvents() {}

    // press types are the mqtt names short long double triple
    public static void button(int buttonId, String pressType) {
        if (!ApiHub.hasController()) return;
        try {
            JSONObject fields = new JSONObject().put("press", press(pressType));
            if (buttonId == POWER_BUTTON_ID) {
                ApiHub.event("power_button", fields);
            } else {
                ApiHub.event("button", fields.put("index", buttonId));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Could not build button event", e);
        }
    }

    public static void swipe(String direction, int fingers) {
        if (!ApiHub.hasController()) return;
        try {
            ApiHub.event("swipe", new JSONObject()
                    .put("direction", direction.toLowerCase(Locale.ROOT))
                    .put("fingers", fingers));
        } catch (JSONException e) {
            Log.w(TAG, "Could not build swipe event", e);
        }
    }

    static String press(String pressType) {
        if (Constants.BUTTON_PRESS_TYPE_SHORT.equals(pressType)) return "single";
        return pressType;
    }
}
