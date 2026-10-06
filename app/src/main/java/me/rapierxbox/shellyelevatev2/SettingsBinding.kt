package me.rapierxbox.shellyelevatev2

import android.content.SharedPreferences
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import androidx.core.content.edit
import androidx.core.view.isVisible
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

// not sealed so module options in other packages can bring their own bindings
interface PrefBinding {
    fun load(prefs: SharedPreferences)
    fun save(editor: SharedPreferences.Editor)
}

// a binding whose current ui value can be read and replaced without going through prefs
interface ValueBinding : PrefBinding {
    fun value(): Any?
    fun setValue(value: Any?)
}

// a self contained block of settings ui that hands its bindings to the binder
interface SettingsSection {
    val bindings: List<PrefBinding>
    // runs after every binding loaded so visibility rules see the stored values
    fun onLoaded() {}
}

// http writes keep the stored type so a number pref may hold an int a long or a float
private fun SharedPreferences.intOr(key: String, default: Int): Int = try {
    getInt(key, default)
} catch (_: ClassCastException) {
    (all[key] as? Number)?.toInt() ?: default
}

private fun SharedPreferences.floatOr(key: String, default: Float): Float = try {
    getFloat(key, default)
} catch (_: ClassCastException) {
    // rewrite as float right away so other getFloat callers stop failing before settings are saved
    (all[key] as? Number)?.toFloat()?.also { edit { putFloat(key, it) } } ?: default
}

class SwitchPref(
    private val view: MaterialSwitch,
    private val key: String,
    private val default: Boolean,
    live: ((Boolean) -> Unit)? = null
) : ValueBinding {
    init {
        if (live != null) {
            view.setOnCheckedChangeListener { _, checked -> live(checked) }
        }
    }
    override fun value(): Boolean = view.isChecked
    override fun setValue(value: Any?) {
        view.isChecked = value == true
    }
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
) : ValueBinding {
    override fun value(): String = view.text.toString().let { if (trim) it.trim() else it }
    override fun setValue(value: Any?) {
        view.setText(value?.toString() ?: "")
    }
    override fun load(prefs: SharedPreferences) {
        view.setText(prefs.getString(key, default))
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putString(key, value())
    }
}

class IntTextPref(
    private val view: EditText,
    private val key: String,
    private val default: Int,
    private val min: Int = Int.MIN_VALUE,
    private val max: Int = Int.MAX_VALUE
) : ValueBinding {
    override fun value(): Int = (view.text.toString().trim().toIntOrNull() ?: default).coerceIn(min, max)
    override fun setValue(value: Any?) {
        view.setText(value?.toString() ?: "")
    }
    override fun load(prefs: SharedPreferences) {
        view.setText(prefs.intOr(key, default).toString())
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putInt(key, value())
    }
}

class FloatTextPref(
    private val view: EditText,
    private val key: String,
    private val default: Float,
    private val min: Float = -Float.MAX_VALUE,
    private val max: Float = Float.MAX_VALUE
) : ValueBinding {
    override fun value(): Float = (view.text.toString().trim().toFloatOrNull() ?: default).coerceIn(min, max)
    override fun setValue(value: Any?) {
        view.setText(value?.toString() ?: "")
    }
    override fun load(prefs: SharedPreferences) {
        view.setText(prefs.floatOr(key, default).toString())
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putFloat(key, value())
    }
}

class SliderPref(
    private val view: Slider,
    private val key: String,
    private val default: Int,
    live: ((Int) -> Unit)? = null
) : ValueBinding {
    init {
        if (live != null) {
            // fromUser guard so load() setting the value doesnt fire the live preview
            // otherwise loading the brightness sliders dims the settings screen
            view.addOnChangeListener { _, value, fromUser -> if (fromUser) live(value.toInt()) }
        }
    }

    // the slider throws on values off its range or step so pull stored values onto it
    private fun snap(value: Int): Float {
        val from = view.valueFrom
        val clamped = value.toFloat().coerceIn(from, view.valueTo)
        val step = view.stepSize
        return if (step > 0f) from + ((clamped - from) / step).toInt() * step else clamped
    }

    override fun value(): Int = view.value.toInt()
    override fun setValue(value: Any?) {
        view.value = snap((value as? Number)?.toInt() ?: default)
    }
    override fun load(prefs: SharedPreferences) {
        view.value = snap(prefs.intOr(key, default))
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

// stores a stable string id instead of the position so reordering entries never remaps values
class SpinnerIdPref(
    private val view: Spinner,
    private val key: String,
    private val ids: List<String>,
    private val default: String
) : ValueBinding {
    override fun value(): String = ids.getOrNull(view.selectedItemPosition) ?: default
    override fun setValue(value: Any?) {
        val index = ids.indexOf(value).takeIf { it >= 0 } ?: ids.indexOf(default)
        if (index >= 0) view.setSelection(index)
    }
    override fun load(prefs: SharedPreferences) = setValue(prefs.getString(key, default))
    override fun save(editor: SharedPreferences.Editor) {
        editor.putString(key, value())
    }
}

class SettingsBinder(private val prefs: SharedPreferences) {
    private val bindings = mutableListOf<PrefBinding>()
    private val sections = mutableListOf<SettingsSection>()
    private val toggleActions = mutableMapOf<MaterialSwitch, MutableList<(Boolean) -> Unit>>()

    operator fun PrefBinding.unaryPlus() { bindings += this }

    operator fun SettingsSection.unaryPlus() {
        sections += this
        this@SettingsBinder.bindings += bindings
    }

    fun visibleWhen(switch: MaterialSwitch, vararg targets: View) {
        addToggle(switch) { checked -> targets.forEach { it.isVisible = checked } }
    }

    fun visibleWhenNot(switch: MaterialSwitch, vararg targets: View) {
        addToggle(switch) { checked -> targets.forEach { it.isVisible = !checked } }
    }

    // register extra toggle actions through the binder because a raw
    // setOnCheckedChangeListener would replace the listener loadAll installs
    fun onToggle(switch: MaterialSwitch, action: (Boolean) -> Unit) = addToggle(switch, action)

    private fun addToggle(switch: MaterialSwitch, action: (Boolean) -> Unit) {
        toggleActions.getOrPut(switch) { mutableListOf() } += action
    }

    // what the page would write right after loading. only keys that differ from it are saved
    // so a value the api mqtt or another screen wrote meanwhile is not overwritten with the old one
    private var loaded: Map<String, Any?> = emptyMap()

    fun loadAll() {
        bindings.forEach { it.load(prefs) }
        toggleActions.forEach { (switch, actions) ->
            actions.forEach { it(switch.isChecked) }
            switch.setOnCheckedChangeListener { _, checked -> actions.forEach { it(checked) } }
        }
        sections.forEach { it.onLoaded() }
        loaded = record()
    }

    // lets several binders share one editor and one disk write
    fun saveTo(editor: SharedPreferences.Editor) {
        for ((key, value) in record()) {
            if (loaded.containsKey(key) && loaded[key] == value) continue
            when (value) {
                RecordingEditor.REMOVED -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
    }

    private fun record(): Map<String, Any?> = RecordingEditor().also { r -> bindings.forEach { it.save(r) } }.values
}

// collects the writes of the bindings without touching the prefs
private class RecordingEditor : SharedPreferences.Editor {
    val values = linkedMapOf<String, Any?>()

    override fun putString(key: String, value: String?) = apply { values[key] = value ?: REMOVED }
    override fun putStringSet(key: String, value: MutableSet<String>?) = apply { values[key] = value?.toSet() ?: REMOVED }
    override fun putInt(key: String, value: Int) = apply { values[key] = value }
    override fun putLong(key: String, value: Long) = apply { values[key] = value }
    override fun putFloat(key: String, value: Float) = apply { values[key] = value }
    override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
    override fun remove(key: String) = apply { values[key] = REMOVED }
    override fun clear() = this
    override fun commit() = true
    override fun apply() {}

    companion object {
        val REMOVED = Any()
    }
}
