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

    // a press on a wired sw input
    public static void input(int index, String pressType) {
        if (!ApiHub.hasController()) return;
        try {
            ApiHub.event("input", new JSONObject()
                    .put("index", index)
                    .put("press", press(pressType)));
        } catch (JSONException e) {
            Log.w(TAG, "Could not build input event", e);
        }
    }

    // a music url that could not be played. what and extra are the MediaPlayer error codes
    public static void mediaError(String url, int what, int extra) {
        if (!ApiHub.hasController()) return;
        try {
            ApiHub.event("media_error", new JSONObject()
                    .put("url", url != null ? url : JSONObject.NULL)
                    .put("what", what)
                    .put("extra", extra));
        } catch (JSONException e) {
            Log.w(TAG, "Could not build media_error event", e);
        }
    }

    // a self update started by app.update that did not install
    public static void appUpdateFailed(String version, String reason) {
        if (!ApiHub.hasController()) return;
        try {
            ApiHub.event("app_update", new JSONObject()
                    .put("status", "failed")
                    .put("version", version)
                    .put("reason", reason != null ? reason : ""));
        } catch (JSONException e) {
            Log.w(TAG, "Could not build app_update event", e);
        }
    }

    static String press(String pressType) {
        if (Constants.BUTTON_PRESS_TYPE_SHORT.equals(pressType)) return "single";
        return pressType;
    }
}
