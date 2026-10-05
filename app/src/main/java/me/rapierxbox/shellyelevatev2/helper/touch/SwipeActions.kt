package me.rapierxbox.shellyelevatev2.helper.touch

import android.os.SystemClock
import android.util.Log
import me.rapierxbox.shellyelevatev2.BuildConfig
import me.rapierxbox.shellyelevatev2.Constants.APP_SWITCHER_GESTURE_DEFAULT
import me.rapierxbox.shellyelevatev2.Constants.APP_SWITCHER_GESTURE_OFF
import me.rapierxbox.shellyelevatev2.Constants.SP_APP_SWITCHER_GESTURE
import me.rapierxbox.shellyelevatev2.Constants.SP_PUBLISH_SWIPE_EVENTS
import me.rapierxbox.shellyelevatev2.Constants.SP_SWITCH_ON_SWIPE
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FIVE_FINGER_DOWN
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FIVE_FINGER_LEFT
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FIVE_FINGER_RIGHT
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FIVE_FINGER_UP
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FOUR_FINGER_DOWN
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FOUR_FINGER_LEFT
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FOUR_FINGER_RIGHT
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_FOUR_FINGER_UP
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_SINGLE
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_THREE_FINGER_DOWN
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_THREE_FINGER_LEFT
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_THREE_FINGER_RIGHT
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_THREE_FINGER_UP
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_TWO_FINGER_DOWN
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_TWO_FINGER_LEFT
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_TWO_FINGER_RIGHT
import me.rapierxbox.shellyelevatev2.Constants.SWIPE_EVENT_TYPE_TWO_FINGER_UP
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.helper.touch.SwipeClassifier.Direction
import me.rapierxbox.shellyelevatev2.helper.touch.SwipeClassifier.Swipe
import me.rapierxbox.shellyelevatev2.switcher.AppSwitcher

// what a recognised swipe does. every swipe source ends up here on the main thread
object SwipeActions {
    private const val TAG = "SwipeActions"
    private const val SWITCHER_DEBOUNCE_MS = 1000L

    private var lastSwitcherOpenMs = 0L

    @JvmStatic
    fun switcherGesture(): String =
        mSharedPreferences.getString(SP_APP_SWITCHER_GESTURE, APP_SWITCHER_GESTURE_DEFAULT) ?: APP_SWITCHER_GESTURE_DEFAULT

    // true when this swipe opens the app switcher
    @JvmStatic
    fun isSwitcherGesture(swipe: Swipe): Boolean =
        swipe.fingers >= 2 && swipe.id == switcherGesture()

    @JvmStatic
    fun dispatch(swipe: Swipe) {
        if (BuildConfig.DEBUG) Log.d(TAG, "swipe ${swipe.id}")

        // the switcher gesture is consumed so it neither toggles nor publishes
        // while the screensaver runs it falls through so the swipe stays a plain event
        if (isSwitcherGesture(swipe) && mScreenSaverManager?.isScreenSaverRunning != true) {
            mScreenSaverManager?.onSwipeFired()
            openSwitcher()
            return
        }

        if (swipe.fingers == 1) {
            if (!mSharedPreferences.getBoolean(SP_SWITCH_ON_SWIPE, true)) return
            val relayIndex = 0
            mDeviceHelper.setRelay(relayIndex, !mDeviceHelper.getRelay(relayIndex))
            val mqtt = mMQTTServer
            if (mqtt != null && mqtt.shouldSend()) mqtt.publishSwipeEvent(SWIPE_EVENT_TYPE_SINGLE)
            return
        }

        val mqtt = mMQTTServer
        if (!mSharedPreferences.getBoolean(SP_PUBLISH_SWIPE_EVENTS, true) || mqtt == null || !mqtt.shouldSend()) return
        mqtt.publishSwipeEvent(eventName(swipe))
        mScreenSaverManager?.onSwipeFired()
    }

    // debounced so a gesture reported by two sources never opens it twice
    @JvmStatic
    fun openSwitcher() {
        if (switcherGesture() == APP_SWITCHER_GESTURE_OFF) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSwitcherOpenMs < SWITCHER_DEBOUNCE_MS || AppSwitcher.isOpen) return
        lastSwitcherOpenMs = now
        AppSwitcher.open(mApplicationContext)
    }

    private fun eventName(swipe: Swipe): String {
        val names = when (swipe.fingers) {
            5 -> arrayOf(SWIPE_EVENT_TYPE_FIVE_FINGER_UP, SWIPE_EVENT_TYPE_FIVE_FINGER_DOWN,
                SWIPE_EVENT_TYPE_FIVE_FINGER_LEFT, SWIPE_EVENT_TYPE_FIVE_FINGER_RIGHT)
            4 -> arrayOf(SWIPE_EVENT_TYPE_FOUR_FINGER_UP, SWIPE_EVENT_TYPE_FOUR_FINGER_DOWN,
                SWIPE_EVENT_TYPE_FOUR_FINGER_LEFT, SWIPE_EVENT_TYPE_FOUR_FINGER_RIGHT)
            3 -> arrayOf(SWIPE_EVENT_TYPE_THREE_FINGER_UP, SWIPE_EVENT_TYPE_THREE_FINGER_DOWN,
                SWIPE_EVENT_TYPE_THREE_FINGER_LEFT, SWIPE_EVENT_TYPE_THREE_FINGER_RIGHT)
            else -> arrayOf(SWIPE_EVENT_TYPE_TWO_FINGER_UP, SWIPE_EVENT_TYPE_TWO_FINGER_DOWN,
                SWIPE_EVENT_TYPE_TWO_FINGER_LEFT, SWIPE_EVENT_TYPE_TWO_FINGER_RIGHT)
        }
        return names[when (swipe.direction) {
            Direction.UP -> 0
            Direction.DOWN -> 1
            Direction.LEFT -> 2
            Direction.RIGHT -> 3
        }]
    }
}
