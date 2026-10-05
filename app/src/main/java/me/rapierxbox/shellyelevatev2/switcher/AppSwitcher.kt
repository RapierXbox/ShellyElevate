package me.rapierxbox.shellyelevatev2.switcher

import android.content.Context
import android.content.Intent
import android.util.Log
import me.rapierxbox.shellyelevatev2.display.DisplayController
import me.rapierxbox.shellyelevatev2.display.app.AppDisplayModule

// entry points for the app switcher overlay
object AppSwitcher {
    private const val TAG = "AppSwitcher"

    @Volatile
    @JvmStatic
    var isOpen = false
        internal set

    @JvmStatic
    fun open(context: Context) {
        val intent = Intent(context, AppSwitcherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        context.startActivity(intent)
    }

    // the package the display module is showing. our own package stands for the webview host
    fun modulePackage(context: Context): String? {
        val module = DisplayController.activeModule(context)
        return if (module === AppDisplayModule) AppDisplayModule.packageName(context).ifEmpty { null } else context.packageName
    }

    // brings the display module back or opens any other app on purpose
    fun launch(context: Context, packageName: String): Boolean {
        if (packageName == modulePackage(context)) {
            // a deliberate tap never counts toward the crash loop guard
            AppDisplayModule.clearLaunchHistory()
            DisplayController.bringActiveToFront(context)
            return true
        }
        val pm = context.packageManager
        val entry = AppCatalog.find(packageName)
        val intent = if (entry != null) {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(entry.component)
        } else {
            pm.getLaunchIntentForPackage(packageName)
        } ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            RecentApps.touch(context, packageName)
            // the watchdog leaves this alone until the next screensaver or reboot
            DisplayController.markUserAway("$packageName opened from the switcher")
            true
        } catch (e: Exception) {
            Log.e(TAG, "launching $packageName failed", e)
            false
        }
    }
}
