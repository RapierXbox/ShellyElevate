package me.rapierxbox.shellyelevatev2.display.app

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.display.DisplayContent
import me.rapierxbox.shellyelevatev2.display.DisplayHost
import me.rapierxbox.shellyelevatev2.switcher.AppCatalog

// black placeholder behind the external app. whenever the host comes back to the front
// the app was closed or the user left settings so the app is started again
@SuppressLint("ClickableViewAccessibility")
class AppContent(private val host: DisplayHost, parent: ViewGroup) : DisplayContent {

    private val activity = host.activity
    private val handler = Handler(Looper.getMainLooper())

    override val view: View = LayoutInflater.from(activity).inflate(R.layout.display_app_content, parent, false)

    private val icon: ImageView = view.findViewById(R.id.appContentIcon)
    private val message: TextView = view.findViewById(R.id.appContentMessage)
    private val buttons: View = view.findViewById(R.id.appContentButtons)
    private val retry: Button = view.findViewById(R.id.appContentRetry)

    private val launchRunnable = Runnable { launchIfStillInFront() }

    init {
        view.setOnTouchListener { _, event ->
            host.onContentTouch(event)
            true
        }
        retry.setOnClickListener {
            AppDisplayModule.clearLaunchHistory()
            launchIfStillInFront()
        }
        view.findViewById<Button>(R.id.appContentSettings).setOnClickListener { host.openSettings() }
    }

    override fun onResume() {
        showOpening()
        // a short delay lets a settings or screensaver activity started at the same time win
        handler.removeCallbacks(launchRunnable)
        handler.postDelayed(launchRunnable, LAUNCH_DELAY_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(launchRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(launchRunnable)
    }

    private fun launchIfStillInFront() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val pkg = AppDisplayModule.packageName(activity)
        when {
            pkg.isEmpty() -> showProblem(activity.getString(R.string.display_app_not_set), canRetry = false)
            AppDisplayModule.isLaunchBlocked() -> showProblem(activity.getString(R.string.display_app_keeps_closing, label(pkg)), canRetry = true)
            !AppDisplayModule.launch(activity) -> showProblem(activity.getString(R.string.display_app_not_installed, label(pkg)), canRetry = true)
        }
    }

    private fun showOpening() {
        val pkg = AppDisplayModule.packageName(activity)
        if (pkg.isEmpty()) {
            showProblem(activity.getString(R.string.display_app_not_set), canRetry = false)
            return
        }
        showIcon(pkg)
        message.text = activity.getString(R.string.display_app_opening, label(pkg))
        buttons.isVisible = false
    }

    private fun showProblem(text: String, canRetry: Boolean) {
        showIcon(AppDisplayModule.packageName(activity))
        message.text = text
        retry.isVisible = canRetry
        buttons.isVisible = true
    }

    private fun showIcon(pkg: String) {
        val app = AppCatalog.find(pkg)
        icon.setImageBitmap(app?.icon)
        icon.isVisible = app?.icon != null
    }

    private fun label(pkg: String) = AppCatalog.find(pkg)?.label ?: pkg

    private companion object {
        const val LAUNCH_DELAY_MS = 300L
    }
}
