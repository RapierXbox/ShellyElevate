package me.rapierxbox.shellyelevatev2.switcher

import android.content.Context
import androidx.core.content.edit

// most recently used apps for the switcher cards. kept in its own prefs file
// so it never shows up in the /settings api
object RecentApps {
    private const val PREFS = "AppSwitcher"
    private const val KEY = "recent"
    private const val MAX = 8

    @JvmStatic
    @Synchronized
    fun list(context: Context): List<String> =
        prefs(context).getString(KEY, "").orEmpty().split(',').filter { it.isNotEmpty() }

    // moves the package to the front
    @JvmStatic
    @Synchronized
    fun touch(context: Context, packageName: String) {
        if (packageName.isEmpty() || packageName == context.packageName) return
        val updated = (listOf(packageName) + list(context).filter { it != packageName }).take(MAX)
        prefs(context).edit { putString(KEY, updated.joinToString(",")) }
    }

    @JvmStatic
    @Synchronized
    fun remove(context: Context, packageName: String) {
        prefs(context).edit { putString(KEY, list(context).filter { it != packageName }.joinToString(",")) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
