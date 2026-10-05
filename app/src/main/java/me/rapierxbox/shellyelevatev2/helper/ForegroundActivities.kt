package me.rapierxbox.shellyelevatev2.helper

import android.app.Activity
import android.app.Application
import android.os.Bundle

// counts our resumed activities so the global touch reader knows when our own views
// already see the touches and when another app is in front
object ForegroundActivities : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var resumed = 0

    // class of our activity that paused last. when the host resumes and this is not the host itself
    // one of our own screens covered it and not an external app that went away
    @Volatile
    @JvmStatic
    var lastPausedClass: String? = null
        private set

    @JvmStatic
    fun anyResumed(): Boolean = resumed > 0

    override fun onActivityResumed(activity: Activity) {
        resumed++
    }

    override fun onActivityPaused(activity: Activity) {
        resumed = (resumed - 1).coerceAtLeast(0)
        lastPausedClass = activity.javaClass.name
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
