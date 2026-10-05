package me.rapierxbox.shellyelevatev2.switcher

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.google.android.material.materialswitch.MaterialSwitch
import me.rapierxbox.shellyelevatev2.Constants.APP_SWITCHER_GESTURE_DEFAULT
import me.rapierxbox.shellyelevatev2.Constants.APP_SWITCHER_GESTURE_OFF
import me.rapierxbox.shellyelevatev2.Constants.SP_APP_SWITCHER_GESTURE
import me.rapierxbox.shellyelevatev2.Constants.SP_APP_SWITCHER_PREVIEWS
import me.rapierxbox.shellyelevatev2.PrefBinding
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.SettingsSection
import me.rapierxbox.shellyelevatev2.SpinnerIdPref
import me.rapierxbox.shellyelevatev2.SwitchPref
import me.rapierxbox.shellyelevatev2.helper.touch.SwipeClassifier

// gesture picker and preview toggle for the app switcher
class AppSwitcherSettings(context: Context, gesture: Spinner, previews: MaterialSwitch) : SettingsSection {

    private val ids: List<String>

    init {
        // single finger swipes are left out since they collide with scrolling inside apps
        val gestures = (2..5).flatMap { fingers -> SwipeClassifier.Direction.entries.map { fingers to it } }
        ids = listOf(APP_SWITCHER_GESTURE_OFF) + gestures.map { (fingers, dir) -> SwipeClassifier.gestureId(fingers, dir) }
        val labels = listOf(context.getString(R.string.app_switcher_gesture_off)) + gestures.map { (fingers, dir) ->
            context.getString(R.string.app_switcher_gesture_label, fingers, context.getString(directionLabel(dir)))
        }
        gesture.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    override val bindings: List<PrefBinding> = listOf(
        SpinnerIdPref(gesture, SP_APP_SWITCHER_GESTURE, ids, APP_SWITCHER_GESTURE_DEFAULT),
        SwitchPref(previews, SP_APP_SWITCHER_PREVIEWS, true),
    )

    private fun directionLabel(direction: SwipeClassifier.Direction) = when (direction) {
        SwipeClassifier.Direction.UP -> R.string.swipe_direction_up
        SwipeClassifier.Direction.DOWN -> R.string.swipe_direction_down
        SwipeClassifier.Direction.LEFT -> R.string.swipe_direction_left
        SwipeClassifier.Direction.RIGHT -> R.string.swipe_direction_right
    }
}
