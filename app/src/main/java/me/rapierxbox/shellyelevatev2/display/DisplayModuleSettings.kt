package me.rapierxbox.shellyelevatev2.display

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import me.rapierxbox.shellyelevatev2.Constants.SP_DISPLAY_MODULE
import me.rapierxbox.shellyelevatev2.PrefBinding
import me.rapierxbox.shellyelevatev2.SettingsSection
import me.rapierxbox.shellyelevatev2.SpinnerIdPref
import me.rapierxbox.shellyelevatev2.display.options.ModuleOptionRenderer
import me.rapierxbox.shellyelevatev2.display.options.RenderedOptions

// module picker plus one options page per registered module where only the picked one shows
class DisplayModuleSettings(
    fragment: Fragment,
    private val spinner: Spinner,
    container: LinearLayout
) : SettingsSection {

    private class Page(val module: DisplayModule, val view: View, val options: RenderedOptions, val custom: SettingsSection?)

    private val pages: List<Page>

    init {
        val context = fragment.requireContext()
        val renderer = ModuleOptionRenderer(context)
        pages = DisplayModuleRegistry.modules.map { module ->
            val options = renderer.render(module.options)
            val custom = module.createCustomSettings(fragment, options.view)
            container.addView(options.view)
            Page(module, options.view, options, custom)
        }

        val labels = pages.map { context.getString(it.module.titleRes) }
        spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showPage(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override val bindings: List<PrefBinding> =
        listOf<PrefBinding>(SpinnerIdPref(spinner, SP_DISPLAY_MODULE, pages.map { it.module.id }, DisplayModuleRegistry.DEFAULT_ID)) +
            pages.flatMap { it.options.bindings + (it.custom?.bindings ?: emptyList()) }

    override fun onLoaded() {
        pages.forEach {
            it.options.onLoaded()
            it.custom?.onLoaded()
        }
        showPage(spinner.selectedItemPosition)
    }

    // current value of a rendered option even before it is saved
    fun value(key: String): Any? = pages.firstOrNull { it.options.has(key) }?.options?.value(key)

    private fun showPage(position: Int) {
        pages.forEachIndexed { index, page -> page.view.isVisible = index == position }
    }
}
