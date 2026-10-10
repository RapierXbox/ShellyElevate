package me.rapierxbox.shellyelevatev2.display.webview

import android.content.Context
import android.view.ViewGroup
import me.rapierxbox.shellyelevatev2.Constants.DISPLAY_MODULE_WEBVIEW
import me.rapierxbox.shellyelevatev2.Constants.SP_EXTENDED_JAVASCRIPT_INTERFACE
import me.rapierxbox.shellyelevatev2.Constants.SP_IGNORE_SSL_ERRORS
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_BATCH_UPDATES
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_MODERN_FRONTEND
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_REDUCE_MOTION
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_URL
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.display.DisplayContent
import me.rapierxbox.shellyelevatev2.display.DisplayController
import me.rapierxbox.shellyelevatev2.display.DisplayHost
import me.rapierxbox.shellyelevatev2.display.DisplayModule
import me.rapierxbox.shellyelevatev2.display.options.ModuleOption
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper

// the dashboard webview that used to be all of MainActivity
object WebViewDisplayModule : DisplayModule {

    override val id = DISPLAY_MODULE_WEBVIEW

    // the url the dashboard webview last finished loading or navigated to in page
    @JvmStatic
    @Volatile
    var shownUrl: String? = null
        internal set
    override val titleRes = R.string.display_module_webview

    // keys predate the module system so they keep their old names
    override val options: List<ModuleOption> = listOf(
        ModuleOption.Action("webview.findUrl", R.string.findIPButtonText, { actions ->
            ServiceHelper.getHAURL(actions.context.applicationContext) { url ->
                actions.setValue(SP_WEBVIEW_URL, url)
            }
        }),
        ModuleOption.Url(SP_WEBVIEW_URL, R.string.display_webview_url, hintRes = R.string.homeAssistantIpEditTextPreviewIp),
        ModuleOption.Toggle(SP_IGNORE_SSL_ERRORS, R.string.ignore_ssl_errors, false),
        ModuleOption.Toggle(SP_EXTENDED_JAVASCRIPT_INTERFACE, R.string.extended_js_interface, false),
        ModuleOption.Toggle(SP_WEBVIEW_MODERN_FRONTEND, R.string.webview_modern_frontend, false,
            summaryRes = R.string.webview_modern_frontend_summary),
        ModuleOption.Toggle(SP_WEBVIEW_REDUCE_MOTION, R.string.webview_reduce_motion, false,
            summaryRes = R.string.webview_reduce_motion_summary),
        ModuleOption.Toggle(SP_WEBVIEW_BATCH_UPDATES, R.string.webview_batch_updates, true,
            summaryRes = R.string.webview_batch_updates_summary),
    )

    override fun createContent(host: DisplayHost, parent: ViewGroup): DisplayContent = WebViewContent(host)

    override fun isInFront(context: Context): Boolean? = DisplayController.isHostInFront()

    override fun bringToFront(context: Context) = DisplayController.launchHost(context)
}
