package me.rapierxbox.shellyelevatev2.display.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rapierxbox.shellyelevatev2.BuildConfig
import me.rapierxbox.shellyelevatev2.Constants.EXTRA_SCREEN_SAVER_ID
import me.rapierxbox.shellyelevatev2.Constants.INTENT_HA_LOGIN_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_AOD_STARTED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_AOD_STOPPED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_UPDATED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STARTED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STOPPED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_OFF
import me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_ON
import me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_INJECT_JAVASCRIPT
import me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_REFRESH
import me.rapierxbox.shellyelevatev2.Constants.SCREEN_SAVER_ID_AOD
import me.rapierxbox.shellyelevatev2.Constants.SLEEP_OPT_NONE
import me.rapierxbox.shellyelevatev2.Constants.SLEEP_OPT_STANDARD
import me.rapierxbox.shellyelevatev2.Constants.SP_IGNORE_SSL_ERRORS
import me.rapierxbox.shellyelevatev2.Constants.SP_SLEEP_OPTIMIZATION_LEVEL
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_MODERN_FRONTEND
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_BATCH_UPDATES
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_REDUCE_MOTION
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mShellyElevateJavascriptInterface
import me.rapierxbox.shellyelevatev2.api.ApiHub
import me.rapierxbox.shellyelevatev2.api.HaLoginRules
import me.rapierxbox.shellyelevatev2.api.HaLoginStore
import me.rapierxbox.shellyelevatev2.display.DisplayContent
import me.rapierxbox.shellyelevatev2.display.DisplayHost
import me.rapierxbox.shellyelevatev2.helper.RendererPriority
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper

// the dashboard webview with offline fallback crash recovery and sleep handling
class WebViewContent(private val host: DisplayHost) : DisplayContent {

    private val activity = host.activity
    private val broadcastManager = LocalBroadcastManager.getInstance(activity)

    // pre raster keeps the whole page rastered which only pays off with memory to spare
    // declared before the webview since createWebView reads it
    private val preRaster = !isLowMemoryDevice(activity)

    // the modern frontend relies on the polyfills of the document start script
    private val documentStartScriptSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    // null while the webview runs with its own user agent
    private var appliedUserAgent: String? = null

    // set while the reduced motion script is registered on the current webview
    private var reduceMotionScript: ScriptHandler? = null

    // set while the update batching script is registered on the current webview
    private var batchUpdatesScript: ScriptHandler? = null

    // a plain handler since pauseTimers stops every js timer of the page
    private val sleepHandler = Handler(Looper.getMainLooper())

    private val suspendHaRunnable = Runnable {
        if (!webViewPausedForSleep || destroyed) return@Runnable
        try {
            // pauseTimers also holds back evaluateJavascript so the timers run for a moment
            // the page stays paused and hidden so ha keeps its guard and reconnects only on wake
            webView.resumeTimers()
            webView.evaluateJavascript(HaFrontend.SUSPEND_WHEN_HIDDEN_SCRIPT, null)
            sleepHandler.postDelayed(repauseRunnable, HA_SUSPEND_RUN_MS)
            Log.i(TAG, "suspending the dashboard connection while asleep")
        } catch (e: Exception) {
            Log.w(TAG, "suspending the dashboard connection failed: ${e.message}")
        }
    }

    private val repauseRunnable = Runnable {
        if (!webViewPausedForSleep || destroyed) return@Runnable
        try {
            webView.pauseTimers()
        } catch (e: Exception) {
            Log.w(TAG, "pausing the webview again failed: ${e.message}")
        }
    }

    // replaced wholesale after a render process crash so never cache it elsewhere
    // added straight to the module container so the busiest view has no extra layout level
    private var webView: WebView = createWebView()

    override val view: View get() = webView
    override val gestureTarget: View get() = webView

    private var initialLoadDone = false
    private var initialLoadJob: Job? = null
    private var retryJob: Job? = null

    // the lifecycle scope outlives this content on a module switch so late coroutines check it
    private var destroyed = false

    // a renderer killed again this soon after a recovery gets the offline page to avoid a loop
    private var lastRecoveryAtMs = 0L

    // last dashboard url we asked the webview to load so redundant reloads can be skipped
    private var lastRequestedUrl: String? = null

    // js injected before the first paint would run against a blank document
    private var firstPaintDone = false
    private val pendingJs = mutableListOf<String>()

    private var webViewPausedForSleep = false
    private var inAODMode = false

    // a translucent activity like the switcher only pauses us so the page never stopped
    private var stoppedSinceResume = false

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
                // a new user agent or start script only applies to the next page load
                val pageSetupChanged = applyUserAgent(webView.settings) or applyReduceMotion(webView) or
                    applyBatchUpdates(webView)
                if (pageSetupChanged || webviewUrl != lastRequestedUrl || isOfflineUrl(webView.url)) {
                    Log.d(TAG, "Reloading WebView due to settings change: $webviewUrl")
                    loadDashboard(webviewUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reloading WebView on settings change", e)
            }
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

    // home assistant handed over or took back the dashboard login
    private val haLoginReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                applyHaLogin(webView, webView.url)
            } catch (e: Exception) {
                Log.e(TAG, "Error applying the dashboard login", e)
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

    init {
        registerBroadcastReceivers()
    }

    // lifecycle

    override fun onResume() {
        // covers the case where the screensaver stopped broadcast did not fire first
        resumeWebViewFromSleep()

        // refire the js hooks so a page suspended in the background can refresh its state
        if (stoppedSinceResume) {
            stoppedSinceResume = false
            mShellyElevateJavascriptInterface.onScreenOn()
            mShellyElevateJavascriptInterface.onScreensaverOff()
        }

        if (!initialLoadDone) safeInitialLoad()
    }

    // back walks the dashboard history instead of leaving the kiosk
    override fun onBackPressed(): Boolean {
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    override fun onStop() {
        stoppedSinceResume = true
        cancelRetry()
    }

    override fun onDestroy() {
        destroyed = true
        // a later module or webview must not report the page of this one
        WebViewDisplayModule.shownUrl = null
        unregisterBroadcastReceivers()
        initialLoadJob?.cancel()
        initialLoadJob = null
        cancelRetry()
        aodTickHandler.removeCallbacksAndMessages(null)
        sleepHandler.removeCallbacksAndMessages(null)
        pendingJs.clear()
        // destroy the webview or every crash relaunch leaks a full renderer
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "webview destroy failed: ${e.message}")
        }
    }

    // receivers

    private fun registerBroadcastReceivers() {
        broadcastManager.apply {
            registerReceiver(settingsChangedReceiver, IntentFilter(INTENT_SETTINGS_CHANGED))
            registerReceiver(webviewRefreshReceiver, IntentFilter(INTENT_WEBVIEW_REFRESH))
            registerReceiver(javascriptInjectReceiver, IntentFilter(INTENT_WEBVIEW_INJECT_JAVASCRIPT))
            registerReceiver(haLoginReceiver, IntentFilter(INTENT_HA_LOGIN_CHANGED))
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
            unregisterReceiver(haLoginReceiver)
            unregisterReceiver(screenStateReceiver)
            unregisterReceiver(aodReceiver)
        }
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
            webView.visibility = View.INVISIBLE
            sleepHandler.removeCallbacks(suspendHaRunnable)
            sleepHandler.postDelayed(suspendHaRunnable, HaFrontend.HIDDEN_SUSPEND_MS)
            Log.i(TAG, "webview paused for sleep")
        } catch (e: Exception) {
            Log.w(TAG, "pauseWebViewForSleep failed: ${e.message}")
        }
    }

    private fun resumeWebViewFromSleep() {
        if (!webViewPausedForSleep) return
        webViewPausedForSleep = false
        // the suspend and its repause both belong to the sleep that just ended
        sleepHandler.removeCallbacksAndMessages(null)
        try {
            webView.visibility = View.VISIBLE
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
            webView.resumeTimers()
            webView.onResume()
            webView.settings.offscreenPreRaster = preRaster
            Log.i(TAG, "aod mode exited")
        } catch (e: Exception) {
            Log.w(TAG, "exitAODMode failed: ${e.message}")
        }
    }

    // loading

    private fun loadDashboard(url: String) {
        if (destroyed) return
        // any explicit load makes a pending offline retry obsolete
        cancelRetry()
        // the old page stops counting as shown once another dashboard is requested
        if (url != lastRequestedUrl && WebViewDisplayModule.shownUrl != null) {
            WebViewDisplayModule.shownUrl = null
            ApiHub.stateChanged()
        }
        lastRequestedUrl = url
        webView.loadUrl(url)
    }

    // hands the home assistant session to the page or drops it after a logout
    // returns true when the page got the session
    private fun applyHaLogin(target: WebView, pageUrl: String?): Boolean {
        if (destroyed || pageUrl.isNullOrEmpty()) return false
        val store = HaLoginStore.get(activity)
        store.takeLogout(pageUrl)?.let { origin ->
            target.evaluateJavascript(HaLoginRules.logoutScript(origin), null)
            return false
        }
        return handOverHaLogin(target, store, pageUrl)
    }

    // the frontend is about to open the login page so the current page gets the session instead
    // and the login never shows
    private fun interceptHaLogin(view: WebView, url: String): Boolean {
        val origin = HaLoginRules.originOf(url) ?: return false
        if (!HaLoginRules.isAuthorizePage(url, origin) || !HaLoginRules.sameOrigin(view.url, origin)) return false
        return handOverHaLogin(view, HaLoginStore.get(activity), url)
    }

    private fun handOverHaLogin(target: WebView, store: HaLoginStore, pageUrl: String): Boolean {
        val dashboard = ServiceHelper.getWebviewUrl()
        val session = store.handOver(pageUrl, dashboard) ?: return false
        Log.i(TAG, "Logging the dashboard in as ${session.userName}")
        target.evaluateJavascript(
            HaLoginRules.loginScript(session.origin, session.clientId, session.refreshToken, dashboard), null
        )
        return true
    }

    private fun showOfflinePage(view: WebView, failingUrl: String?) {
        // never reload the offline page itself or a broken asset would loop
        if (!isOfflineUrl(failingUrl)) view.post { view.loadUrl(OFFLINE_URL) }
    }

    private fun safeInitialLoad() {
        initialLoadDone = true
        initialLoadJob = activity.lifecycleScope.launch(Dispatchers.Default) {
            val url = ServiceHelper.getWebviewUrl()
            val online = ServiceHelper.isNetworkReady(activity.applicationContext)
            withContext(Dispatchers.Main) {
                initialLoadJob = null
                if (destroyed) return@withContext
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
        if (destroyed) return
        retryJob = activity.lifecycleScope.launch(Dispatchers.Default) {
            // fast backoff for the window right after boot then a slow poll to ride out long wifi outages
            val delays = sequence {
                yieldAll(RETRY_BACKOFF_MS)
                while (true) yield(RETRY_POLL_MS)
            }
            for (delayMs in delays) {
                delay(delayMs)
                if (ServiceHelper.isNetworkReady(activity.applicationContext)) {
                    withContext(Dispatchers.Main) {
                        // drop the reference first so loadDashboard does not cancel this job mid flight
                        retryJob = null
                        if (!destroyed) loadDashboard(targetUrl)
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

    // same id so older lookups by R.id.myWebView keep working
    private fun createWebView(): WebView = WebView(activity).apply {
        id = R.id.myWebView
        overScrollMode = View.OVER_SCROLL_NEVER
        // the fading view scrollbars redraw the whole webview for every frame of the fade
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        configureWebView(this)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView(target: WebView) {
        applyWebSettings(target.settings)

        target.apply {
            // no hardware layer since the webview draws through its own gl functor
            // and a layer would add a full screen offscreen copy to every frame

            // let the renderer drop priority whenever the webview is covered
            // by settings or a screensaver activity instead of only during aod
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }

            if (documentStartScriptSupported) {
                WebViewCompat.addDocumentStartJavaScript(this, HaFrontend.DOCUMENT_START_SCRIPT, setOf("*"))
            }
            // a handler of a crashed webview means nothing for this one
            reduceMotionScript = null
            applyReduceMotion(this)
            batchUpdatesScript = null
            applyBatchUpdates(this)

            webViewClient = DashboardWebViewClient()
            webChromeClient = DashboardChromeClient()
            addJavascriptInterface(mShellyElevateJavascriptInterface, "ShellyElevate")

            // the swipe helper is fed only by GestureInterceptLayout
            // consuming keeps a wake touch from reaching the page
            setOnTouchListener { _, event -> host.onContentTouch(event) }
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
        settings.offscreenPreRaster = preRaster
        // no prompt handler grants location so fail requests at once instead of leaving them pending
        settings.setGeolocationEnabled(false)
        applyUserAgent(settings)

        // the dashboard does its own theming and chromium auto darken washes out colors on this panel
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }
    }

    // returns true when the user agent changed
    private fun applyUserAgent(settings: WebSettings): Boolean {
        val wanted = if (documentStartScriptSupported && mSharedPreferences.getBoolean(SP_WEBVIEW_MODERN_FRONTEND, false)) {
            HaFrontend.modernUserAgent(WebSettings.getDefaultUserAgent(activity))
        } else {
            null
        }
        // always set since a webview rebuilt after a crash starts with the default
        // null restores the default user agent
        settings.userAgentString = wanted
        val changed = wanted != appliedUserAgent
        appliedUserAgent = wanted
        return changed
    }

    // returns true when the script was added or removed
    private fun applyReduceMotion(target: WebView): Boolean {
        val wanted = documentStartScriptSupported && mSharedPreferences.getBoolean(SP_WEBVIEW_REDUCE_MOTION, false)
        val current = reduceMotionScript
        if (wanted == (current != null)) return false
        if (wanted) {
            reduceMotionScript = WebViewCompat.addDocumentStartJavaScript(target, HaFrontend.REDUCED_MOTION_SCRIPT, setOf("*"))
        } else {
            current?.remove()
            reduceMotionScript = null
        }
        return true
    }

    // returns true when the script was added or removed
    private fun applyBatchUpdates(target: WebView): Boolean {
        val wanted = documentStartScriptSupported && mSharedPreferences.getBoolean(SP_WEBVIEW_BATCH_UPDATES, true)
        val current = batchUpdatesScript
        if (wanted == (current != null)) return false
        if (wanted) {
            batchUpdatesScript = WebViewCompat.addDocumentStartJavaScript(target, HaFrontend.BATCH_UPDATES_SCRIPT, setOf("*"))
        } else {
            current?.remove()
            batchUpdatesScript = null
        }
        return true
    }

    // swaps in a fresh webview at the same spot after the renderer died
    private fun recoverWebView(crashed: WebView, didCrash: Boolean) {
        val parent = crashed.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(crashed)
        val layoutParams = crashed.layoutParams

        // detach before destroy so we never touch a destroyed view that is still attached
        parent.removeViewAt(index)
        crashed.destroy()

        val fresh = createWebView()
        webView = fresh
        firstPaintDone = false
        parent.addView(fresh, index, layoutParams)
        carrySleepStateTo(fresh)

        // a renderer the system killed for memory did nothing wrong so go straight back to the dashboard
        // a real crash lands on the offline page so a crashing page cannot hot loop
        val now = SystemClock.elapsedRealtime()
        val recentlyRecovered = lastRecoveryAtMs != 0L && now - lastRecoveryAtMs < RECOVERY_LOOP_WINDOW_MS
        lastRecoveryAtMs = now
        val url = lastRequestedUrl
        // a pending offline retry keeps the offline page and brings the dashboard back itself
        if (!didCrash && !recentlyRecovered && retryJob == null && !url.isNullOrEmpty()) {
            loadDashboard(url)
            Log.i(TAG, "Recovered WebView after renderer kill, reloading dashboard")
        } else {
            fresh.loadUrl(OFFLINE_URL)
            Log.i(TAG, "Recovered WebView after crash, showing offline page")
        }
    }

    // a fresh webview starts awake so give it the sleep or aod state the old one had
    private fun carrySleepStateTo(target: WebView) {
        if (!webViewPausedForSleep && !inAODMode) return
        target.onPause()
        target.pauseTimers()
        if (webViewPausedForSleep) target.visibility = View.INVISIBLE
        if (inAODMode) {
            target.settings.offscreenPreRaster = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                target.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, false)
            }
        }
    }

    private inner class DashboardWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            firstPaintDone = false
            mShellyElevateJavascriptInterface.onPageStarted()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            reportShownUrl(url)
            RendererPriority.boostAsync()
        }

        // single page dashboards switch views through the history api without a new page load
        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            reportShownUrl(url)
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            firstPaintDone = true
            if (pendingJs.isNotEmpty()) {
                pendingJs.forEach { webView.evaluateJavascript(it, null) }
                pendingJs.clear()
            }
            if (view != null) applyHaLogin(view, url)
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
                return WebResourceResponse("text/html", "UTF-8", activity.assets.open(OFFLINE_PAGE))
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            if (view != null && request.isForMainFrame && interceptHaLogin(view, url)) return true
            if (!url.startsWith(APP_URL_SCHEME)) return false
            when (url.removePrefix(APP_URL_SCHEME)) {
                "reload" -> view?.post { loadDashboard(ServiceHelper.getWebviewUrl()) }
                "offline" -> view?.post { view.loadUrl(OFFLINE_URL) }
                "settings" -> host.openSettings()
            }
            return true
        }

        // the webview only calls this on api 26 and up
        @SuppressLint("NewApi")
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e(TAG, "WebView render process crashed; didCrash=${detail.didCrash()}")
            try {
                recoverWebView(view, detail.didCrash())
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
                activity, Manifest.permission.RECORD_AUDIO
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
            // release builds only pass warnings and errors on to logcat
            if (!BuildConfig.DEBUG && consoleMessage.messageLevel() in QUIET_CONSOLE_LEVELS) return true
            return super.onConsoleMessage(consoleMessage)
        }
    }

    private fun reportShownUrl(url: String?) {
        // the offline fallback is not a page the user configured
        if (destroyed || url.isNullOrEmpty() || isOfflineUrl(url) || url.startsWith(ASSET_URL_PREFIX)) return
        if (url == WebViewDisplayModule.shownUrl) return
        WebViewDisplayModule.shownUrl = url
        ApiHub.stateChanged()
    }

    private fun isOfflineUrl(url: String?): Boolean = url?.contains(OFFLINE_PAGE) == true

    private companion object {
        const val TAG = "WebViewContent"

        const val OFFLINE_PAGE = "offline.html"
        const val ASSET_URL_PREFIX = "file:///android_asset/"
        const val OFFLINE_URL = "$ASSET_URL_PREFIX$OFFLINE_PAGE"
        const val APP_URL_SCHEME = "shellyelevate:"

        const val MAX_PENDING_JS = 50

        val QUIET_CONSOLE_LEVELS = setOf(
            ConsoleMessage.MessageLevel.LOG,
            ConsoleMessage.MessageLevel.DEBUG,
            ConsoleMessage.MessageLevel.TIP,
        )

        const val AOD_TICK_PERIOD_MS = 1000L
        const val AOD_TICK_WINDOW_MS = 200L

        // long enough for the suspend script and the socket close to run before timers pause again
        const val HA_SUSPEND_RUN_MS = 2_000L

        val RETRY_BACKOFF_MS = listOf(2000L, 4000L, 8000L, 16000L)
        const val RETRY_POLL_MS = 30_000L

        const val RECOVERY_LOOP_WINDOW_MS = 60_000L

        // a 1 gb device reports a little under 1 gb and a 2 gb device well above this
        const val LOW_MEMORY_BYTES = 1536L * 1024 * 1024

        fun isLowMemoryDevice(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
            if (am.isLowRamDevice) return true
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            return info.totalMem < LOW_MEMORY_BYTES
        }
    }
}
