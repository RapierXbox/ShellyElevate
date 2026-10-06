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
import me.rapierxbox.shellyelevatev2.FloatTextPref
import me.rapierxbox.shellyelevatev2.IntTextPref
import me.rapierxbox.shellyelevatev2.PrefBinding
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.SettingsSection
import me.rapierxbox.shellyelevatev2.SliderPref
import me.rapierxbox.shellyelevatev2.SpinnerIdPref
import me.rapierxbox.shellyelevatev2.SwitchPref
import me.rapierxbox.shellyelevatev2.TextPref
import me.rapierxbox.shellyelevatev2.ValueBinding
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
    ): OptionControl {
        fun inflate(layout: Int): View = inflater.inflate(layout, parent, false)
        return when (option) {
            is ModuleOption.Toggle -> toggleControl(option, inflate(R.layout.settings_option_toggle))
            is ModuleOption.Text -> textControl(option, inflate(R.layout.settings_option_text), option.hintRes, option.inputType) {
                TextPref(it, option.key, option.default, option.trim)
            }
            is ModuleOption.Url -> textControl(option, inflate(R.layout.settings_option_text), option.hintRes,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) {
                TextPref(it, option.key, option.default, trim = true)
            }
            is ModuleOption.IntNumber -> textControl(option, inflate(R.layout.settings_option_text), null,
                InputType.TYPE_CLASS_NUMBER or (if (option.min < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)) {
                IntTextPref(it, option.key, option.default, option.min, option.max)
            }
            is ModuleOption.FloatNumber -> textControl(option, inflate(R.layout.settings_option_text), null,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED) {
                FloatTextPref(it, option.key, option.default, option.min, option.max)
            }
            is ModuleOption.Slider -> sliderControl(option, inflate(R.layout.settings_option_slider))
            is ModuleOption.Choice -> choiceControl(option, inflate(R.layout.settings_option_choice))
            is ModuleOption.AppPicker -> AppControl(option, inflate(R.layout.settings_option_app))
            is ModuleOption.Action -> ActionControl(option, inflate(R.layout.settings_option_action), actions)
        }
    }

    private fun toggleControl(toggle: ModuleOption.Toggle, row: View): OptionControl {
        val switch: MaterialSwitch = row.findViewById(R.id.optionSwitch)
        switch.setText(toggle.titleRes)
        return BoundControl(toggle, row, SwitchPref(switch, toggle.key, toggle.default)) { notify ->
            switch.setOnCheckedChangeListener { _, _ -> notify() }
        }
    }

    private fun textControl(
        option: ModuleOption,
        row: View,
        hintRes: Int?,
        inputType: Int,
        binding: (EditText) -> ValueBinding
    ): OptionControl {
        val edit: EditText = row.findViewById(R.id.optionEdit)
        row.findViewById<TextView>(R.id.optionTitle).setText(option.titleRes)
        edit.inputType = inputType
        hintRes?.let { edit.setHint(it) }
        return BoundControl(option, row, binding(edit)) { notify -> edit.doAfterTextChanged { notify() } }
    }

    private fun sliderControl(slider: ModuleOption.Slider, row: View): OptionControl {
        val view: Slider = row.findViewById(R.id.optionSlider)
        row.findViewById<TextView>(R.id.optionTitle).setText(slider.titleRes)
        view.valueFrom = slider.min.toFloat()
        view.valueTo = slider.max.toFloat()
        view.stepSize = slider.step.toFloat()
        view.value = slider.default.toFloat()
        return BoundControl(slider, row, SliderPref(view, slider.key, slider.default)) { notify ->
            view.addOnChangeListener { _, _, _ -> notify() }
        }
    }

    private fun choiceControl(choice: ModuleOption.Choice, row: View): OptionControl {
        val spinner: Spinner = row.findViewById(R.id.optionSpinner)
        row.findViewById<TextView>(R.id.optionTitle).setText(choice.titleRes)
        val labels = choice.entries.map { row.context.getString(it.labelRes) }
        spinner.adapter = ArrayAdapter(row.context, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val binding = SpinnerIdPref(spinner, choice.key, choice.entries.map { it.id }, choice.default)
        return BoundControl(choice, row, binding) { notify ->
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = notify()
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
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

// a module option backed by one of the shared pref bindings
private class BoundControl(
    option: ModuleOption,
    row: View,
    private val binding: ValueBinding,
    watch: (notify: () -> Unit) -> Unit
) : OptionControl(option, row) {
    init {
        watch { notifyChanged() }
    }

    override fun value(): Any? = binding.value()
    override fun setValue(value: Any?) {
        binding.setValue(value)
        // a spinner selection only reports to its listener after the next layout pass
        notifyChanged()
    }
    override fun load(prefs: SharedPreferences) = binding.load(prefs)
    override fun save(editor: SharedPreferences.Editor) = binding.save(editor)
}

private class AppControl(private val picker: ModuleOption.AppPicker, row: View) : OptionControl(picker, row) {
    private val icon: ImageView = row.findViewById(R.id.optionIcon)
    private val label: TextView = row.findViewById(R.id.optionValue)
    private var packageName = ""
    private var component = ""
    private val catalogListener = { showSelection() }

    init {
        row.findViewById<TextView>(R.id.optionTitle).setText(picker.titleRes)
        row.findViewById<View>(R.id.optionPicker).setOnClickListener {
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
