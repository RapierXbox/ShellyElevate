package me.rapierxbox.shellyelevatev2

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rapierxbox.shellyelevatev2.Constants.EXTRA_SCREEN_SAVER_ID
import me.rapierxbox.shellyelevatev2.Constants.INTENT_AOD_STARTED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_AOD_STOPPED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_KEY
import me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_UPDATED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STARTED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STOPPED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_OFF
import me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_ON
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_SCORE
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_SCORE_KEY
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_STATE_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_STATE_KEY
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_TEXT
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_TEXT_KEY
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_THRESHOLD_KEY
import me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_INJECT_JAVASCRIPT
import me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_REFRESH
import me.rapierxbox.shellyelevatev2.Constants.SCREEN_SAVER_ID_AOD
import me.rapierxbox.shellyelevatev2.Constants.SLEEP_OPT_NONE
import me.rapierxbox.shellyelevatev2.Constants.SLEEP_OPT_STANDARD
import me.rapierxbox.shellyelevatev2.Constants.SP_IGNORE_SSL_ERRORS
import me.rapierxbox.shellyelevatev2.Constants.SP_SETTINGS_EVER_SHOWN
import me.rapierxbox.shellyelevatev2.Constants.SP_SLEEP_OPTIMIZATION_LEVEL
import me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_SCORE_BAR_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_UPDATE_PROMPTED
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mButtonHandler
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMediaHelper
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mShellyElevateJavascriptInterface
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwInputHandler
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwipeHelper
import me.rapierxbox.shellyelevatev2.databinding.MainActivityBinding
import me.rapierxbox.shellyelevatev2.helper.GestureInterceptLayout
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper
import me.rapierxbox.shellyelevatev2.helper.WebViewUpdater
import me.rapierxbox.shellyelevatev2.voice.VoiceAssistantManager
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var binding: MainActivityBinding

    // replaced wholesale after a render process crash so never cache it elsewhere
    private lateinit var webView: WebView

    private val broadcastManager by lazy { LocalBroadcastManager.getInstance(this) }

    private var initialLoadDone = false
    private var retryJob: Job? = null

    // last dashboard url we asked the webview to load so redundant reloads can be skipped
    private var lastRequestedUrl: String? = null

    // js injected before the first paint would run against a blank document
    private var firstPaintDone = false
    private val pendingJs = mutableListOf<String>()

    private var webViewPausedForSleep = false
    private var inAODMode = false

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

    // aod wakes the webview briefly once per second so widgets like the clock stay current
    private val aodTickHandler = Handler(Looper.getMainLooper())

    private val aodTickPauseRunnable = Runnable {
        if (!inAODMode) return@Runnable
        try {
            webView.onPause()
            webView.pauseTimers()
        } catch (e: Exception) {
            Log.w(TAG, "aod tick pause failed: ${e.message}")
        }
    }

    private val aodTickRunnable = object : Runnable {
        override fun run() {
            if (!inAODMode) return
            try {
                webView.resumeTimers()
                webView.onResume()
                aodTickHandler.postDelayed(aodTickPauseRunnable, AOD_TICK_WINDOW_MS)
            } catch (e: Exception) {
                Log.w(TAG, "aod tick resume failed: ${e.message}")
            }
            aodTickHandler.postDelayed(this, AOD_TICK_PERIOD_MS)
        }
    }

    private val settingsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                // only reload when the url changed or we sit on the offline page
                // so saving unrelated settings does not restart the dashboard
                val webviewUrl = ServiceHelper.getWebviewUrl()
                if (webviewUrl != lastRequestedUrl || isOfflineUrl(webView.url)) {
                    Log.d(TAG, "Reloading WebView due to settings change: $webviewUrl")
                    loadDashboard(webviewUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reloading WebView on settings change", e)
            }
            applyScoreBarSetting()
        }
    }

    // explicit refresh request so it always reloads unlike the settings receiver
    private val webviewRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                loadDashboard(ServiceHelper.getWebviewUrl())
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing WebView", e)
            }
        }
    }

    private val javascriptInjectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val javascriptCode = intent?.getStringExtra("javascript")?.trim() ?: return
            try {
                if (!firstPaintDone) {
                    // capped so a page stuck loading cannot grow the queue forever
                    if (pendingJs.size >= MAX_PENDING_JS) pendingJs.removeAt(0)
                    pendingJs.add(javascriptCode)
                    Log.d(TAG, "Queueing JS until first paint")
                    return
                }
                Log.d(TAG, "Injecting JS into WebView")
                webView.evaluateJavascript(javascriptCode, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting JS", e)
            }
        }
    }

    private val voiceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stateName = intent?.getStringExtra(INTENT_VOICE_STATE_KEY) ?: return
            val active = stateName == VoiceAssistantManager.State.LISTENING.name
                    || stateName == VoiceAssistantManager.State.PROCESSING.name
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

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            try {
                when (action) {
                    INTENT_TURN_SCREEN_ON -> mShellyElevateJavascriptInterface.onScreenOn()
                    INTENT_TURN_SCREEN_OFF -> mShellyElevateJavascriptInterface.onScreenOff()
                    INTENT_SCREEN_SAVER_STARTED -> {
                        mShellyElevateJavascriptInterface.onScreensaverOn()
                        val saverId = intent.getIntExtra(EXTRA_SCREEN_SAVER_ID, -1)
                        // aod keeps the webview on screen so it must not be paused for sleep
                        if (saverId != SCREEN_SAVER_ID_AOD && sleepLevel() >= SLEEP_OPT_STANDARD) {
                            pauseWebViewForSleep()
                        }
                    }
                    INTENT_SCREEN_SAVER_STOPPED -> {
                        resumeWebViewFromSleep()
                        mShellyElevateJavascriptInterface.onScreensaverOff()
                    }
                    INTENT_PROXIMITY_UPDATED -> mShellyElevateJavascriptInterface.onMotion()
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "screenStateReceiver invoked: $action")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling screen state: $action", e)
            }
        }
    }

    private val aodReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                INTENT_AOD_STARTED -> enterAODMode()
                INTENT_AOD_STOPPED -> exitAODMode()
            }
        }
    }

    // lifecycle

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ServiceHelper.ensureKioskService(applicationContext)
        requestWriteSettingsPermission()
        setScreenOptions()

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // the webview covers the whole window so the theme background is pure overdraw
        window.setBackgroundDrawable(null)
        webView = binding.myWebView

        configureWebView()
        setupSettingsButtons()
        (binding.root as GestureInterceptLayout).swipeHelper = mSwipeHelper

        registerBroadcastReceivers()
        applyScoreBarSetting()

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
        setScreenOptions()

        // covers the case where the screensaver stopped broadcast did not fire first
        resumeWebViewFromSleep()

        // refire the js hooks so a page suspended in the background can refresh its state
        mShellyElevateJavascriptInterface.onScreenOn()
        mShellyElevateJavascriptInterface.onScreensaverOff()

        if (!initialLoadDone) safeInitialLoad()

        showWebViewUpdateOutcome()
        maybePromptForWebViewUpdate()
    }

    override fun onStop() {
        cancelRetry()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterBroadcastReceivers()
        cancelRetry()
        aodTickHandler.removeCallbacksAndMessages(null)
        pendingJs.clear()
        dismissDialogs()
        // destroy the webview or every crash relaunch leaks a full renderer
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "webview destroy failed: ${e.message}")
        }
        super.onDestroy()
    }

    // receivers

    private fun registerBroadcastReceivers() {
        broadcastManager.apply {
            registerReceiver(settingsChangedReceiver, IntentFilter(INTENT_SETTINGS_CHANGED))
            registerReceiver(webviewRefreshReceiver, IntentFilter(INTENT_WEBVIEW_REFRESH))
            registerReceiver(javascriptInjectReceiver, IntentFilter(INTENT_WEBVIEW_INJECT_JAVASCRIPT))
            registerReceiver(voiceStateReceiver, IntentFilter(INTENT_VOICE_STATE_CHANGED))
            registerReceiver(voiceTextReceiver, IntentFilter(INTENT_VOICE_TEXT))
            registerReceiver(screenStateReceiver, IntentFilter().apply {
                addAction(INTENT_TURN_SCREEN_ON)
                addAction(INTENT_TURN_SCREEN_OFF)
                addAction(INTENT_SCREEN_SAVER_STARTED)
                addAction(INTENT_SCREEN_SAVER_STOPPED)
                addAction(INTENT_PROXIMITY_UPDATED)
            })
            registerReceiver(aodReceiver, IntentFilter().apply {
                addAction(INTENT_AOD_STARTED)
                addAction(INTENT_AOD_STOPPED)
            })
        }
    }

    private fun unregisterBroadcastReceivers() {
        broadcastManager.apply {
            unregisterReceiver(settingsChangedReceiver)
            unregisterReceiver(webviewRefreshReceiver)
            unregisterReceiver(javascriptInjectReceiver)
            unregisterReceiver(voiceStateReceiver)
            unregisterReceiver(voiceTextReceiver)
            unregisterReceiver(screenStateReceiver)
            unregisterReceiver(aodReceiver)
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

    // sleep and aod

    private fun sleepLevel(): Int =
        mSharedPreferences.getInt(SP_SLEEP_OPTIMIZATION_LEVEL, SLEEP_OPT_NONE)

    private fun pauseWebViewForSleep() {
        if (webViewPausedForSleep) return
        webViewPausedForSleep = true
        try {
            webView.onPause()
            webView.pauseTimers()
            webView.setLayerType(View.LAYER_TYPE_NONE, null)
            webView.visibility = View.INVISIBLE
            Log.i(TAG, "webview paused for sleep")
        } catch (e: Exception) {
            Log.w(TAG, "pauseWebViewForSleep failed: ${e.message}")
        }
    }

    private fun resumeWebViewFromSleep() {
        if (!webViewPausedForSleep) return
        webViewPausedForSleep = false
        try {
            webView.visibility = View.VISIBLE
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webView.resumeTimers()
            webView.onResume()
            Log.i(TAG, "webview resumed from sleep")
        } catch (e: Exception) {
            Log.w(TAG, "resumeWebViewFromSleep failed: ${e.message}")
        }
    }

    private fun enterAODMode() {
        if (inAODMode) return
        inAODMode = true
        try {
            // the webview stays visible and the ticker resumes its timers briefly each second
            webView.settings.offscreenPreRaster = false
            webView.onPause()
            webView.pauseTimers()
            webView.setLayerType(View.LAYER_TYPE_NONE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, false)
            }
            aodTickHandler.removeCallbacksAndMessages(null)
            aodTickHandler.postDelayed(aodTickRunnable, AOD_TICK_PERIOD_MS)
            Log.i(TAG, "aod mode entered")
        } catch (e: Exception) {
            Log.w(TAG, "enterAODMode failed: ${e.message}")
        }
    }

    private fun exitAODMode() {
        if (!inAODMode) return
        inAODMode = false
        try {
            aodTickHandler.removeCallbacksAndMessages(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webView.resumeTimers()
            webView.onResume()
            webView.settings.offscreenPreRaster = true
            Log.i(TAG, "aod mode exited")
        } catch (e: Exception) {
            Log.w(TAG, "exitAODMode failed: ${e.message}")
        }
    }

    // loading

    private fun loadDashboard(url: String) {
        // any explicit load makes a pending offline retry obsolete
        cancelRetry()
        lastRequestedUrl = url
        webView.loadUrl(url)
    }

    private fun showOfflinePage(view: WebView, failingUrl: String?) {
        // never reload the offline page itself or a broken asset would loop
        if (!isOfflineUrl(failingUrl)) view.post { view.loadUrl(OFFLINE_URL) }
    }

    private fun safeInitialLoad() {
        initialLoadDone = true
        lifecycleScope.launch(Dispatchers.Default) {
            val url = ServiceHelper.getWebviewUrl()
            val online = ServiceHelper.isNetworkReady(applicationContext)
            withContext(Dispatchers.Main) {
                if (online) {
                    loadDashboard(url)
                } else {
                    webView.loadUrl(OFFLINE_URL)
                    scheduleRetryOnlineAfterOffline(url)
                }
            }
        }
    }

    private fun scheduleRetryOnlineAfterOffline(targetUrl: String) {
        cancelRetry()
        retryJob = lifecycleScope.launch(Dispatchers.Default) {
            // fast backoff for the window right after boot then a slow poll to ride out long wifi outages
            val delays = sequence {
                yieldAll(RETRY_BACKOFF_MS)
                while (true) yield(RETRY_POLL_MS)
            }
            for (delayMs in delays) {
                delay(delayMs)
                if (ServiceHelper.isNetworkReady(applicationContext)) {
                    withContext(Dispatchers.Main) {
                        // drop the reference first so loadDashboard does not cancel this job mid flight
                        retryJob = null
                        loadDashboard(targetUrl)
                    }
                    return@launch
                }
            }
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    // webview setup

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView() {
        applyWebSettings(webView.settings)

        webView.apply {
            if (layerType != View.LAYER_TYPE_HARDWARE) {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            // let the renderer drop priority whenever the webview is covered
            // by settings or a screensaver activity instead of only during aod
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }

            webViewClient = DashboardWebViewClient()
            webChromeClient = DashboardChromeClient()
            addJavascriptInterface(mShellyElevateJavascriptInterface, "ShellyElevate")

            setOnTouchListener { _, event ->
                val screenManager = ShellyElevateApplication.mScreenManager
                val consumeForWake = screenManager?.shouldConsumeTouchForWake() == true
                if (BuildConfig.DEBUG) Log.d(TAG, "Touch event detected on WebView, mScreenManager=$screenManager, consumeForWake=$consumeForWake")
                // the swipe helper is fed only by GestureInterceptLayout
                mScreenSaverManager.onTouchEvent(event)
                screenManager?.onTouchEvent()
                // consuming keeps a wake touch from reaching the page
                consumeForWake
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun applyWebSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.allowFileAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.databaseEnabled = true
        settings.setRenderPriority(WebSettings.RenderPriority.NORMAL)
        settings.offscreenPreRaster = true

        // the dashboard does its own theming and chromium auto darken washes out colors on this panel
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }
    }

    // swaps in a fresh webview at the same spot after the renderer died
    private fun recoverWebView(crashed: WebView) {
        val parent = crashed.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(crashed)
        val layoutParams = crashed.layoutParams

        // detach before destroy so we never touch a destroyed view that is still attached
        parent.removeViewAt(index)
        crashed.destroy()

        // same id so GestureInterceptLayout can still find it
        val fresh = WebView(this).apply {
            id = R.id.myWebView
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        webView = fresh
        firstPaintDone = false
        configureWebView()
        parent.addView(fresh, index, layoutParams)

        // land on the offline page instead of the failing url so a crashing page cannot hot loop
        fresh.loadUrl(OFFLINE_URL)
        Log.i(TAG, "Recovered WebView after crash, showing offline page")
    }

    private inner class DashboardWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            firstPaintDone = false
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            firstPaintDone = true
            if (pendingJs.isNotEmpty()) {
                pendingJs.forEach { webView.evaluateJavascript(it, null) }
                pendingJs.clear()
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            try {
                if (mSharedPreferences.getBoolean(SP_IGNORE_SSL_ERRORS, false)) {
                    handler?.proceed()
                } else {
                    handler?.cancel()
                    if (view != null) showOfflinePage(view, error?.url)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SSL error handling failed", e)
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse?) {
            if (!request.isForMainFrame) return
            try {
                val failingUrl = request.url.toString()
                Log.w(TAG, "HTTP error for main frame: $failingUrl -> ${errorResponse?.statusCode}")
                showOfflinePage(view, failingUrl)
            } catch (e: Exception) {
                Log.e(TAG, "onReceivedHttpError failed", e)
            }
        }

        // the legacy overload is only reached through the default of this one so it needs no override
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            try {
                val failingUrl = request.url.toString()
                Log.w(TAG, "onReceivedError main frame: $failingUrl - ${error.description}")
                showOfflinePage(view, failingUrl)
            } catch (e: Exception) {
                Log.e(TAG, "onReceivedError failed", e)
            }
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString()
            if (isOfflineUrl(url)) {
                return WebResourceResponse("text/html", "UTF-8", assets.open(OFFLINE_PAGE))
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            if (!url.startsWith(APP_URL_SCHEME)) return false
            when (url.removePrefix(APP_URL_SCHEME)) {
                "reload" -> view?.post { loadDashboard(ServiceHelper.getWebviewUrl()) }
                "offline" -> view?.post { view.loadUrl(OFFLINE_URL) }
                "settings" -> startSettingsActivity()
            }
            return true
        }

        // the webview only calls this on api 26 and up
        @SuppressLint("NewApi")
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e(TAG, "WebView render process crashed; didCrash=${detail.didCrash()}")
            try {
                recoverWebView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover WebView", e)
            }
            // true keeps the app alive instead of letting the system kill it
            return true
        }
    }

    private inner class DashboardChromeClient : WebChromeClient() {

        // pages may use the microphone when the app holds the record audio permission
        // audio only since these panels have no camera
        override fun onPermissionRequest(request: PermissionRequest) {
            if (!request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                request.deny()
                return
            }
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "audio capture requested but RECORD_AUDIO is not granted")
                request.deny()
                return
            }
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            // webview 128 and newer probes android.webkit.PacProcessor which does not exist on api 24
            // the resulting class not found spam is harmless
            if (consoleMessage.message().contains("PacProcessor")) return true
            return super.onConsoleMessage(consoleMessage)
        }
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

    @Suppress("DEPRECATION")
    private fun setScreenOptions() {
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun isOfflineUrl(url: String?): Boolean = url?.contains(OFFLINE_PAGE) == true

    companion object {
        private const val TAG = "MainActivity"

        private const val OFFLINE_PAGE = "offline.html"
        private const val OFFLINE_URL = "file:///android_asset/$OFFLINE_PAGE"
        private const val APP_URL_SCHEME = "shellyelevate:"

        private const val MAX_PENDING_JS = 50
        private const val SETTINGS_TAP_COUNT = 10
        private const val SETTINGS_TAP_TIMEOUT_MS = 2000L

        private const val AOD_TICK_PERIOD_MS = 1000L
        private const val AOD_TICK_WINDOW_MS = 200L

        private val RETRY_BACKOFF_MS = listOf(2000L, 4000L, 8000L, 16000L)
        private const val RETRY_POLL_MS = 30_000L

        private const val KEY_POWER = 140
        private val KEY_CAPACITIVE = 131..134
        private const val KEY_PROXIMITY_NEAR = 135
        private const val KEY_PROXIMITY_MID = 136
        private const val KEY_SW_INPUT_0 = 141
        private const val KEY_SW_INPUT_1 = 142
    }
}
