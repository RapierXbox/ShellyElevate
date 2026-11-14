package me.rapierxbox.shellyelevatev2.helper.touch

import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import me.rapierxbox.shellyelevatev2.BuildConfig
import me.rapierxbox.shellyelevatev2.Constants.GESTURE_ACTION_NONE
import me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_REFRESH
import me.rapierxbox.shellyelevatev2.Constants.SP_GESTURE_ACTION_FORMAT
import me.rapierxbox.shellyelevatev2.Constants.SP_PUBLISH_GESTURE_EVENTS
import me.rapierxbox.shellyelevatev2.SettingsActivity
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.api.ApiEvents
import me.rapierxbox.shellyelevatev2.helper.touch.GestureClassifier.Gesture
import me.rapierxbox.shellyelevatev2.helper.touch.GestureClassifier.Kind
import me.rapierxbox.shellyelevatev2.switcher.AppSwitcher

// what a gesture does besides its event. every gesture that can carry an action has one setting
// gestureAction_<gesture id> holding one of the action ids below. called on the main thread
object GestureActions {
    private const val TAG = "GestureActions"

    const val ACTION_SETTINGS = "settings"
    const val ACTION_APP_SWITCHER = "app_switcher"
    const val ACTION_RELOAD = "reload"
    const val ACTION_WAKE = "wake"
    const val ACTION_SLEEP = "sleep"
    const val ACTION_RELAY_TOGGLE_PREFIX = "relay_toggle_"

    // relays offered in the settings. a relay the device does not have is ignored by DeviceHelper
    const val MAX_RELAYS = 2

    private val DIRECTIONS = arrayOf("up", "down", "left", "right")

    @JvmStatic
    fun settingKey(gestureId: String): String = String.format(SP_GESTURE_ACTION_FORMAT, gestureId)

    // gestures that can carry an action. plain one finger taps and long presses are left out on purpose
    // since they belong to the dashboard under the finger
    @JvmStatic
    fun assignableIds(): List<String> {
        val ids = ArrayList<String>()
        for (fingers in 2..GestureClassifier.MAX_FINGERS) {
            for (direction in DIRECTIONS) ids += "swipe_${fingers}_$direction"
        }
        for (fingers in 2..GestureClassifier.MAX_FINGERS) ids += Gesture(Kind.TAP, fingers).id
        ids += Gesture(Kind.DOUBLE_TAP, 1).id
        for (fingers in 2..GestureClassifier.MAX_FINGERS) ids += Gesture(Kind.LONG_PRESS, fingers).id
        for (edge in GestureClassifier.Edge.entries) ids += Gesture(Kind.EDGE, 1, edge).id
        return ids
    }

    // settings label of a gesture id like "3 finger tap" or "Swipe in from the left edge"
    @JvmStatic
    fun label(gestureId: String): String {
        val parts = gestureId.split('_')
        return when {
            gestureId.startsWith("swipe_") && parts.size == 3 -> "${parts[1]} finger swipe ${parts[2]}"
            gestureId.startsWith("tap_") -> "${parts.last()} finger tap"
            gestureId.startsWith("double_tap_") -> "Double tap"
            gestureId.startsWith("long_press_") -> "${parts.last()} finger long press"
            gestureId.startsWith("edge_") -> "Swipe in from the ${parts.last()} edge"
            else -> gestureId
        }
    }

    // action ids in the order the settings show them
    @JvmStatic
    fun actionIds(): List<String> {
        val ids = arrayListOf(
            GESTURE_ACTION_NONE, ACTION_SETTINGS, ACTION_APP_SWITCHER, ACTION_RELOAD, ACTION_WAKE, ACTION_SLEEP
        )
        for (relay in 0 until MAX_RELAYS) ids += ACTION_RELAY_TOGGLE_PREFIX + relay
        return ids
    }

    @JvmStatic
    fun actionLabel(actionId: String): String = when {
        actionId == GESTURE_ACTION_NONE -> "Event only"
        actionId == ACTION_SETTINGS -> "Open settings"
        actionId == ACTION_APP_SWITCHER -> "Open app switcher"
        actionId == ACTION_RELOAD -> "Reload dashboard"
        actionId == ACTION_WAKE -> "Wake screen"
        actionId == ACTION_SLEEP -> "Screen to sleep"
        actionId.startsWith(ACTION_RELAY_TOGGLE_PREFIX) ->
            "Toggle relay ${(actionId.removePrefix(ACTION_RELAY_TOGGLE_PREFIX).toIntOrNull() ?: 0) + 1}"
        else -> actionId
    }

    // a tap long press double tap or edge swipe was recognised. inOwnApp is true while one of our own
    // activities is in front where one finger gestures belong to the dashboard
    @JvmStatic
    fun dispatch(gesture: Gesture, inOwnApp: Boolean) {
        // a plain tap is ordinary use of the panel and neither an event nor an action
        if (gesture.kind == Kind.TAP && gesture.fingers == 1) return
        if (BuildConfig.DEBUG) Log.d(TAG, "gesture ${gesture.id}")

        if (mSharedPreferences.getBoolean(SP_PUBLISH_GESTURE_EVENTS, true)) ApiEvents.gesture(gesture.id)

        // one finger long presses are an event only and a double tap acts only over other apps
        if (gesture.kind == Kind.LONG_PRESS && gesture.fingers == 1) return
        if (gesture.kind == Kind.DOUBLE_TAP && inOwnApp) return
        runAssigned(gesture.id)
    }

    // runs the action set for this gesture id. true when one ran
    @JvmStatic
    fun runAssigned(gestureId: String): Boolean {
        val action = mSharedPreferences.getString(settingKey(gestureId), GESTURE_ACTION_NONE) ?: GESTURE_ACTION_NONE
        if (action == GESTURE_ACTION_NONE) return false
        return try {
            run(action)
        } catch (e: Exception) {
            Log.w(TAG, "action $action for $gestureId failed", e)
            false
        }
    }

    private fun run(action: String): Boolean {
        when {
            action == ACTION_SETTINGS -> {
                // required when starting an activity from a non-activity context
                mApplicationContext.startActivity(
                    Intent(mApplicationContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            action == ACTION_APP_SWITCHER -> if (!AppSwitcher.isOpen) AppSwitcher.open(mApplicationContext)
            action == ACTION_RELOAD ->
                LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(Intent(INTENT_WEBVIEW_REFRESH))
            action == ACTION_WAKE -> mScreenSaverManager?.stopScreenSaver()
            // asked for by hand so it sleeps even with the screensaver setting off
            action == ACTION_SLEEP -> mScreenSaverManager?.startScreenSaver(true)
            action.startsWith(ACTION_RELAY_TOGGLE_PREFIX) -> {
                val relay = action.removePrefix(ACTION_RELAY_TOGGLE_PREFIX).toIntOrNull() ?: return false
                mDeviceHelper.setRelay(relay, !mDeviceHelper.getRelay(relay))
            }
            else -> return false
        }
        return true
    }
}
