package me.rapierxbox.shellyelevatev2.display.options

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import me.rapierxbox.shellyelevatev2.PrefBinding
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.SettingsSection
import me.rapierxbox.shellyelevatev2.switcher.AppCatalog
import me.rapierxbox.shellyelevatev2.switcher.AppPickerDialog

// turns a list of module options into settings rows plus the bindings that load and save them
class ModuleOptionRenderer(private val context: Context) {

    fun render(options: List<ModuleOption>): RenderedOptions {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val inflater = LayoutInflater.from(context)
        val rendered = RenderedOptions(context, container)
        for (option in options) {
            val control = createControl(inflater, container, option, rendered)
            control.row.findViewById<TextView?>(R.id.optionSummary)?.let { summary ->
                option.summaryRes?.let {
                    summary.setText(it)
                    summary.isVisible = true
                }
            }
            container.addView(control.row)
            rendered.add(control)
        }
        return rendered
    }

    private fun createControl(
        inflater: LayoutInflater,
        parent: ViewGroup,
        option: ModuleOption,
        actions: ActionContext
    ): OptionControl = when (option) {
        is ModuleOption.Toggle -> ToggleControl(option, inflater.inflate(R.layout.settings_option_toggle, parent, false))
        is ModuleOption.Text -> TextControl(option, inflater.inflate(R.layout.settings_option_text, parent, false),
            option.default, option.hintRes, option.inputType, option.trim)
        is ModuleOption.Url -> TextControl(option, inflater.inflate(R.layout.settings_option_text, parent, false),
            option.default, option.hintRes, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, trim = true)
        is ModuleOption.IntNumber -> IntControl(option, inflater.inflate(R.layout.settings_option_text, parent, false))
        is ModuleOption.FloatNumber -> FloatControl(option, inflater.inflate(R.layout.settings_option_text, parent, false))
        is ModuleOption.Slider -> SliderControl(option, inflater.inflate(R.layout.settings_option_slider, parent, false))
        is ModuleOption.Choice -> ChoiceControl(option, inflater.inflate(R.layout.settings_option_choice, parent, false))
        is ModuleOption.AppPicker -> AppControl(option, inflater.inflate(R.layout.settings_option_app, parent, false))
        is ModuleOption.Action -> ActionControl(option, inflater.inflate(R.layout.settings_option_action, parent, false), actions)
    }
}

// the rendered rows of one option list
class RenderedOptions internal constructor(
    override val context: Context,
    val view: LinearLayout
) : SettingsSection, ActionContext {

    private val controls = LinkedHashMap<String, OptionControl>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override val bindings: List<PrefBinding> get() = controls.values.toList()

    internal fun add(control: OptionControl) {
        controls[control.option.key] = control
        control.onChange { applyVisibility() }
    }

    override fun onLoaded() = applyVisibility()

    fun has(key: String) = controls.containsKey(key)

    override fun value(key: String): Any? = controls[key]?.value()

    override fun setValue(key: String, value: Any?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            controls[key]?.setValue(value)
        } else {
            mainHandler.post { controls[key]?.setValue(value) }
        }
    }

    private fun applyVisibility() {
        for (control in controls.values) {
            val rule = control.option.visibleWhen ?: continue
            control.row.isVisible = rule.predicate(controls[rule.key]?.value())
        }
    }
}

internal abstract class OptionControl(val option: ModuleOption, val row: View) : PrefBinding {
    private val listeners = mutableListOf<() -> Unit>()

    abstract fun value(): Any?
    abstract fun setValue(value: Any?)

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    protected fun notifyChanged() = listeners.forEach { it() }
}

private class ToggleControl(private val toggle: ModuleOption.Toggle, row: View) : OptionControl(toggle, row) {
    private val switch: MaterialSwitch = row.findViewById(R.id.optionSwitch)

    init {
        switch.setText(toggle.titleRes)
        switch.setOnCheckedChangeListener { _, _ -> notifyChanged() }
    }

    override fun value(): Any = switch.isChecked
    override fun setValue(value: Any?) {
        switch.isChecked = value == true
    }
    override fun load(prefs: SharedPreferences) {
        switch.isChecked = prefs.getBoolean(option.key, toggle.default)
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putBoolean(option.key, switch.isChecked)
    }
}

private open class TextControl(
    option: ModuleOption,
    row: View,
    private val default: String,
    hintRes: Int?,
    inputType: Int,
    private val trim: Boolean
) : OptionControl(option, row) {
    protected val edit: EditText = row.findViewById(R.id.optionEdit)

    init {
        row.findViewById<TextView>(R.id.optionTitle).setText(option.titleRes)
        edit.inputType = inputType
        hintRes?.let { edit.setHint(it) }
        edit.doAfterTextChanged { notifyChanged() }
    }

    protected fun text(): String = edit.text.toString().let { if (trim) it.trim() else it }

    override fun value(): Any? = text()
    override fun setValue(value: Any?) {
        edit.setText(value?.toString() ?: "")
    }
    override fun load(prefs: SharedPreferences) {
        edit.setText(prefs.getString(option.key, default))
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putString(option.key, text())
    }
}

private class IntControl(private val number: ModuleOption.IntNumber, row: View) : TextControl(
    number, row, number.default.toString(), null,
    InputType.TYPE_CLASS_NUMBER or (if (number.min < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0), trim = true
) {
    private fun parsed(): Int = (text().toIntOrNull() ?: number.default).coerceIn(number.min, number.max)

    override fun value(): Any = parsed()
    override fun load(prefs: SharedPreferences) {
        // http writes keep the stored type so a long or float can sit here
        val stored = (prefs.all[option.key] as? Number)?.toInt() ?: number.default
        edit.setText(stored.toString())
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putInt(option.key, parsed())
    }
}

private class FloatControl(private val number: ModuleOption.FloatNumber, row: View) : TextControl(
    number, row, number.default.toString(), null,
    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED, trim = true
) {
    private fun parsed(): Float = (text().toFloatOrNull() ?: number.default).coerceIn(number.min, number.max)

    override fun value(): Any = parsed()
    override fun load(prefs: SharedPreferences) {
        // a whole number written over http may sit as an int so read the raw value
        val stored = (prefs.all[option.key] as? Number)?.toFloat() ?: number.default
        edit.setText(stored.toString())
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putFloat(option.key, parsed())
    }
}

private class SliderControl(private val slider: ModuleOption.Slider, row: View) : OptionControl(slider, row) {
    private val view: Slider = row.findViewById(R.id.optionSlider)

    init {
        row.findViewById<TextView>(R.id.optionTitle).setText(slider.titleRes)
        view.valueFrom = slider.min.toFloat()
        view.valueTo = slider.max.toFloat()
        view.stepSize = slider.step.toFloat()
        view.value = slider.default.toFloat()
        view.addOnChangeListener { _, _, _ -> notifyChanged() }
    }

    private fun snap(value: Int): Float {
        val clamped = value.coerceIn(slider.min, slider.max)
        return (slider.min + (clamped - slider.min) / slider.step * slider.step).toFloat()
    }

    override fun value(): Any = view.value.toInt()
    override fun setValue(value: Any?) {
        view.value = snap((value as? Number)?.toInt() ?: slider.default)
    }
    override fun load(prefs: SharedPreferences) {
        setValue((prefs.all[option.key] as? Number)?.toInt() ?: slider.default)
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putInt(option.key, view.value.toInt())
    }
}

private class ChoiceControl(private val choice: ModuleOption.Choice, row: View) : OptionControl(choice, row) {
    private val spinner: Spinner = row.findViewById(R.id.optionSpinner)
    private val ids = choice.entries.map { it.id }

    init {
        row.findViewById<TextView>(R.id.optionTitle).setText(choice.titleRes)
        val labels = choice.entries.map { row.context.getString(it.labelRes) }
        spinner.adapter = ArrayAdapter(row.context, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = notifyChanged()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun value(): Any = ids.getOrNull(spinner.selectedItemPosition) ?: choice.default
    override fun setValue(value: Any?) {
        val index = ids.indexOf(value).takeIf { it >= 0 } ?: ids.indexOf(choice.default)
        if (index >= 0) {
            spinner.setSelection(index)
            // setSelection only reports to the listener after the next layout pass
            notifyChanged()
        }
    }
    override fun load(prefs: SharedPreferences) = setValue(prefs.getString(option.key, choice.default))
    override fun save(editor: SharedPreferences.Editor) {
        editor.putString(option.key, value() as String)
    }
}

private class AppControl(private val picker: ModuleOption.AppPicker, row: View) : OptionControl(picker, row) {
    private val icon: ImageView = row.findViewById(R.id.optionIcon)
    private val label: TextView = row.findViewById(R.id.optionValue)
    private var packageName = ""
    private var component = ""
    private val catalogListener = { showSelection() }

    init {
        row.findViewById<TextView>(R.id.optionTitle).setText(picker.titleRes)
        row.findViewById<Button>(R.id.optionButton).setOnClickListener {
            AppPickerDialog.show(row.context) { app ->
                packageName = app.packageName
                component = app.component.flattenToString()
                showSelection()
                notifyChanged()
            }
        }
        // the catalog loads in the background so refresh the label once it lands
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = AppCatalog.addListener(catalogListener)
            override fun onViewDetachedFromWindow(v: View) = AppCatalog.removeListener(catalogListener)
        })
    }

    private fun showSelection() {
        if (packageName.isEmpty()) {
            label.setText(R.string.display_app_none)
            icon.isVisible = false
            return
        }
        val app = AppCatalog.find(packageName)
        label.text = app?.label ?: packageName
        icon.setImageBitmap(app?.icon)
        icon.isVisible = app != null
    }

    override fun value(): Any = packageName
    override fun setValue(value: Any?) {
        packageName = value?.toString() ?: ""
        component = AppCatalog.find(packageName)?.component?.flattenToString() ?: ""
        showSelection()
        notifyChanged()
    }
    override fun load(prefs: SharedPreferences) {
        packageName = prefs.getString(option.key, "") ?: ""
        component = prefs.getString(picker.componentKey, "") ?: ""
        showSelection()
    }
    override fun save(editor: SharedPreferences.Editor) {
        editor.putString(option.key, packageName)
        editor.putString(picker.componentKey, component)
    }
}

private class ActionControl(
    private val action: ModuleOption.Action,
    row: View,
    actions: ActionContext
) : OptionControl(action, row) {
    init {
        row.findViewById<Button>(R.id.optionButton).apply {
            setText(action.titleRes)
            setOnClickListener { action.onClick(actions) }
        }
    }

    override fun value(): Any? = null
    override fun setValue(value: Any?) {}
    override fun load(prefs: SharedPreferences) {}
    override fun save(editor: SharedPreferences.Editor) {}
}
