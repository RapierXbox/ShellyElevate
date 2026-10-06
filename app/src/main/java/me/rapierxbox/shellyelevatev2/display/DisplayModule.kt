package me.rapierxbox.shellyelevatev2.display

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import me.rapierxbox.shellyelevatev2.SettingsSection
import me.rapierxbox.shellyelevatev2.display.options.ModuleOption

// one thing the screen can show. modules are stateless singletons listed in DisplayModuleRegistry
// and the per activity state lives in the DisplayContent they create
interface DisplayModule {
    // persisted in SP_DISPLAY_MODULE so never change it
    val id: String

    @get:StringRes
    val titleRes: Int

    // rendered into the display settings page and published by /display/modules
    val options: List<ModuleOption> get() = emptyList()

    fun createContent(host: DisplayHost, parent: ViewGroup): DisplayContent

    // used by the kiosk watchdog to decide whether the module needs to come back
    // null when nothing can tell. may shell out so keep it off the main thread
    fun isInFront(context: Context): Boolean?

    // must work from a non activity context
    fun bringToFront(context: Context)

    // how often the kiosk watchdog checks that the module is still in front
    val watchdogIntervalMs: Long get() = 30_000L

    // for settings ui that options cannot express
    fun createCustomSettings(fragment: Fragment, parent: ViewGroup): SettingsSection? = null
}

// what a module puts inside MainActivity
interface DisplayContent {
    val view: View

    // receives a cancel when GestureInterceptLayout steals a multi finger swipe
    val gestureTarget: View? get() = null

    fun onResume() {}
    fun onStop() {}
    fun onDestroy() {}
}

// the narrow slice of MainActivity a module may use
interface DisplayHost {
    val activity: ComponentActivity
    fun openSettings()
    // feeds the screensaver and screen manager and returns true when the touch only woke the screen
    fun onContentTouch(event: MotionEvent): Boolean
}
