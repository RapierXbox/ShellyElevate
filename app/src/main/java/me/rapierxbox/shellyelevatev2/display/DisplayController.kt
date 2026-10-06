package me.rapierxbox.shellyelevatev2.display

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME
import me.rapierxbox.shellyelevatev2.Constants.SP_LITE_MODE
import me.rapierxbox.shellyelevatev2.MainActivity
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.display.app.AppDisplayModule
import me.rapierxbox.shellyelevatev2.display.webview.WebViewDisplayModule
import me.rapierxbox.shellyelevatev2.helper.ForegroundActivities
import me.rapierxbox.shellyelevatev2.helper.ForegroundDetector
import me.rapierxbox.shellyelevatev2.helper.PrivilegedShell
import me.rapierxbox.shellyelevatev2.screensavers.activities.DigitalClockAndDateScreenSaverActivity
import me.rapierxbox.shellyelevatev2.switcher.AppSwitcherActivity
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// decides when the active display module has to come back to the front
// once the user leaves on purpose it only returns after a screensaver or a reboot
object DisplayController {
    private const val TAG = "DisplayController"

    // lets a finishing screensaver activity get out of the way before we look at the front
    private const val RETURN_DELAY_MS = 800L

    // how often the front is looked at again while the screen wakes and settles
    private const val RETURN_POLL_MS = 250L

    private val HOST_CLASS = MainActivity::class.java.name
    private val SWITCHER_CLASS = AppSwitcherActivity::class.java.name
    private val SAVER_CLASSES = setOf(DigitalClockAndDateScreenSaverActivity::class.java.name)

    // the dumpsys fallback spawns a shell so it is polled this many times less often
    private const val SLOW_DETECTOR_FACTOR = 3

    // launchers rarely change so the package manager is asked at most this often
    private const val HOME_CACHE_MS = 60_000L

    @Volatile
    private var homePackages: Set<String> = emptySet()

    @Volatile
    private var homeLoadedAt = 0L

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "DisplayController") }

    // our activity in front when the screensaver started so settings the user was in stays after wake
    @Volatile
    private var saverStartClass: String? = null

    // bumped on every screensaver start and stop so a pending return check knows it is stale
    private val saverGeneration = AtomicInteger()

    // set when the user opened something else and cleared on screensaver or when the module is shown again
    @Volatile
    @JvmStatic
    var userAway = false
        private set

    @Volatile
    private var lastModuleId: String? = null

    @JvmStatic
    fun init(context: Context) {
        val app = context.applicationContext
        lastModuleId = activeModule(app).id
        // the dashboard is back however the user got there so the watchdog and the switcher guess
        // stop treating the app they left as still in front. an external module clears it on launch
        ForegroundActivities.addResumeListener { cls ->
            if (userAway && cls == HOST_CLASS && activeModule(app) is WebViewDisplayModule) onModuleShown()
        }
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
        worker.execute {
            allowBackgroundStarts(app)
            bringActiveToFront(app)
        }
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
    // new task works from any context and reorder lifts the existing host above settings in our task
    @JvmStatic
    fun launchHost(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "starting the host failed", e)
        }
    }

    // called right before the saver shows so we know what the user was looking at
    @JvmStatic
    fun onScreenSaverStarted() {
        saverGeneration.incrementAndGet()
        saverStartClass = ForegroundActivities.resumedClass
    }

    @JvmStatic
    fun onScreenSaverStopped(context: Context) {
        val app = context.applicationContext
        userAway = false
        // a screensaver is a fresh start so an app the crash loop guard blocked gets another chance
        AppDisplayModule.clearLaunchHistory()
        val generation = saverGeneration.incrementAndGet()
        val startClass = saverStartClass
        val stoppedAt = SystemClock.elapsedRealtime()
        scheduleReturnCheck(RETURN_DELAY_MS) { checkReturn(app, generation, startClass, stoppedAt, -1L) }
    }

    // the scheduled future would swallow a failure silently
    private fun scheduleReturnCheck(delayMs: Long, check: () -> Unit) {
        worker.schedule({
            try {
                check()
            } catch (e: Exception) {
                Log.e(TAG, "screensaver return check failed", e)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    // runs on the worker and polls until the module is back or nothing is left to do
    // externalSince is when nothing of ours was first seen resumed on a usable screen or -1
    private fun checkReturn(app: Context, generation: Int, startClass: String?, stoppedAt: Long, externalSince: Long) {
        // a newer saver or a deliberate app launch since then takes over
        if (generation != saverGeneration.get() || userAway || isLiteMode(app)) return
        if (mScreenSaverManager?.isScreenSaverRunning == true) return
        val module = activeModule(app)
        val now = SystemClock.elapsedRealtime()
        val resumed = ForegroundActivities.resumedClass
        val usable = isScreenUsable(app)
        val since = if (resumed == null && usable) (if (externalSince < 0) now else externalSince) else -1L
        val action = ScreenSaverReturn.decide(
            resumedClass = resumed,
            startClass = startClass,
            hostClass = HOST_CLASS,
            saverClasses = SAVER_CLASSES,
            switcherClass = SWITCHER_CLASS,
            screenUsable = usable,
            externalForMs = if (since < 0) 0L else now - since,
            waitedMs = now - stoppedAt,
            keepsInFront = module.keepsInFront(app),
            moduleInFront = { module.isInFront(app) },
        )
        when (action) {
            ScreenSaverReturn.Action.DONE -> Unit
            ScreenSaverReturn.Action.WAIT ->
                scheduleReturnCheck(RETURN_POLL_MS) { checkReturn(app, generation, startClass, stoppedAt, since) }
            ScreenSaverReturn.Action.BRING_BACK -> {
                Log.i(TAG, "screensaver ended, bringing ${module.id} back")
                bringBackFromBackground(app, module)
            }
        }
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
        // our own screens are fine and a visible host restarts its module itself when it resumes
        // an asleep or locked screen shows nothing so there is nothing to fix either
        if (currentFront(app) != Front.EXTERNAL) return
        val module = activeModule(app)
        if (module.isInFront(app) == true) return
        val top = ForegroundDetector.current(app)
        if (top == null) {
            // nothing tells which app is in front so only recover a missing host like before
            if (!hasOwnTask(app)) {
                Log.w(TAG, "host not running, relaunching")
                allowBackgroundStarts(app)
                launchHost(app)
            }
            return
        }
        // the detector lags behind a switch between our own activities
        if (top.packageName == app.packageName) return
        if (!isHome(app, top.packageName)) {
            // another app is in front so somebody opened it on purpose
            markUserAway("${top.packageName} in front")
            return
        }
        if (!module.keepsInFront(app)) return
        Log.w(TAG, "launcher in front, bringing ${module.id} back")
        bringBackFromBackground(app, module)
    }

    // what is in front by our own lifecycle callbacks which work without any privileged grant
    @JvmStatic
    fun currentFront(context: Context): Front =
        Front.of(ForegroundActivities.resumedClass, HOST_CLASS, isScreenUsable(context))

    // the host activity is the resumed activity. false while another app or the sleeping screen hides it
    @JvmStatic
    fun isHostInFront(): Boolean = ForegroundActivities.resumedClass == HOST_CLASS

    // an asleep panel pauses every activity and a locked one keeps them paused
    private fun isScreenUsable(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return (pm?.isInteractive ?: true) && km?.isKeyguardLocked != true
    }

    // for callers off the main thread while some other app or nothing is in front
    private fun bringBackFromBackground(app: Context, module: DisplayModule) {
        allowBackgroundStarts(app)
        module.bringToFront(app)
    }

    // android 10 and later only let an app in the background start activities with the overlay grant
    // it normally ran at start already but it is cheap to try again. may shell out so keep it off the main thread
    private fun allowBackgroundStarts(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)) return
        PrivilegedShell.allowAppOp(context.packageName, "SYSTEM_ALERT_WINDOW")
        if (!Settings.canDrawOverlays(context)) Log.w(TAG, "overlay permission missing so android may block the return to the front")
    }

    // our own task still exists. only says the host was not finished and never whether it is visible
    // the switcher keeps a hidden task of its own so it never counts
    private fun hasOwnTask(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        for (task in am.appTasks) {
            try {
                val top = task.taskInfo.topActivity ?: continue
                if (top.className != SWITCHER_CLASS) return true
            } catch (e: Exception) {
                // the task can vanish between listing and querying it
                Log.d(TAG, "skipping app task", e)
            }
        }
        return false
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
