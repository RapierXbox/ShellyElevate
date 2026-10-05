package me.rapierxbox.shellyelevatev2.display.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.display.DisplayContent
import me.rapierxbox.shellyelevatev2.display.DisplayController
import me.rapierxbox.shellyelevatev2.display.DisplayHost
import me.rapierxbox.shellyelevatev2.display.DisplayModule
import me.rapierxbox.shellyelevatev2.display.KeepInFront
import me.rapierxbox.shellyelevatev2.display.options.ModuleOption
import me.rapierxbox.shellyelevatev2.helper.ForegroundDetector
import me.rapierxbox.shellyelevatev2.switcher.RecentApps

// launches a chosen external app and keeps it in front like the dashboard
object AppDisplayModule : DisplayModule, KeepInFront {
    private const val TAG = "AppDisplayModule"

    const val ID = "app"
    const val KEY_PACKAGE = "app.package"
    const val KEY_COMPONENT = "app.component"
    const val KEY_KEEP_IN_FRONT = "app.keepInFront"

    // more launches than this inside the window means the app keeps dying
    private const val CRASH_LOOP_LAUNCHES = 5
    private const val CRASH_LOOP_WINDOW_MS = 60_000L

    override val id = ID
    override val titleRes = R.string.display_module_app
    override val watchdogIntervalMs = 5_000L

    override val options: List<ModuleOption> = listOf(
        ModuleOption.AppPicker(KEY_PACKAGE, KEY_COMPONENT, R.string.display_app_package),
        ModuleOption.Toggle(KEY_KEEP_IN_FRONT, R.string.display_app_keep_in_front, true,
            summaryRes = R.string.display_app_keep_in_front_summary),
    )

    private val launchTimes = ArrayDeque<Long>()

    override fun createContent(host: DisplayHost, parent: ViewGroup): DisplayContent = AppContent(host, parent)

    override fun isInFront(context: Context): Boolean {
        val pkg = packageName(context)
        if (pkg.isEmpty()) return false
        return ForegroundDetector.current(context)?.packageName == pkg
    }

    override fun bringToFront(context: Context) {
        // the host shows why the app is not coming up
        if (packageName(context).isEmpty() || isLaunchBlocked() || !launch(context)) {
            DisplayController.launchHost(context)
        }
    }

    override fun keepInFront(context: Context) = prefs(context).getBoolean(KEY_KEEP_IN_FRONT, true)

    fun packageName(context: Context): String = prefs(context).getString(KEY_PACKAGE, "") ?: ""

    // false when the app is missing or refused to start
    fun launch(context: Context): Boolean {
        val intent = launchIntent(context) ?: return false
        return try {
            context.startActivity(intent)
            recordLaunch()
            RecentApps.touch(context, intent.component?.packageName ?: packageName(context))
            DisplayController.onModuleShown()
            true
        } catch (e: Exception) {
            Log.e(TAG, "launching ${intent.component} failed", e)
            false
        }
    }

    // a launcher style intent so an app that is already running is only brought forward
    fun launchIntent(context: Context): Intent? {
        val pkg = packageName(context)
        if (pkg.isEmpty()) return null
        val pm = context.packageManager
        val stored = ComponentName.unflattenFromString(prefs(context).getString(KEY_COMPONENT, "") ?: "")
        val intent = if (stored != null && stored.packageName == pkg) {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(stored)
                .takeIf { it.resolveActivity(pm) != null }
        } else null
        val resolved = intent ?: pm.getLaunchIntentForPackage(pkg) ?: return null
        return resolved.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    @Synchronized
    fun isLaunchBlocked(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (launchTimes.isNotEmpty() && now - launchTimes.first() > CRASH_LOOP_WINDOW_MS) launchTimes.removeFirst()
        return launchTimes.size >= CRASH_LOOP_LAUNCHES
    }

    @Synchronized
    fun clearLaunchHistory() = launchTimes.clear()

    @Synchronized
    private fun recordLaunch() {
        launchTimes.addLast(SystemClock.elapsedRealtime())
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
}
