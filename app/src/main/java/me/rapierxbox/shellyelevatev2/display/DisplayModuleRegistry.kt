package me.rapierxbox.shellyelevatev2.display

import android.content.Context
import android.content.SharedPreferences
import me.rapierxbox.shellyelevatev2.Constants.DISPLAY_MODULE_WEBVIEW
import me.rapierxbox.shellyelevatev2.Constants.SP_DISPLAY_MODULE
import me.rapierxbox.shellyelevatev2.display.options.ModuleOption
import me.rapierxbox.shellyelevatev2.display.webview.WebViewDisplayModule
import org.json.JSONArray
import org.json.JSONObject

// every display module the app knows. adding a module is one class plus one line here
object DisplayModuleRegistry {

    @JvmStatic
    val modules: List<DisplayModule> = listOf(
        WebViewDisplayModule,
    )

    const val DEFAULT_ID = DISPLAY_MODULE_WEBVIEW

    @JvmStatic
    fun byId(id: String?): DisplayModule? = modules.firstOrNull { it.id == id }

    @JvmStatic
    fun activeId(prefs: SharedPreferences): String =
        byId(prefs.getString(SP_DISPLAY_MODULE, DEFAULT_ID))?.id ?: DEFAULT_ID

    @JvmStatic
    fun active(prefs: SharedPreferences): DisplayModule = byId(activeId(prefs)) ?: modules.first()

    // float option keys so the http settings api keeps storing them as floats
    @JvmStatic
    fun floatKeys(): Set<String> = modules.flatMap { m ->
        m.options.filterIsInstance<ModuleOption.FloatNumber>().map { it.key }
    }.toSet()

    // schema for GET /display/modules and tools/apply-settings.py
    @JvmStatic
    fun schemaJson(context: Context, prefs: SharedPreferences): JSONObject {
        val list = JSONArray()
        for (module in modules) {
            val options = JSONArray()
            for (option in module.options) options.put(optionJson(context, option))
            list.put(JSONObject()
                .put("id", module.id)
                .put("title", context.getString(module.titleRes))
                .put("options", options))
        }
        return JSONObject()
            .put("key", SP_DISPLAY_MODULE)
            .put("default", DEFAULT_ID)
            .put("active", activeId(prefs))
            .put("modules", list)
    }

    private fun optionJson(context: Context, option: ModuleOption): JSONObject {
        val json = JSONObject()
            .put("key", option.key)
            .put("type", option.typeName)
            .put("title", context.getString(option.titleRes))
        option.defaultValue?.let { json.put("default", it) }
        option.summaryRes?.let { json.put("summary", context.getString(it)) }
        option.visibleWhen?.let { json.put("visibleWhen", it.key) }
        when (option) {
            is ModuleOption.IntNumber -> {
                if (option.min != Int.MIN_VALUE) json.put("min", option.min)
                if (option.max != Int.MAX_VALUE) json.put("max", option.max)
            }
            is ModuleOption.FloatNumber -> {
                if (option.min != -Float.MAX_VALUE) json.put("min", option.min.toDouble())
                if (option.max != Float.MAX_VALUE) json.put("max", option.max.toDouble())
            }
            is ModuleOption.Slider -> json.put("min", option.min).put("max", option.max).put("step", option.step)
            is ModuleOption.Choice -> {
                val choices = JSONArray()
                option.entries.forEach {
                    choices.put(JSONObject().put("id", it.id).put("label", context.getString(it.labelRes)))
                }
                json.put("choices", choices)
            }
            is ModuleOption.AppPicker -> json.put("componentKey", option.componentKey)
            else -> {}
        }
        return json
    }
}
