package me.rapierxbox.shellyelevatev2.helper

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArraySet

// counts our resumed activities so the global touch reader knows when our own views
// already see the touches and when another app is in front
object ForegroundActivities : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var resumed = 0

    // run on the main thread whenever we go from no resumed activity to some or back
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    // run on the main thread with the class of every activity of ours that resumes
    private val resumeListeners = CopyOnWriteArraySet<(String) -> Unit>()

    // class of our activity that paused last. when the host resumes and this is not the host itself
    // one of our own screens covered it and not an external app that went away
    @Volatile
    @JvmStatic
    var lastPausedClass: String? = null
        private set

    // class of our activity in front or null while another app is in front
    @Volatile
    @JvmStatic
    var resumedClass: String? = null
        private set

    @JvmStatic
    fun anyResumed(): Boolean = resumed > 0

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun addResumeListener(listener: (String) -> Unit) {
        resumeListeners += listener
    }

    override fun onActivityResumed(activity: Activity) {
        resumed++
        resumedClass = activity.javaClass.name
        if (resumed == 1) listeners.forEach { it(true) }
        resumeListeners.forEach { it(activity.javaClass.name) }
    }

    override fun onActivityPaused(activity: Activity) {
        val wasResumed = resumed > 0
        resumed = (resumed - 1).coerceAtLeast(0)
        lastPausedClass = activity.javaClass.name
        if (resumedClass == activity.javaClass.name) resumedClass = null
        if (wasResumed && resumed == 0) listeners.forEach { it(false) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
