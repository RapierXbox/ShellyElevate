package me.rapierxbox.shellyelevatev2.display

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME
import me.rapierxbox.shellyelevatev2.Constants.SP_LITE_MODE
import me.rapierxbox.shellyelevatev2.MainActivity
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.helper.ForegroundDetector
import me.rapierxbox.shellyelevatev2.switcher.AppSwitcherActivity
import java.util.concurrent.Executors

// decides when the active display module has to come back to the front
// once the user leaves on purpose it only returns after a screensaver or a reboot
object DisplayController {
    private const val TAG = "DisplayController"

    // lets a finishing screensaver activity get out of the way before we look at the front
    private const val RETURN_DELAY_MS = 800L

    // the dumpsys fallback spawns a shell so it is polled this many times less often
    private const val SLOW_DETECTOR_FACTOR = 3

    // launchers rarely change so the package manager is asked at most this often
    private const val HOME_CACHE_MS = 60_000L

    @Volatile
    private var homePackages: Set<String> = emptySet()

    @Volatile
    private var homeLoadedAt = 0L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "DisplayController") }
    private val mainHandler = Handler(Looper.getMainLooper())

    // set when the user opened something else and cleared on screensaver or when the module is shown again
    @Volatile
    @JvmStatic
    var userAway = false
        private set

    @Volatile
    private var lastModuleId: String? = null

    @JvmStatic
    fun init(context: Context) {
        lastModuleId = activeModule(context).id
    }

    // a module picked in settings or over http comes to the front right away
    @JvmStatic
    fun onSettingsChanged(context: Context) {
        val app = context.applicationContext
        val id = activeModule(app).id
        val previous = lastModuleId
        lastModuleId = id
        if (previous == null || previous == id || userAway || isLiteMode(app)) return
        // the end of the screensaver brings the new module up
        if (mScreenSaverManager?.isScreenSaverRunning == true) return
        Log.i(TAG, "display module changed from $previous to $id")
        worker.execute { bringActiveToFront(app) }
    }

    @JvmStatic
    fun markUserAway(reason: String) {
        if (!userAway) Log.i(TAG, "user left the display module: $reason")
        userAway = true
    }

    @JvmStatic
    fun onModuleShown() {
        userAway = false
    }

    @JvmStatic
    fun isLiteMode(context: Context): Boolean = prefs(context).getBoolean(SP_LITE_MODE, false)

    @JvmStatic
    fun activeModule(context: Context): DisplayModule = DisplayModuleRegistry.active(prefs(context))

    @JvmStatic
    fun bringActiveToFront(context: Context) {
        userAway = false
        activeModule(context).bringToFront(context.applicationContext)
    }

    // starts MainActivity which then shows whatever module is active
    @JvmStatic
    fun launchHost(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(intent)
    }

    @JvmStatic
    fun onScreenSaverStopped(context: Context) {
        val app = context.applicationContext
        mainHandler.postDelayed({
            worker.execute {
                if (isLiteMode(app)) return@execute
                userAway = false
                val module = activeModule(app)
                if (!module.keepsInFront(app)) return@execute
                if (module.isInFront(app) || isOwnUiInFront(app)) return@execute
                Log.i(TAG, "screensaver ended, bringing ${module.id} back")
                module.bringToFront(app)
            }
        }, RETURN_DELAY_MS)
    }

    // how long the kiosk watchdog waits before the next check
    @JvmStatic
    fun watchdogIntervalMs(context: Context): Long {
        val base = activeModule(context).watchdogIntervalMs
        return if (ForegroundDetector.isCheap()) base else base * SLOW_DETECTOR_FACTOR
    }

    // run by the kiosk watchdog off the main thread
    @JvmStatic
    fun watchdogCheck(context: Context) {
        val app = context.applicationContext
        if (isLiteMode(app)) return
        if (userAway) return
        // nothing is visible and the end of the screensaver brings the module back anyway
        if (mScreenSaverManager?.isScreenSaverRunning == true) return
        val module = activeModule(app)
        val top = ForegroundDetector.current(app)
        if (top == null) {
            // nothing tells us what is in front so only recover a missing host like before
            if (!isOwnTaskTopOurs(app)) {
                Log.w(TAG, "host not running, relaunching")
                launchHost(app)
            }
            return
        }
        if (module.isInFront(app)) return
        // our own screens are fine and a visible host restarts its module itself when it resumes
        if (top.packageName == app.packageName) return
        if (!isHome(app, top.packageName)) {
            // another app is in front so somebody opened it on purpose
            markUserAway("${top.packageName} in front")
            return
        }
        if (!module.keepsInFront(app)) return
        Log.w(TAG, "launcher in front, bringing ${module.id} back")
        module.bringToFront(app)
    }

    // the host activity is the visible activity
    @JvmStatic
    fun isHostInFront(context: Context): Boolean {
        val top = ForegroundDetector.current(context)
            ?: return ownTaskTopClass(context) == MainActivity::class.java.name
        return top.packageName == context.packageName &&
            (top.className ?: ownTaskTopClass(context)) == MainActivity::class.java.name
    }

    // settings the switcher or a screensaver of ours is the visible activity
    @JvmStatic
    fun isOwnUiInFront(context: Context): Boolean {
        val top = ForegroundDetector.current(context)
        if (top == null) {
            val cls = ownTaskTopClass(context)
            return cls != null && cls != MainActivity::class.java.name
        }
        if (top.packageName != context.packageName) return false
        val cls = top.className ?: ownTaskTopClass(context)
        return cls != null && cls != MainActivity::class.java.name
    }

    private fun isOwnTaskTopOurs(context: Context): Boolean = ownTaskTopClass(context) != null

    // top activity of our own task or null when we have none
    // the switcher keeps a hidden task of its own so it never counts
    private fun ownTaskTopClass(context: Context): String? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        for (task in am.appTasks) {
            try {
                val top = task.taskInfo.topActivity ?: continue
                if (top.className == AppSwitcherActivity::class.java.name) continue
                return top.className
            } catch (e: Exception) {
                // the task can vanish between listing and querying it
                Log.d(TAG, "skipping app task", e)
            }
        }
        return null
    }

    private fun isHome(context: Context, packageName: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (homeLoadedAt == 0L || now - homeLoadedAt > HOME_CACHE_MS) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            homePackages = context.packageManager.queryIntentActivities(intent, 0)
                .mapTo(HashSet()) { it.activityInfo.packageName }
            homeLoadedAt = now
        }
        return packageName in homePackages || packageName == "com.android.systemui"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
}

// modules that wrap an external app can opt out of being forced back
fun DisplayModule.keepsInFront(context: Context): Boolean =
    (this as? KeepInFront)?.keepInFront(context) ?: true

interface KeepInFront {
    fun keepInFront(context: Context): Boolean
}
