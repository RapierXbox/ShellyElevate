package me.rapierxbox.shellyelevatev2

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.updateLayoutParams
import androidx.annotation.RequiresPermission
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rapierxbox.shellyelevatev2.Constants.INTENT_PROXIMITY_UPDATED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STARTED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SCREEN_SAVER_STOPPED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_OFF
import me.rapierxbox.shellyelevatev2.Constants.INTENT_TURN_SCREEN_ON
import me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_INJECT_JAVASCRIPT
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_STATE_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_STATE_KEY
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_TEXT
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_TEXT_KEY
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_SCORE
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_SCORE_KEY
import me.rapierxbox.shellyelevatev2.Constants.INTENT_VOICE_THRESHOLD_KEY
import me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_SCORE_BAR_ENABLED
import androidx.core.content.ContextCompat
import me.rapierxbox.shellyelevatev2.voice.VoiceAssistantManager
import me.rapierxbox.shellyelevatev2.Constants.SP_IGNORE_SSL_ERRORS
import me.rapierxbox.shellyelevatev2.Constants.SP_SETTINGS_EVER_SHOWN
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMQTTServer
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mMediaHelper
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mShellyElevateJavascriptInterface
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwipeHelper
import me.rapierxbox.shellyelevatev2.databinding.MainActivityBinding
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper
import me.rapierxbox.shellyelevatev2.helper.ButtonPressDetector
import me.rapierxbox.shellyelevatev2.Constants.SP_POWER_BUTTON_AUTO_REBOOT
import android.provider.Settings
import android.net.Uri
import java.io.IOException

class MainActivity : ComponentActivity() {
    private var isActive: Boolean = false
    private var initialLoadDone = false
    private var retryJob: kotlinx.coroutines.Job? = null
    private var firstPaintDone = false
    private val pendingJs = mutableListOf<String>()

    private lateinit var binding: MainActivityBinding // Declare the binding object

    private var clicksButtonRight: Int = 0
    private var clicksButtonLeft: Int = 0

    // Button press detectors for regular buttons (0-3) and power button (140)
    private lateinit var buttonPressDetector0: ButtonPressDetector
    private lateinit var buttonPressDetector1: ButtonPressDetector
    private lateinit var buttonPressDetector2: ButtonPressDetector
    private lateinit var buttonPressDetector3: ButtonPressDetector
    private lateinit var powerButtonPressDetector: ButtonPressDetector

    // === SETTINGS CHANGED RECEIVER ===
    private val settingsChangedBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val webviewUrl = ServiceHelper.getWebviewUrl()
                Log.d("MainActivity", "Reloading WebView due to settings change: $webviewUrl")
                binding.myWebView.loadUrl(webviewUrl)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error reloading WebView on settings change", e)
            }
            applyScoreBarSetting()
        }
    }

    // === WEBVIEW JS INJECTOR RECEIVER ===
    private val webviewJavascriptInjectorBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val javascriptCode = intent?.getStringExtra("javascript")?.trim() ?: return
            try {
                if (!firstPaintDone) {
                    pendingJs.add(javascriptCode)
                    Log.d("MainActivity", "Queueing JS until first paint")
                    return
                }
                Log.d("MainActivity", "Injecting JS into WebView")
                binding.myWebView.evaluateJavascript(javascriptCode, null)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error injecting JS", e)
            }
        }
    }

    private val voiceStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stateName = intent?.getStringExtra(INTENT_VOICE_STATE_KEY) ?: return
            val active = stateName == VoiceAssistantManager.State.LISTENING.name
                    || stateName == VoiceAssistantManager.State.PROCESSING.name
            binding.voiceIndicatorDot.visibility = if (active) View.VISIBLE else View.GONE
            if (active) resetScoreBar()
        }
    }

    private var scoreBarRegistered = false

    private val colorGreen by lazy { ContextCompat.getColor(this, R.color.voice_score_green) }
    private val colorAmber by lazy { ContextCompat.getColor(this, R.color.voice_score_amber) }
    private val colorRed   by lazy { ContextCompat.getColor(this, R.color.voice_score_red)   }

    private fun resetScoreBar() {
        binding.voiceScoreBar.updateLayoutParams { height = 0 }
        binding.voiceScoreValue.text = ".00"
        binding.voiceScoreBar.setBackgroundColor(colorGreen)
    }

    private val voiceScoreReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val score = intent?.getFloatExtra(INTENT_VOICE_SCORE_KEY, 0f) ?: 0f
            val threshold = intent?.getFloatExtra(INTENT_VOICE_THRESHOLD_KEY, 0.5f) ?: 0.5f
            val h = binding.voiceScoreBarContainer.height.takeIf { it > 0 } ?: return

            binding.voiceScoreBar.updateLayoutParams { height = (h * score.coerceIn(0f, 1f)).toInt() }

            val thrPx = (h * threshold.coerceIn(0f, 1f)).toInt()
            (binding.voiceThresholdLine.layoutParams as android.widget.FrameLayout.LayoutParams)
                .bottomMargin = thrPx
            binding.voiceThresholdLine.requestLayout()
            binding.voiceThresholdLine.invalidate()

            val barColor = when {
                score >= threshold -> colorRed
                score >= threshold * 0.5f -> colorAmber
                else -> colorGreen
            }
            binding.voiceScoreBar.setBackgroundColor(barColor)
            binding.voiceScoreValue.text = String.format("%.2f", score)
        }
    }

    private fun applyScoreBarSetting() {
        val enabled = mSharedPreferences.getBoolean(SP_VOICE_SCORE_BAR_ENABLED, false)
        if (enabled && !scoreBarRegistered) {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                voiceScoreReceiver, IntentFilter(INTENT_VOICE_SCORE))
            scoreBarRegistered = true
            binding.voiceScoreBarContainer.visibility = View.VISIBLE
        } else if (!enabled && scoreBarRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(voiceScoreReceiver)
            scoreBarRegistered = false
            binding.voiceScoreBarContainer.visibility = View.GONE
        }
    }

    private val voiceTextReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(INTENT_VOICE_TEXT_KEY) ?: ""
            if (text.isEmpty()) {
                binding.voiceBubble.visibility = View.GONE
            } else {
                binding.voiceBubbleText.text = text
                binding.voiceBubble.visibility = View.VISIBLE
            }
        }
    }

    // === SCREEN STATE RECEIVER ===
    private val screenStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            try {
                when (action) {
                    INTENT_TURN_SCREEN_ON -> mShellyElevateJavascriptInterface.onScreenOn()
                    INTENT_TURN_SCREEN_OFF -> mShellyElevateJavascriptInterface.onScreenOff()
                    INTENT_SCREEN_SAVER_STARTED -> mShellyElevateJavascriptInterface.onScreensaverOn()
                    INTENT_SCREEN_SAVER_STOPPED -> mShellyElevateJavascriptInterface.onScreensaverOff()
                    INTENT_PROXIMITY_UPDATED -> mShellyElevateJavascriptInterface.onMotion()
                }
                if (BuildConfig.DEBUG) Log.d("MainActivity", "screenStateReceiver invoked: $action")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error handling screen state: $action", e)
            }
        }
    }

    var offlineFile = "file:///android_asset/offline.html"

    /**
     * Initialize button press detectors for all buttons that support press type detection.
     * Each detector will invoke its callback when a press sequence is complete.
     */
    private fun initializeButtonPressDetectors() {
        val pressCallback = ButtonPressDetector.Callback { buttonId, pressType ->
            onButtonPressTypeDetected(buttonId, pressType)
        }

        buttonPressDetector0 = ButtonPressDetector(0, pressCallback)
        buttonPressDetector1 = ButtonPressDetector(1, pressCallback)
        buttonPressDetector2 = ButtonPressDetector(2, pressCallback)
        buttonPressDetector3 = ButtonPressDetector(3, pressCallback)
        powerButtonPressDetector = ButtonPressDetector(140, pressCallback)
    }

    /**
     * Called when a button press type is detected (short, long, double, triple).
     * For power button, may trigger reboot if auto-reboot is enabled.
     */
    private fun onButtonPressTypeDetected(buttonId: Int, pressType: String) {
        Log.d("MainActivity", "Button $buttonId press type detected: $pressType")

        if (buttonId == 140) {
            // Power button handling
            handlePowerButtonPress(pressType)
        } else {
            // Regular button handling (0-3)
            publishButtonPress(buttonId, pressType)
        }
    }

    /**
     * Handle power button press with optional auto-reboot on long press.
     */
    private fun handlePowerButtonPress(pressType: String) {
        publishButtonPress(140, pressType)

        if (pressType == Constants.BUTTON_PRESS_TYPE_LONG) {
            val autoReboot = mSharedPreferences.getBoolean(SP_POWER_BUTTON_AUTO_REBOOT, true)
            if (autoReboot) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        Runtime.getRuntime().exec("reboot")
                    } catch (e: IOException) {
                        Log.e("MainActivity", "Error rebooting:", e)
                    }
                }
            }
        }
    }

    /**
     * Publish a button press to MQTT and JavaScript interface.
     */
    private fun publishButtonPress(buttonId: Int, pressType: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            mMQTTServer.publishButton(buttonId, pressType)
        }
        mShellyElevateJavascriptInterface.onButtonPressed(buttonId)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSettingsButtons() {
        binding.settingButtonOverlayRight.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) clicksButtonRight++
            false
        }
        binding.settingButtonOverlayLeft.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (clicksButtonRight == 10) clicksButtonLeft++ else resetClicks()
                if (clicksButtonLeft == 10) startSettingsActivity()
            }
            false
        }
    }

    private fun startSettingsActivity() {
        resetClicks()
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun resetClicks() {
        clicksButtonLeft = 0
        clicksButtonRight = 0
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val webSettings: WebSettings = binding.myWebView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.javaScriptCanOpenWindowsAutomatically = true
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webSettings.allowFileAccess = true

        @Suppress("DEPRECATION")
        webSettings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        webSettings.allowUniversalAccessFromFileURLs = true

        @Suppress("DEPRECATION")
        webSettings.databaseEnabled = true

        @Suppress("DEPRECATION")
        webSettings.setRenderPriority(WebSettings.RenderPriority.NORMAL)

        // Disable WebView-led darkening to avoid tone-mapped/desaturated colors
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webSettings, WebSettingsCompat.FORCE_DARK_OFF)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, false)
        }

        // Hint Chromium to preraster when appropriate (improves first paint)
        webSettings.offscreenPreRaster = true

        binding.myWebView.apply {
            // Ensure hardware acceleration stays enabled for proper color/gamut handling
            if (layerType != View.LAYER_TYPE_HARDWARE) {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            webViewClient = object : WebViewClient() {

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    firstPaintDone = false
                }

                // Helper to know if we already show the offline page
                private fun isOfflineUrl(url: String?): Boolean {
                    return url != null && url.contains("offline.html")
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    try {
                        if (mSharedPreferences.getBoolean(SP_IGNORE_SSL_ERRORS, false)) {
                            handler?.proceed()
                        } else {
                            handler?.cancel()
                            // show offline page if SSL blocks main frame
                            view?.post {
                                if (!isOfflineUrl(offlineFile)) view.loadUrl(offlineFile)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "SSL error handling failed", e)
                    }
                }

                // Modern HTTP error (API 23+)
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse?) {
                    try {
                        if (request.isForMainFrame) {
                            val failingUrl = request.url.toString()
                            Log.w("MainActivity", "HTTP error for main frame: $failingUrl -> ${errorResponse?.statusCode}")
                            view.post {
                                if (!isOfflineUrl(failingUrl)) view.loadUrl(offlineFile)
                            }
                        } else {
                            super.onReceivedHttpError(view, request, errorResponse)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "onReceivedHttpError failed", e)
                    }
                }

                // Legacy and generic network errors (covers timeouts, dns, connect)
                @Deprecated("deprecated")
                override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                    try {
                        Log.w("MainActivity", "Legacy onReceivedError for $failingUrl: $description")
                        if (!isOfflineUrl(failingUrl)) view.post { view.loadUrl(offlineFile) }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "legacy onReceivedError failed", e)
                    }
                }

                // Modern variant (API 23+)
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    try {
                        if (request.isForMainFrame) {
                            val failingUrl = request.url.toString()
                            Log.w("MainActivity", "onReceivedError main frame: $failingUrl - ${error.description}")
                            if (!isOfflineUrl(failingUrl)) view.post { view.loadUrl(offlineFile) }
                        } else {
                            super.onReceivedError(view, request, error)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "onReceivedError failed", e)
                    }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString().orEmpty()
                    // Serve offline.html only when explicitly requested or when you want to map a failed host to offline
                    if (url.contains("offline.html")) {
                        return WebResourceResponse("text/html", "UTF-8", assets.open("offline.html"))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("shellyelevate:")) {
                        when (url.removePrefix("shellyelevate:")) {
                            "reload" -> view?.post { view.loadUrl(ServiceHelper.getWebviewUrl()) }
                            "offline" -> view?.post { view.loadUrl(offlineFile) }
                            "settings" -> startSettingsActivity()
                        }
                        return true
                    }
                    return false
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Log.e("MainActivity", "WebView render process crashed; didCrash=${detail.didCrash()}")

                    try {
                        val parent = view.parent as? ViewGroup ?: return true
                        val index = parent.indexOfChild(view)
                        val lp = view.layoutParams

                        // Remove before destroy
                        parent.removeViewAt(index)
                        view.destroy()

                        // Create and configure new WebView
                        val newWebView = WebView(this@MainActivity)
                        binding.myWebView = newWebView
                        configureWebView()

                        // Re‑insert at same position
                        parent.addView(newWebView, index, lp)

                        // Guard against loops: only load offline if not already showing
                        if (newWebView.url?.contains("offline.html") != true) {
                            newWebView.post { newWebView.loadUrl(offlineFile) }
                        }

                        // Breadcrumb logging for diagnostics
                        Log.i("MainActivity", "Recovered WebView after crash, showing offline page")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to recover WebView", e)
                    }

                    // We handled the crash
                    return true
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    firstPaintDone = true
                    if (pendingJs.isNotEmpty()) {
                        pendingJs.forEach { binding.myWebView.evaluateJavascript(it, null) }
                        pendingJs.clear()
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val message = consoleMessage.message()
                    
                    // Filter out PacProcessor ClassNotFoundException errors
                    // This is a known compatibility issue with Google WebView v128+ on targetSdk 24:
                    // The WebView provider tries to load android.webkit.PacProcessor which doesn't 
                    // exist in API 24, but this is benign - WebView still initializes and works correctly.
                    // These errors clutter logs without indicating actual problems.
                    if (message.contains("PacProcessor")) {
                        return true // Suppress
                    }
                    
                    // Log other console messages normally
                    return super.onConsoleMessage(consoleMessage)
                }
            }
            addJavascriptInterface(mShellyElevateJavascriptInterface, "ShellyElevate")
            
            // Add touch listener for touch-to-wake support
            setOnTouchListener { _, event ->
                val sm = ShellyElevateApplication.mScreenManager
                val consumeForWake = sm?.shouldConsumeTouchForWake() == true
                if (BuildConfig.DEBUG) Log.d("MainActivity", "Touch event detected on WebView, mScreenManager=$sm, consumeForWake=$consumeForWake")
                mSwipeHelper?.onTouchEvent(event)
                mScreenSaverManager.onTouchEvent(event)
                sm?.onTouchEvent()
                consumeForWake // true means we handled it for wake and don't pass to WebView
            }
            
            // Delay first load slightly to allow system services to settle after boot
            //postDelayed({ loadUrl(ServiceHelper.getWebviewUrl()) }, 500)
        }
    }

    private fun registerBroadcastReceivers() {
        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(settingsChangedBroadcastReceiver, IntentFilter(INTENT_SETTINGS_CHANGED))
            registerReceiver(webviewJavascriptInjectorBroadcastReceiver, IntentFilter(INTENT_WEBVIEW_INJECT_JAVASCRIPT))
            registerReceiver(voiceStateReceiver, IntentFilter(INTENT_VOICE_STATE_CHANGED))
            registerReceiver(voiceTextReceiver, IntentFilter(INTENT_VOICE_TEXT))
            registerReceiver(screenStateReceiver, IntentFilter().apply {
                addAction(INTENT_TURN_SCREEN_ON)
                addAction(INTENT_TURN_SCREEN_OFF)
                addAction(INTENT_SCREEN_SAVER_STARTED)
                addAction(INTENT_SCREEN_SAVER_STOPPED)
                addAction(INTENT_PROXIMITY_UPDATED)
            })
        }
    }

    private fun broadcastProximity(value: Float) {
        val intent = Intent(INTENT_PROXIMITY_UPDATED)
            .putExtra(Constants.INTENT_PROXIMITY_KEY, value)

        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun safeInitialLoad() {
        lifecycleScope.launch(Dispatchers.Default) {
            val url = ServiceHelper.getWebviewUrl()
            val online = ServiceHelper.isNetworkReady(applicationContext)

            withContext(Dispatchers.Main) {
                if (online) {
                    binding.myWebView.loadUrl(url)
                    initialLoadDone = true
                    cancelRetry()
                } else {
                    binding.myWebView.loadUrl(offlineFile)
                    initialLoadDone = true
                    scheduleRetryOnlineAfterOffline(url)
                }
            }
        }
    }

    private fun scheduleRetryOnlineAfterOffline(targetUrl: String) {
        cancelRetry()
        retryJob = lifecycleScope.launch(Dispatchers.Default) {
            // Simple backoff: 2s, 4s, 8s, 16s (max 4 attempts)
            val delays = listOf(2000L, 4000L, 8000L, 16000L)
            for (d in delays) {
                kotlinx.coroutines.delay(d)
                if (!isActive) return@launch
                val online = ServiceHelper.isNetworkReady(applicationContext)
                if (online) {
                    withContext(Dispatchers.Main) {
                        binding.myWebView.loadUrl(targetUrl)
                        cancelRetry()
                    }
                    return@launch
                }
            }
            // Optional: keep a final slow retry every 30s
            while (isActive) {
                kotlinx.coroutines.delay(30000L)
                val online = ServiceHelper.isNetworkReady(applicationContext)
                if (online) {
                    withContext(Dispatchers.Main) {
                        binding.myWebView.loadUrl(targetUrl)
                        cancelRetry()
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

    override fun onResume() {
        super.onResume()

        setScreenOptions()

        // In case screen is already on and app resumes
        mShellyElevateJavascriptInterface.onScreenOn()
        mShellyElevateJavascriptInterface.onScreensaverOff()

        if (!initialLoadDone) {
            safeInitialLoad() // does connectivity check and loads either URL or offline
            initialLoadDone = true
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start KioskService as foreground service
        ServiceHelper.ensureKioskService(applicationContext)

        // Request WRITE_SETTINGS permission for brightness control
        requestWriteSettingsPermission()

        // handle screen options
        setScreenOptions()

        binding = MainActivityBinding.inflate(layoutInflater) // Inflate the binding
        setContentView(binding.root) // Set the content view using binding.root

        // Initialize button press detectors
        initializeButtonPressDetectors()

        configureWebView()
        setupSettingsButtons()
        setupSwipeOverlay()

        registerBroadcastReceivers()
        applyScoreBarSetting()

        // Only show settings on first run, but not if launched from settings or if already in settings task
        if (!mSharedPreferences.getBoolean(SP_SETTINGS_EVER_SHOWN, false)) {
            // Check if SettingsActivity is already in the task stack
            val settingsAlreadyRunning = isActivityInStack(SettingsActivity::class.java.name)
            if (!settingsAlreadyRunning && !isTaskRoot) {
                startActivity(Intent(this, SettingsActivity::class.java))
            } else if (isTaskRoot) {
                // Only start if this is the root activity (not being relaunched after crash)
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeOverlay() {
        binding.swipeDetectionOverlay.setOnTouchListener { _, event ->
            false // touch handling lives on WebView listener to avoid duplicate gesture processing
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Log everything for debugging
        //Log.d("MainActivity", "dispatchKeyEvent: $event")

        val handled = onKeyEventInternal(event.keyCode, event)

        // Then always forward to WebView
        // if (!handled) binding.myWebView.post { binding.myWebView.dispatchKeyEvent(event) }

        // Return true if you handled it, otherwise let Activity super handle it
        return handled || super.dispatchKeyEvent(event)
    }

    private fun onKeyEventInternal(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (BuildConfig.DEBUG) Log.d("MainActivity", "Key pressed: $keyCode - Event: $event")
        when (keyCode) {
            // Power button - use detector for press type (short/long/double/triple)
            140 -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> powerButtonPressDetector.onPressDown()
                    KeyEvent.ACTION_UP -> powerButtonPressDetector.onPressUp()
                }
                return true
            }
            // Switch inputs (treated as edge-triggered on ACTION_UP)
            141 -> { if (event.action == KeyEvent.ACTION_UP) switchInput(0, true); return true }
            142 -> { if (event.action == KeyEvent.ACTION_UP) switchInput(1, true); return true }

            // Shelly input buttons - use detector for press type (short/long/double/triple)
            131 -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> buttonPressDetector0.onPressDown()
                    KeyEvent.ACTION_UP -> { toggleMappedRelay(0); buttonPressDetector0.onPressUp() }
                }
                return true
            }
            132 -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> buttonPressDetector1.onPressDown()
                    KeyEvent.ACTION_UP -> { toggleMappedRelay(1); buttonPressDetector1.onPressUp() }
                }
                return true
            }
            133 -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> buttonPressDetector2.onPressDown()
                    KeyEvent.ACTION_UP -> { toggleMappedRelay(2); buttonPressDetector2.onPressUp() }
                }
                return true
            }
            134 -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> buttonPressDetector3.onPressDown()
                    KeyEvent.ACTION_UP -> { toggleMappedRelay(3); buttonPressDetector3.onPressUp() }
                }
                return true
            }

            // Proximity
            135 -> { if (event.action == KeyEvent.ACTION_UP) { broadcastProximity(0f); return true }; return false }
            136 -> { if (event.action == KeyEvent.ACTION_UP) { broadcastProximity(0.5f); return true }; return false }

            // Media keys
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (event.action == KeyEvent.ACTION_UP) { mMediaHelper?.resumeOrPauseMusic(); return true }; return false }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (event.action == KeyEvent.ACTION_UP) { mMediaHelper?.resumeMusic(); return true }; return false }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (event.action == KeyEvent.ACTION_UP) { mMediaHelper?.pauseMusic(); return true }; return false }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (event.action == KeyEvent.ACTION_UP) { /* next track */ return true }; return false }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (event.action == KeyEvent.ACTION_UP) { /* prev track */ return true }; return false }

            else -> return false
        }
    }

    private fun switchInput(i: Int, state: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            mMQTTServer.publishSwitch(i, state)
        }
        mShellyElevateJavascriptInterface.onButtonPressed(100 + i)
    }

    /**
     * Toggle the relay mapped to the given button, if button-to-relay mapping is enabled
     * and a relay is configured for this button.
     */
    private fun toggleMappedRelay(buttonId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val relayEnabled = mSharedPreferences.getBoolean(Constants.SP_BUTTON_RELAY_ENABLED, false)
            if (!relayEnabled) return@launch
            val relayIndex = mSharedPreferences.getInt(String.format(java.util.Locale.US, Constants.SP_BUTTON_RELAY_MAP_FORMAT, buttonId), -1)
            if (relayIndex < 0) return@launch
            if (relayIndex >= DeviceModel.getReportedDevice().relays) return@launch
            val deviceHelper = ShellyElevateApplication.mDeviceHelper ?: return@launch
            val currentState = deviceHelper.getRelay(relayIndex)
            deviceHelper.setRelay(relayIndex, !currentState)
        }
    }

    /**
     * Request WRITE_SETTINGS permission for brightness control.
     * This is a special permission that requires explicit user action via Settings.
     * SELinux denials (sysfs access) are expected and work in permissive mode on rooted devices.
     */
    private fun requestWriteSettingsPermission() {
        if (!Settings.System.canWrite(this)) {
            Log.w("MainActivity", "WRITE_SETTINGS permission not granted, requesting...")
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to request WRITE_SETTINGS permission", e)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setScreenOptions() {
        // Full screen support
        enableEdgeToEdge()

        // Prevent system from dimming or turning off the screen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // hide system bars
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).apply {
            unregisterReceiver(settingsChangedBroadcastReceiver)
            unregisterReceiver(webviewJavascriptInjectorBroadcastReceiver)
            unregisterReceiver(voiceStateReceiver)
            unregisterReceiver(voiceTextReceiver)
            if (scoreBarRegistered) unregisterReceiver(voiceScoreReceiver)
            unregisterReceiver(screenStateReceiver)
        }
        cancelRetry()
        super.onDestroy()
    }

    /**
     * Check if an activity with the given class name is already in the task stack
     */
    private fun isActivityInStack(activityClassName: String): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        val tasks = am.getRunningTasks(10)
        for (task in tasks) {
            val activities = task.numActivities
            for (i in 0 until activities) {
                val topActivity = task.topActivity
                if (topActivity?.className == activityClassName) {
                    return true
                }
            }
        }
        return false
    }

    override fun onStop() {
        cancelRetry()
        super.onStop()
    }
}