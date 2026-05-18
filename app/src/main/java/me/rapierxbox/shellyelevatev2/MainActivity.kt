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
import kotlinx.coroutines.isActive
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
import me.rapierxbox.shellyelevatev2.helper.WebViewUpdater
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_UPDATE_PROMPTED
import androidx.appcompat.app.AlertDialog
import me.rapierxbox.shellyelevatev2.Constants.SP_POWER_BUTTON_AUTO_REBOOT
import android.provider.Settings
import android.net.Uri
import java.io.IOException

class MainActivity : ComponentActivity() {
    private var initialLoadDone = false
    private var retryJob: kotlinx.coroutines.Job? = null
    private var firstPaintDone = false
    private val pendingJs = mutableListOf<String>()
    private var webviewUpdatePromptShown = false

    private lateinit var binding: MainActivityBinding
    /** Current WebView; replaced wholesale after a render-process crash. */
    private lateinit var webView: WebView

    private var clicksButtonRight: Int = 0
    private var clicksButtonLeft: Int = 0

    private lateinit var buttonPressDetector0: ButtonPressDetector
    private lateinit var buttonPressDetector1: ButtonPressDetector
    private lateinit var buttonPressDetector2: ButtonPressDetector
    private lateinit var buttonPressDetector3: ButtonPressDetector
    private lateinit var powerButtonPressDetector: ButtonPressDetector

    private val settingsChangedBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val webviewUrl = ServiceHelper.getWebviewUrl()
                Log.d("MainActivity", "Reloading WebView due to settings change: $webviewUrl")
                webView.loadUrl(webviewUrl)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error reloading WebView on settings change", e)
            }
            applyScoreBarSetting()
        }
    }

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
                webView.evaluateJavascript(javascriptCode, null)
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

    private fun onButtonPressTypeDetected(buttonId: Int, pressType: String) {
        Log.d("MainActivity", "Button $buttonId press type detected: $pressType")

        if (buttonId == 140) {
            handlePowerButtonPress(pressType)
        } else {
            publishButtonPress(buttonId, pressType)
        }
    }

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
        val webSettings: WebSettings = webView.settings
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

        // Disable WebView-led darkening; the HA dashboard does its own theming
        // and Chromium's auto-darken produces washed-out colors on this panel.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webSettings, WebSettingsCompat.FORCE_DARK_OFF)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, false)
        }

        webSettings.offscreenPreRaster = true

        webView.apply {
            if (layerType != View.LAYER_TYPE_HARDWARE) {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            webViewClient = object : WebViewClient() {

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    firstPaintDone = false
                }

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
                            view?.post {
                                if (!isOfflineUrl(offlineFile)) view.loadUrl(offlineFile)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "SSL error handling failed", e)
                    }
                }

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

                @Deprecated("deprecated")
                override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                    try {
                        Log.w("MainActivity", "Legacy onReceivedError for $failingUrl: $description")
                        if (!isOfflineUrl(failingUrl)) view.post { view.loadUrl(offlineFile) }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "legacy onReceivedError failed", e)
                    }
                }

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

                        // Order matters: removeView before destroy to avoid touching
                        // a view still attached to its parent.
                        parent.removeViewAt(index)
                        view.destroy()

                        val newWebView = WebView(this@MainActivity)
                        webView = newWebView
                        configureWebView()

                        parent.addView(newWebView, index, lp)

                        // Show offline.html unless we're already there, so a recurring
                        // crash doesn't hot-loop on the failing URL.
                        if (newWebView.url?.contains("offline.html") != true) {
                            newWebView.post { newWebView.loadUrl(offlineFile) }
                        }

                        Log.i("MainActivity", "Recovered WebView after crash, showing offline page")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to recover WebView", e)
                    }

                    return true
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    firstPaintDone = true
                    if (pendingJs.isNotEmpty()) {
                        pendingJs.forEach { webView.evaluateJavascript(it, null) }
                        pendingJs.clear()
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val message = consoleMessage.message()

                    // Suppress benign PacProcessor ClassNotFoundException spam: Google
                    // WebView v128+ probes android.webkit.PacProcessor, which doesn't
                    // exist on API 24. WebView still initialises correctly.
                    if (message.contains("PacProcessor")) {
                        return true
                    }

                    return super.onConsoleMessage(consoleMessage)
                }
            }
            addJavascriptInterface(mShellyElevateJavascriptInterface, "ShellyElevate")

            setOnTouchListener { _, event ->
                val sm = ShellyElevateApplication.mScreenManager
                val consumeForWake = sm?.shouldConsumeTouchForWake() == true
                if (BuildConfig.DEBUG) Log.d("MainActivity", "Touch event detected on WebView, mScreenManager=$sm, consumeForWake=$consumeForWake")
                mSwipeHelper?.onTouchEvent(event)
                mScreenSaverManager.onTouchEvent(event)
                sm?.onTouchEvent()
                // Returning true consumes the event so it isn't delivered to the WebView.
                consumeForWake
            }
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
                    webView.loadUrl(url)
                    initialLoadDone = true
                    cancelRetry()
                } else {
                    webView.loadUrl(offlineFile)
                    initialLoadDone = true
                    scheduleRetryOnlineAfterOffline(url)
                }
            }
        }
    }

    private fun scheduleRetryOnlineAfterOffline(targetUrl: String) {
        cancelRetry()
        retryJob = lifecycleScope.launch(Dispatchers.Default) {
            // Exponential-ish backoff for the early window after boot, then settle
            // into a slow 30s poll so we recover from a long Wi-Fi outage.
            val delays = listOf(2000L, 4000L, 8000L, 16000L)
            for (d in delays) {
                kotlinx.coroutines.delay(d)
                if (!isActive) return@launch
                val online = ServiceHelper.isNetworkReady(applicationContext)
                if (online) {
                    withContext(Dispatchers.Main) {
                        webView.loadUrl(targetUrl)
                        cancelRetry()
                    }
                    return@launch
                }
            }
            while (isActive) {
                kotlinx.coroutines.delay(30000L)
                val online = ServiceHelper.isNetworkReady(applicationContext)
                if (online) {
                    withContext(Dispatchers.Main) {
                        webView.loadUrl(targetUrl)
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

        // Re-fire the JS lifecycle hooks on every resume so a webview that was
        // suspended in the background can refresh its UI state.
        mShellyElevateJavascriptInterface.onScreenOn()
        mShellyElevateJavascriptInterface.onScreensaverOff()

        if (!initialLoadDone) {
            safeInitialLoad()
            initialLoadDone = true
        }

        maybePromptForWebViewUpdate()
    }

    // Ask exactly once, on the first launch where the WebView is actually too
    // old. After the user has answered (Yes or No), the Settings screen is the
    // only entry point for triggering the update again.
    private fun maybePromptForWebViewUpdate() {
        if (webviewUpdatePromptShown) return
        if (mSharedPreferences.getBoolean(SP_WEBVIEW_UPDATE_PROMPTED, false)) return
        if (!WebViewUpdater.isUpdateNeeded(applicationContext)) return

        webviewUpdatePromptShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.webview_update_prompt_title)
            .setMessage(R.string.webview_update_prompt_message)
            .setCancelable(false)
            .setPositiveButton(R.string.webview_update_prompt_yes) { _, _ ->
                mSharedPreferences.edit().putBoolean(SP_WEBVIEW_UPDATE_PROMPTED, true).apply()
                startBackgroundWebViewDownload()
            }
            .setNegativeButton(R.string.webview_update_prompt_no) { _, _ ->
                mSharedPreferences.edit().putBoolean(SP_WEBVIEW_UPDATE_PROMPTED, true).apply()
            }
            .show()
    }

    private fun startBackgroundWebViewDownload() {
        WebViewUpdater.downloadAndStage(object : WebViewUpdater.Listener {
            override fun onProgress(percent: Int) {}
            override fun onCompleted(staged: java.io.File) {
                Log.i("MainActivity", "WebView OTA staged at ${staged.absolutePath}")
                showWebViewRebootDialog()
            }
            override fun onFailed(reason: String) {
                Log.w("MainActivity", "WebView OTA download failed: $reason")
            }
        })
    }

    private fun showWebViewRebootDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.webview_update_reboot_title)
            .setMessage(R.string.webview_update_reboot_message)
            .setPositiveButton(R.string.webview_update_reboot_now) { _, _ -> WebViewUpdater.rebootToInstall() }
            .setNegativeButton(R.string.webview_update_reboot_later, null)
            .show()
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ServiceHelper.ensureKioskService(applicationContext)

        requestWriteSettingsPermission()

        setScreenOptions()

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        webView = binding.myWebView

        initializeButtonPressDetectors()

        configureWebView()
        setupSettingsButtons()
        setupSwipeOverlay()

        registerBroadcastReceivers()
        applyScoreBarSetting()

        // First-run: pop the settings UI so the user can enter HA URL/token. Skip
        // if we're being relaunched from a crash (isTaskRoot==false and Settings
        // already on the task stack), otherwise we'd open it on every relaunch.
        if (!mSharedPreferences.getBoolean(SP_SETTINGS_EVER_SHOWN, false)) {
            val settingsAlreadyRunning = isActivityInStack(SettingsActivity::class.java.name)
            if (!settingsAlreadyRunning && !isTaskRoot) {
                startActivity(Intent(this, SettingsActivity::class.java))
            } else if (isTaskRoot) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeOverlay() {
        // The WebView's own touch listener handles gestures; this overlay is only
        // here for hit-testing on regions outside the web content.
        binding.swipeDetectionOverlay.setOnTouchListener { _, _ -> false }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = onKeyEventInternal(event.keyCode, event)
        return handled || super.dispatchKeyEvent(event)
    }

    private fun onKeyEventInternal(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (BuildConfig.DEBUG) Log.d("MainActivity", "Key pressed: $keyCode - Event: $event")
        when (keyCode) {
            // Power button (140): supports short/long/double/triple press types.
            140 -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> powerButtonPressDetector.onPressDown()
                    KeyEvent.ACTION_UP -> powerButtonPressDetector.onPressUp()
                }
                return true
            }
            // 141/142: physical switch inputs, edge-triggered on release.
            141 -> { if (event.action == KeyEvent.ACTION_UP) switchInput(0, true); return true }
            142 -> { if (event.action == KeyEvent.ACTION_UP) switchInput(1, true); return true }

            // 131..134: capacitive Shelly buttons 0..3; same press-type handling as power.
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

            // 135 = near, 136 = mid; mapped to broadcastProximity values.
            135 -> { if (event.action == KeyEvent.ACTION_UP) { broadcastProximity(0f); return true }; return false }
            136 -> { if (event.action == KeyEvent.ACTION_UP) { broadcastProximity(0.5f); return true }; return false }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (event.action == KeyEvent.ACTION_UP) { mMediaHelper?.resumeOrPauseMusic(); return true }; return false }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (event.action == KeyEvent.ACTION_UP) { mMediaHelper?.resumeMusic(); return true }; return false }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (event.action == KeyEvent.ACTION_UP) { mMediaHelper?.pauseMusic(); return true }; return false }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (event.action == KeyEvent.ACTION_UP) return true; return false }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (event.action == KeyEvent.ACTION_UP) return true; return false }

            else -> return false
        }
    }

    private fun switchInput(i: Int, state: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            mMQTTServer.publishSwitch(i, state)
        }
        mShellyElevateJavascriptInterface.onButtonPressed(100 + i)
    }

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
     * Open the system Settings UI so the user can grant WRITE_SETTINGS, which
     * we need to disable Android's automatic brightness. SELinux denials when
     * writing to the sysfs backlight node are expected on rooted Shelly devices
     * running permissive mode and don't actually block the write.
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
        enableEdgeToEdge()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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