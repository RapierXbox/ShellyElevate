package me.rapierxbox.shellyelevatev2.switcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

// launchable apps with pre rendered icons so the switcher and pickers never hit the
// package manager on the ui thread
object AppCatalog {
    private const val TAG = "AppCatalog"
    private const val ICON_DP = 56

    class AppEntry(
        val packageName: String,
        val component: ComponentName,
        val label: String,
        val icon: Bitmap?
    )

    @Volatile
    private var entries: List<AppEntry> = emptyList()

    @Volatile
    private var byPackage: Map<String, AppEntry> = emptyMap()

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    // background priority so icon rendering at start never competes with the dashboard
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "AppCatalog")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    // installs and component toggles arrive in bursts so they are folded into one reload
    private const val PACKAGE_CHANGE_DELAY_MS = 2_000L
    private val refreshRunnable = Runnable { refresh() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mainHandler.removeCallbacks(refreshRunnable)
            mainHandler.postDelayed(refreshRunnable, PACKAGE_CHANGE_DELAY_MS)
        }
    }

    @JvmStatic
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ctx.registerReceiver(packageReceiver, filter)
        refresh()
    }

    @JvmStatic
    fun refresh() {
        val ctx = appContext ?: return
        executor.execute {
            try {
                load(ctx)
            } catch (e: Exception) {
                Log.e(TAG, "loading launchable apps failed", e)
            }
        }
    }

    @JvmStatic
    fun apps(): List<AppEntry> = entries

    @JvmStatic
    fun find(packageName: String?): AppEntry? = packageName?.let { byPackage[it] }

    // listeners run on the main thread after every reload
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun load(ctx: Context) {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val iconPx = (ICON_DP * ctx.resources.displayMetrics.density).toInt()
        val result = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            // one entry per package keeps recents and the grid unambiguous
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                val pkg = info.activityInfo.packageName
                AppEntry(
                    packageName = pkg,
                    component = ComponentName(pkg, info.activityInfo.name),
                    label = info.loadLabel(pm).toString(),
                    icon = renderIcon(info.loadIcon(pm), iconPx)
                )
            }
            .sortedBy { it.label.lowercase() }
        entries = result
        byPackage = result.associateBy { it.packageName }
        Log.i(TAG, "loaded ${result.size} launchable apps")
        mainHandler.post { listeners.forEach { it() } }
    }

    private fun renderIcon(drawable: Drawable?, sizePx: Int): Bitmap? {
        if (drawable == null || sizePx <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "icon render failed: ${e.message}")
            null
        }
    }
}
