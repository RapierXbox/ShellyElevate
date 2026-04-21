package me.rapierxbox.shellyelevatev2

import android.content.SharedPreferences
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import androidx.core.content.edit
import androidx.core.view.isVisible
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

sealed interface PrefBinding {
    fun load(prefs: SharedPreferences)
    fun save(editor: SharedPreferences.Editor)
}

class SwitchPref(
    private val view: MaterialSwitch,
    private val key: String,
    private val default: Boolean
) : PrefBinding {
    override fun load(prefs: SharedPreferences) {
        view.isChecked = prefs.getBoolean(key, default)
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putBoolean(key, view.isChecked)
    }
}

class TextPref(
    private val view: EditText,
    private val key: String,
    private val default: String = "",
    private val trim: Boolean = false
) : PrefBinding {
    override fun load(prefs: SharedPreferences) {
        view.setText(prefs.getString(key, default))
    }
    override fun save(editor: SharedPreferences.Editor) {
        val value = view.text.toString().let { if (trim) it.trim() else it }
        editor.putString(key, value)
    }
}

class IntTextPref(
    private val view: EditText,
    private val key: String,
    private val default: Int,
    private val min: Int = Int.MIN_VALUE
) : PrefBinding {
    override fun load(prefs: SharedPreferences) {
        view.setText(prefs.getInt(key, default).toString())
    }
    override fun save(editor: SharedPreferences.Editor) {
        val parsed = view.text.toString().toIntOrNull() ?: default
        editor.putInt(key, parsed.coerceAtLeast(min))
    }
}

class FloatTextPref(
    private val view: EditText,
    private val key: String,
    private val default: Float
) : PrefBinding {
    override fun load(prefs: SharedPreferences) {
        view.setText(prefs.getFloat(key, default).toString())
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putFloat(key, view.text.toString().toFloatOrNull() ?: default)
    }
}

class SliderPref(
    private val view: Slider,
    private val key: String,
    private val default: Int,
    live: ((Int) -> Unit)? = null
) : PrefBinding {
    init {
        if (live != null) {
            view.addOnChangeListener { _, value, _ -> live(value.toInt()) }
        }
    }
    override fun load(prefs: SharedPreferences) {
        view.value = prefs.getInt(key, default).toFloat()
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putInt(key, view.value.toInt())
    }
}

class SpinnerPref(
    private val view: Spinner,
    private val key: String,
    private val default: Int
) : PrefBinding {
    override fun load(prefs: SharedPreferences) {
        view.setSelection(prefs.getInt(key, default))
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putInt(key, view.selectedItemPosition)
    }
}

class SettingsBinder(private val prefs: SharedPreferences) {
    private val bindings = mutableListOf<PrefBinding>()
    private val toggleActions = mutableMapOf<MaterialSwitch, MutableList<(Boolean) -> Unit>>()

    operator fun PrefBinding.unaryPlus() { bindings += this }

    fun visibleWhen(switch: MaterialSwitch, vararg targets: View) {
        addToggle(switch) { checked -> targets.forEach { it.isVisible = checked } }
    }

    fun visibleWhenNot(switch: MaterialSwitch, vararg targets: View) {
        addToggle(switch) { checked -> targets.forEach { it.isVisible = !checked } }
    }

    private fun addToggle(switch: MaterialSwitch, action: (Boolean) -> Unit) {
        toggleActions.getOrPut(switch) { mutableListOf() } += action
    }

    fun loadAll() {
        bindings.forEach { it.load(prefs) }
        toggleActions.forEach { (switch, actions) ->
            actions.forEach { it(switch.isChecked) }
            switch.setOnCheckedChangeListener { _, checked -> actions.forEach { it(checked) } }
        }
    }

    fun saveAll() {
        prefs.edit { bindings.forEach { it.save(this) } }
    }
}
