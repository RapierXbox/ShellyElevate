package me.rapierxbox.shellyelevatev2.switcher

import android.content.SharedPreferences
import android.view.View
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import me.rapierxbox.shellyelevatev2.Constants.APP_SWITCHER_GESTURE_DEFAULT
import me.rapierxbox.shellyelevatev2.Constants.APP_SWITCHER_GESTURE_OFF
import me.rapierxbox.shellyelevatev2.Constants.SP_APP_SWITCHER_GESTURE
import me.rapierxbox.shellyelevatev2.Constants.SP_APP_SWITCHER_PREVIEWS
import me.rapierxbox.shellyelevatev2.PrefBinding
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.SettingsSection
import me.rapierxbox.shellyelevatev2.SwitchPref
import me.rapierxbox.shellyelevatev2.helper.touch.SwipeClassifier.Direction
import me.rapierxbox.shellyelevatev2.helper.touch.SwipeClassifier.gestureId

// finger count and direction buttons for the switcher gesture plus the preview toggle
// stored as one gesture id like swipe_2_up so the http api and the bulk tool keep one key
class AppSwitcherSettings(
    private val fingers: MaterialButtonToggleGroup,
    private val direction: MaterialButtonToggleGroup,
    private val directionRow: View,
    previews: MaterialSwitch
) : SettingsSection {

    private val fingerButtons = mapOf(
        R.id.appSwitcherFingers2 to 2,
        R.id.appSwitcherFingers3 to 3,
        R.id.appSwitcherFingers4 to 4,
        R.id.appSwitcherFingers5 to 5,
    )
    private val directionButtons = mapOf(
        R.id.appSwitcherDirectionUp to Direction.UP,
        R.id.appSwitcherDirectionDown to Direction.DOWN,
        R.id.appSwitcherDirectionLeft to Direction.LEFT,
        R.id.appSwitcherDirectionRight to Direction.RIGHT,
    )

    init {
        // a direction only matters while the gesture is on
        fingers.addOnButtonCheckedListener { _, id, checked ->
            if (checked) directionRow.isVisible = id != R.id.appSwitcherFingersOff
        }
    }

    private val gesture = object : PrefBinding {
        override fun load(prefs: SharedPreferences) {
            val id = prefs.getString(SP_APP_SWITCHER_GESTURE, APP_SWITCHER_GESTURE_DEFAULT) ?: APP_SWITCHER_GESTURE_DEFAULT
            val parts = id.split('_')
            val count = parts.getOrNull(1)?.toIntOrNull()
            val dir = Direction.entries.firstOrNull { it.name.equals(parts.getOrNull(2), ignoreCase = true) }
            val fingerButton = fingerButtons.entries.firstOrNull { it.value == count }?.key
            if (id == APP_SWITCHER_GESTURE_OFF || fingerButton == null || dir == null) {
                fingers.check(R.id.appSwitcherFingersOff)
                direction.check(R.id.appSwitcherDirectionUp)
                directionRow.isVisible = false
                return
            }
            fingers.check(fingerButton)
            direction.check(directionButtons.entries.first { it.value == dir }.key)
            directionRow.isVisible = true
        }

        override fun save(editor: SharedPreferences.Editor) {
            val count = fingerButtons[fingers.checkedButtonId]
            val dir = directionButtons[direction.checkedButtonId] ?: Direction.UP
            editor.putString(SP_APP_SWITCHER_GESTURE, if (count == null) APP_SWITCHER_GESTURE_OFF else gestureId(count, dir))
        }
    }

    override val bindings: List<PrefBinding> = listOf(
        gesture,
        SwitchPref(previews, SP_APP_SWITCHER_PREVIEWS, true),
    )
}
