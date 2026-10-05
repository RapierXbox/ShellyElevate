package me.rapierxbox.shellyelevatev2.display.options

import android.content.Context
import android.text.InputType
import androidx.annotation.StringRes

// declarative setting of a display module that the renderer turns into a settings row
// keys are persisted so never change them once shipped
sealed class ModuleOption(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int?,
    val visibleWhen: VisibleWhen?
) {
    // name used by the http schema and the bulk settings tool
    abstract val typeName: String
    // null for options that store nothing
    abstract val defaultValue: Any?

    class Toggle(
        key: String,
        @StringRes titleRes: Int,
        val default: Boolean,
        @StringRes summaryRes: Int? = null,
        visibleWhen: VisibleWhen? = null
    ) : ModuleOption(key, titleRes, summaryRes, visibleWhen) {
        override val typeName get() = "bool"
        override val defaultValue get() = default
    }

    class Text(
        key: String,
        @StringRes titleRes: Int,
        val default: String = "",
        @StringRes val hintRes: Int? = null,
        val inputType: Int = InputType.TYPE_CLASS_TEXT,
        val trim: Boolean = false,
        @StringRes summaryRes: Int? = null,
        visibleWhen: VisibleWhen? = null
    ) : ModuleOption(key, titleRes, summaryRes, visibleWhen) {
        override val typeName get() = "string"
        override val defaultValue get() = default
    }

    class Url(
        key: String,
        @StringRes titleRes: Int,
        val default: String = "",
        @StringRes val hintRes: Int? = null,
        @StringRes summaryRes: Int? = null,
        visibleWhen: VisibleWhen? = null
    ) : ModuleOption(key, titleRes, summaryRes, visibleWhen) {
        override val typeName get() = "url"
        override val defaultValue get() = default
    }

    class IntNumber(
        key: String,
        @StringRes titleRes: Int,
        val default: Int,
        val min: Int = Int.MIN_VALUE,
        val max: Int = Int.MAX_VALUE,
        @StringRes summaryRes: Int? = null,
        visibleWhen: VisibleWhen? = null
    ) : ModuleOption(key, titleRes, summaryRes, visibleWhen) {
        override val typeName get() = "int"
        override val defaultValue get() = default
    }

    class FloatNumber(
        key: String,
        @StringRes titleRes: Int,
        val default: Float,
        val min: Float = -Float.MAX_VALUE,
        val max: Float = Float.MAX_VALUE,
        @StringRes summaryRes: Int? = null,
        visibleWhen: VisibleWhen? = null
    ) : ModuleOption(key, titleRes, summaryRes, visibleWhen) {
        override val typeName get() = "float"
        override val defaultValue get() = default
    }

    class Slider(
        key: String,
        @StringRes titleRes: Int,
        val default: Int,
        val min: Int,
        val max: Int,
        val step: Int = 1,
        @StringRes summaryRes: Int? = null,
        visibleWhen: VisibleWhen? = null
    ) : ModuleOption(key, titleRes, summaryRes, visibleWhen) {
        override val typeName get() = "int"
        override val defaultValue get() = default
    }

    // stores the id of the picked entry
    class Choice(
        key: String,
        @StringRes titleRes: Int,
        val default: String,
        val entries: List<Entry>,
        @StringRes summaryRes: Int? = null,
        visibleWhen: VisibleWhen? = null
    ) : ModuleOption(key, titleRes, summaryRes, visibleWhen) {
        class Entry(val id: String, @StringRes val labelRes: Int)

        override val typeName get() = "choice"
        override val defaultValue get() = default
    }

    // stores the package under key and the launcher activity under componentKey
    class AppPicker(
        key: String,
        val componentKey: String,
        @StringRes titleRes: Int,
        @StringRes summaryRes: Int? = null,
        visibleWhen: VisibleWhen? = null
    ) : ModuleOption(key, titleRes, summaryRes, visibleWhen) {
        override val typeName get() = "app"
        override val defaultValue: Any? get() = ""
    }

    // a button that stores nothing and runs code against the other rendered options
    class Action(
        key: String,
        @StringRes titleRes: Int,
        val onClick: (ActionContext) -> Unit,
        @StringRes summaryRes: Int? = null,
        visibleWhen: VisibleWhen? = null
    ) : ModuleOption(key, titleRes, summaryRes, visibleWhen) {
        override val typeName get() = "action"
        override val defaultValue: Any? get() = null
    }
}

// shows an option only while another option of the same module matches
class VisibleWhen(val key: String, val predicate: (Any?) -> Boolean) {
    companion object {
        fun isTrue(key: String) = VisibleWhen(key) { it == true }
        fun equals(key: String, value: Any) = VisibleWhen(key) { it == value }
    }
}

// what an action button may touch
interface ActionContext {
    val context: Context
    fun value(key: String): Any?
    // safe to call from any thread
    fun setValue(key: String, value: Any?)
}
