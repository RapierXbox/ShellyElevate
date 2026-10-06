package me.rapierxbox.shellyelevatev2

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_KEY
import me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_UPDATED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_SCORE
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_SCORE_KEY
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_STATE_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_STATE_KEY
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_TEXT
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_TEXT_KEY
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_THRESHOLD_KEY
import me.rapierxbox.shellyelevatev2.Constants.SP_SETTINGS_EVER_SHOWN
import me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_SCORE_BAR_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_UPDATE_PROMPTED
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mButtonHandler
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMediaHelper
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwInputHandler
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwipeHelper
import me.rapierxbox.shellyelevatev2.databinding.MainActivityBinding
import me.rapierxbox.shellyelevatev2.display.DisplayContent
import me.rapierxbox.shellyelevatev2.display.DisplayHost
import me.rapierxbox.shellyelevatev2.display.DisplayModuleRegistry
import me.rapierxbox.shellyelevatev2.helper.GestureInterceptLayout
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper
import me.rapierxbox.shellyelevatev2.helper.WebViewUpdater
import me.rapierxbox.shellyelevatev2.helper.touch.TouchCalibrator
import me.rapierxbox.shellyelevatev2.voice.VoiceEngine
import java.io.File

// hosts the active display module and the overlays every module shares
class MainActivity : ComponentActivity(), DisplayHost {

    private lateinit var binding: MainActivityBinding

    private val broadcastManager by lazy { LocalBroadcastManager.getInstance(this) }

    // what the active display module put on screen
    private var content: DisplayContent? = null
    private var contentModuleId: String? = null

    override val activity: ComponentActivity get() = this

    private var webviewUpdatePromptShown = false
    private var webviewUpdateOutcomeChecked = false
    private val shownDialogs = mutableListOf<AlertDialog>()

    private var clicksButtonRight = 0
    private var clicksButtonLeft = 0
    private var lastSettingsTapAtMs = 0L

    private var scoreBarRegistered = false
    private val colorGreen by lazy { ContextCompat.getColor(this, R.color.voice_score_green) }
    private val colorAmber by lazy { ContextCompat.getColor(this, R.color.voice_score_amber) }
    private val colorRed by lazy { ContextCompat.getColor(this, R.color.voice_score_red) }

    private val settingsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // the module picks up its own settings so only a module switch matters here
            if (DisplayModuleRegistry.activeId(mSharedPreferences) != contentModuleId) showActiveModule()
            applyScoreBarSetting()
        }
    }

    private val voiceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stateName = intent?.getStringExtra(INTENT_VOICE_STATE_KEY) ?: return
            val active = stateName == VoiceEngine.State.LISTENING.name
                    || stateName == VoiceEngine.State.PROCESSING.name
            binding.voiceIndicatorDot.visibility = if (active) View.VISIBLE else View.GONE
            if (active) resetScoreBar()
        }
    }

    private val voiceScoreReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val score = intent?.getFloatExtra(INTENT_VOICE_SCORE_KEY, 0f) ?: 0f
            val threshold = intent?.getFloatExtra(INTENT_VOICE_THRESHOLD_KEY, 0.5f) ?: 0.5f
            val barHeight = binding.voiceScoreBarContainer.height.takeIf { it > 0 } ?: return

            binding.voiceScoreBar.updateLayoutParams {
                height = (barHeight * score.coerceIn(0f, 1f)).toInt()
            }
            binding.voiceThresholdLine.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = (barHeight * threshold.coerceIn(0f, 1f)).toInt()
            }

            val barColor = when {
                score >= threshold -> colorRed
                score >= threshold * 0.5f -> colorAmber
                else -> colorGreen
            }
            binding.voiceScoreBar.setBackgroundColor(barColor)
            binding.voiceScoreValue.text = String.format("%.2f", score)
        }
    }

    private val voiceTextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(INTENT_VOICE_TEXT_KEY).orEmpty()
            if (text.isEmpty()) {
                binding.voiceBubble.visibility = View.GONE
            } else {
                binding.voiceBubbleText.text = text
                binding.voiceBubble.visibility = View.VISIBLE
            }
        }
    }

    // lifecycle

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ServiceHelper.ensureKioskService(applicationContext)
        setupWindow()

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // the module covers the whole window so the theme background is pure overdraw
        window.setBackgroundDrawable(null)

        showActiveModule()
        setupSettingsButtons()
        (binding.root as GestureInterceptLayout).apply {
            swipeHelper = mSwipeHelper
            gestureTarget = { content?.gestureTarget }
        }

        registerBroadcastReceivers()
        applyScoreBarSetting()

        // back never leaves the kiosk. finishing the host would uncover whatever app is below
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                content?.onBackPressed()
            }
        })

        // after the content so the dashboard webview starts building first
        requestWriteSettingsPermission()

        // first run opens settings so the user can enter the url
        // but never stack a second settings instance on top of a running one
        if (!mSharedPreferences.getBoolean(SP_SETTINGS_EVER_SHOWN, false)
            && !isActivityInStack(SettingsActivity::class.java.name)
        ) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()

        content?.onResume()

        showWebViewUpdateOutcome()
        maybePromptForWebViewUpdate()
    }

    override fun onStop() {
        content?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterBroadcastReceivers()
        dismissDialogs()
        releaseContent()
        super.onDestroy()
    }

    // display module

    private fun showActiveModule() {
        releaseContent()
        val module = DisplayModuleRegistry.active(mSharedPreferences)
        val created = module.createContent(this, binding.moduleContainer)
        binding.moduleContainer.addView(created.view, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content = created
        contentModuleId = module.id
        Log.i(TAG, "showing display module ${module.id}")
        // a module swapped while we are in front still needs its resume
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) created.onResume()
    }

    private fun releaseContent() {
        val old = content ?: return
        content = null
        contentModuleId = null
        try {
            old.onDestroy()
        } catch (e: Exception) {
            Log.w(TAG, "display content destroy failed: ${e.message}")
        }
        binding.moduleContainer.removeAllViews()
    }

    override fun openSettings() = startSettingsActivity()

    override fun onContentTouch(event: MotionEvent): Boolean {
        val screenManager = ShellyElevateApplication.mScreenManager
        val consumeForWake = screenManager?.shouldConsumeTouchForWake() == true
        if (BuildConfig.DEBUG) Log.d(TAG, "Touch event on display content, consumeForWake=$consumeForWake")
        mScreenSaverManager.onTouchEvent(event)
        screenManager?.onTouchEvent()
        return consumeForWake
    }

    // receivers

    private fun registerBroadcastReceivers() {
        broadcastManager.apply {
            registerReceiver(settingsChangedReceiver, IntentFilter(INTENT_SETTINGS_CHANGED))
            registerReceiver(voiceStateReceiver, IntentFilter(INTENT_VOICE_STATE_CHANGED))
            registerReceiver(voiceTextReceiver, IntentFilter(INTENT_VOICE_TEXT))
        }
    }

    private fun unregisterBroadcastReceivers() {
        broadcastManager.apply {
            unregisterReceiver(settingsChangedReceiver)
            unregisterReceiver(voiceStateReceiver)
            unregisterReceiver(voiceTextReceiver)
            if (scoreBarRegistered) unregisterReceiver(voiceScoreReceiver)
        }
        scoreBarRegistered = false
    }

    private fun broadcastProximity(value: Float) {
        val intent = Intent(INTENT_PROXIMITY_UPDATED).putExtra(INTENT_PROXIMITY_KEY, value)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    // voice overlay

    private fun applyScoreBarSetting() {
        val enabled = mSharedPreferences.getBoolean(SP_VOICE_SCORE_BAR_ENABLED, false)
        if (enabled == scoreBarRegistered) return
        if (enabled) {
            broadcastManager.registerReceiver(voiceScoreReceiver, IntentFilter(INTENT_VOICE_SCORE))
        } else {
            broadcastManager.unregisterReceiver(voiceScoreReceiver)
        }
        scoreBarRegistered = enabled
        binding.voiceScoreBarContainer.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun resetScoreBar() {
        binding.voiceScoreBar.updateLayoutParams { height = 0 }
        binding.voiceScoreValue.text = ".00"
        binding.voiceScoreBar.setBackgroundColor(colorGreen)
    }

    // settings entry

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSettingsButtons() {
        // secret knock of taps on the bottom right then the bottom left corner
        binding.settingButtonOverlayRight.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                expireStaleSettingsTaps()
                clicksButtonRight++
            }
            false
        }
        binding.settingButtonOverlayLeft.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                expireStaleSettingsTaps()
                // overlays pass touches through so dashboard taps count too and overshooting must not wedge the sequence
                if (clicksButtonRight >= SETTINGS_TAP_COUNT) clicksButtonLeft++ else resetClicks()
                if (clicksButtonLeft >= SETTINGS_TAP_COUNT) startSettingsActivity()
            }
            false
        }
    }

    // a long pause means nobody is entering the sequence so stale taps from normal use are dropped
    private fun expireStaleSettingsTaps() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSettingsTapAtMs > SETTINGS_TAP_TIMEOUT_MS) resetClicks()
        lastSettingsTapAtMs = now
    }

    private fun startSettingsActivity() {
        resetClicks()
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun resetClicks() {
        clicksButtonLeft = 0
        clicksButtonRight = 0
    }

    @Suppress("DEPRECATION")
    private fun isActivityInStack(activityClassName: String): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.getRunningTasks(10).any { it.topActivity?.className == activityClassName }
    }

    // webview ota

    private fun showDialog(builder: AlertDialog.Builder) {
        if (isFinishing || isDestroyed) return
        val dialog = builder.create()
        dialog.setOnDismissListener { shownDialogs.remove(dialog) }
        shownDialogs.add(dialog)
        dialog.show()
    }

    private fun dismissDialogs() {
        // copy since dismiss removes entries through the listener
        shownDialogs.toList().forEach { it.dismiss() }
        shownDialogs.clear()
    }

    // tells the user whether the ota from the last recovery boot took
    private fun showWebViewUpdateOutcome() {
        if (webviewUpdateOutcomeChecked) return
        webviewUpdateOutcomeChecked = true
        val message = WebViewUpdater.consumeUpdateOutcome(applicationContext) ?: return
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.webview_update_result_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
        )
    }

    // asks once on the first launch where the webview is too old
    // after any answer only the settings screen can trigger the update again
    private fun maybePromptForWebViewUpdate() {
        if (webviewUpdatePromptShown) return
        if (mSharedPreferences.getBoolean(SP_WEBVIEW_UPDATE_PROMPTED, false)) return
        if (!WebViewUpdater.isUpdateNeeded(applicationContext)) return

        webviewUpdatePromptShown = true
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.webview_update_prompt_title)
                .setMessage(R.string.webview_update_prompt_message)
                .setCancelable(false)
                .setPositiveButton(R.string.webview_update_prompt_yes) { _, _ ->
                    markWebViewUpdatePrompted()
                    startBackgroundWebViewDownload()
                }
                .setNegativeButton(R.string.webview_update_prompt_no) { _, _ ->
                    markWebViewUpdatePrompted()
                }
        )
    }

    private fun markWebViewUpdatePrompted() {
        mSharedPreferences.edit().putBoolean(SP_WEBVIEW_UPDATE_PROMPTED, true).apply()
    }

    private fun startBackgroundWebViewDownload() {
        WebViewUpdater.downloadAndStage(applicationContext, object : WebViewUpdater.Listener {
            override fun onProgress(percent: Int) {}

            override fun onCompleted(staged: File) {
                Log.i(TAG, "WebView OTA staged at ${staged.absolutePath}")
                showWebViewRebootDialog()
            }

            override fun onFailed(reason: String) {
                Log.w(TAG, "WebView OTA download failed: $reason")
                showWebViewUpdateFailed(reason)
            }
        })
    }

    private fun showWebViewRebootDialog() {
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.webview_update_reboot_title)
                .setMessage(R.string.webview_update_reboot_message)
                .setPositiveButton(R.string.webview_update_reboot_now) { _, _ ->
                    WebViewUpdater.rebootToInstall(applicationContext) { reason -> showWebViewUpdateFailed(reason) }
                }
                .setNegativeButton(R.string.webview_update_reboot_later, null)
        )
    }

    // a dialog and not a toast so the whole reason stays readable
    private fun showWebViewUpdateFailed(reason: String) {
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.webview_update_result_title)
                .setMessage(getString(R.string.webview_update_failed, reason))
                .setPositiveButton(android.R.string.ok, null)
        )
    }

    // the global touch reader learns the panel axes from touches the dashboard sees
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        TouchCalibrator.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    // keys

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handleKeyEvent(event) || super.dispatchKeyEvent(event)

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (BuildConfig.DEBUG) Log.d(TAG, "Key pressed: $keyCode - Event: $event")

        // auto repeat resends key down while held which would reset the
        // press timers and inflate click counts in the detectors
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            return when (keyCode) {
                KEY_POWER, in KEY_CAPACITIVE, KEY_SW_INPUT_0, KEY_SW_INPUT_1 -> true
                // the level coded sw input is held down so it repeats
                // the handler swallows it only when the event really is the sw terminal
                KeyEvent.KEYCODE_1 -> mSwInputHandler?.onKeyEvent(event) == true
                else -> false
            }
        }

        return when (keyCode) {
            // power and capacitive buttons are app scoped since the native monitor has to
            // drive them too or lite mode has no foreground activity to catch them #101
            KEY_POWER, in KEY_CAPACITIVE -> {
                mButtonHandler?.onKeyEvent(event)
                true
            }
            // physical sw inputs share one central path across every activity
            KEY_SW_INPUT_0, KEY_SW_INPUT_1 -> {
                mSwInputHandler?.onKeyEvent(event)
                true
            }
            // the same sw input on hardware that codes the contact level in the key state
            // the handler passes a real keyboard digit on untouched
            KeyEvent.KEYCODE_1 -> mSwInputHandler?.onKeyEvent(event) == true

            KEY_PROXIMITY_NEAR -> onKeyReleased(event) { broadcastProximity(0f) }
            KEY_PROXIMITY_MID -> onKeyReleased(event) { broadcastProximity(0.5f) }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> onKeyReleased(event) { mMediaHelper?.resumeOrPauseMusic() }
            KeyEvent.KEYCODE_MEDIA_PLAY -> onKeyReleased(event) { mMediaHelper?.resumeMusic() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> onKeyReleased(event) { mMediaHelper?.pauseMusic() }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> onKeyReleased(event) {}

            else -> false
        }
    }

    // runs the action on key up and reports the event as handled only then
    private inline fun onKeyReleased(event: KeyEvent, action: () -> Unit): Boolean {
        if (event.action != KeyEvent.ACTION_UP) return false
        action()
        return true
    }

    // window

    // the write settings permission lets us turn off android auto brightness
    // selinux denials on the sysfs backlight node are expected in permissive mode and do not block the write
    private fun requestWriteSettingsPermission() {
        if (Settings.System.canWrite(this)) return
        Log.w(TAG, "WRITE_SETTINGS permission not granted, requesting...")
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request WRITE_SETTINGS permission", e)
        }
    }

    // window flags stick for the life of the window so this runs once
    private fun setupWindow() {
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // the system clears these flags when bars are revealed so every resume reapplies them
    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    companion object {
        private const val TAG = "MainActivity"

        private const val SETTINGS_TAP_COUNT = 10
        private const val SETTINGS_TAP_TIMEOUT_MS = 2000L

        private const val KEY_POWER = 140
        private val KEY_CAPACITIVE = 131..134
        private const val KEY_PROXIMITY_NEAR = 135
        private const val KEY_PROXIMITY_MID = 136
        private const val KEY_SW_INPUT_0 = 141
        private const val KEY_SW_INPUT_1 = 142
    }
}
