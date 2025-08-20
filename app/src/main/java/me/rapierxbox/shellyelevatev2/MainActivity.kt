package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.http.SslError
import android.os.Bundle
import android.view.MotionEvent
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_INJECT_JAVASCRIPT
import me.rapierxbox.shellyelevatev2.Constants.SP_IGNORE_SSL_ERRORS
import me.rapierxbox.shellyelevatev2.Constants.SP_SETTINGS_EVER_SHOWN
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mShellyElevateJavascriptInterface
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwipeHelper
import me.rapierxbox.shellyelevatev2.databinding.MainActivityBinding
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainActivity : ComponentActivity() {
    private lateinit var binding: MainActivityBinding // Declare the binding object

    private var clicksButtonRight: Int = 0
    private var clicksButtonLeft: Int = 0

    private var settingsChangedBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            binding.myWebView.loadUrl(ServiceHelper.getWebviewUrl())
        }
    }

    private var webviewJavascriptInjectorBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("javascript")?.let { extra ->
                binding.myWebView.evaluateJavascript(extra, null)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSettingsButtons() {
        binding.settingButtonOverlayRight.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                clicksButtonRight++
            }
            return@setOnTouchListener false
        }

        binding.settingButtonOverlayLeft.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (clicksButtonRight == 10) {
                    clicksButtonLeft++
                } else {
                    clicksButtonRight = 0
                    clicksButtonLeft = 0
                }

                if (clicksButtonLeft == 10) {
                    startActivity(Intent(this, SettingsActivity::class.java))

                    clicksButtonRight = 0
                    clicksButtonLeft = 0
                }
            }
            return@setOnTouchListener false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val webSettings: WebSettings = binding.myWebView.settings

        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        webSettings.databaseEnabled = true

        @Suppress("DEPRECATION")
        webSettings.setRenderPriority(WebSettings.RenderPriority.HIGH)

        webSettings.javaScriptCanOpenWindowsAutomatically = true

        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        binding.myWebView.apply {

            webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    if (mSharedPreferences.getBoolean(SP_IGNORE_SSL_ERRORS, false)) {
                        handler?.proceed()
                    } else {
                        super.onReceivedSslError(view, handler, error)
                    }
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(mShellyElevateJavascriptInterface, "ShellyElevate")
            loadUrl(ServiceHelper.getWebviewUrl())
        }
    }

    override fun onResume() {
        super.onResume()
        //This will reload the screen after the screenSaver.
        if (binding.myWebView.originalUrl?.toHttpUrlOrNull() != ServiceHelper.getWebviewUrl().toHttpUrlOrNull())
            binding.myWebView.loadUrl(ServiceHelper.getWebviewUrl())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = MainActivityBinding.inflate(layoutInflater) // Inflate the binding
        setContentView(binding.root) // Set the content view using binding.root

        configureWebView()
        setupSettingsButtons()

        binding.swipeDetectionOverlay.setOnTouchListener { _, event ->
            mSwipeHelper.onTouchEvent(event)
            mScreenSaverManager.onTouchEvent(event)
            binding.myWebView.onTouchEvent(event)

            return@setOnTouchListener true
        }

        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(settingsChangedBroadcastReceiver, IntentFilter(INTENT_SETTINGS_CHANGED))
            registerReceiver(webviewJavascriptInjectorBroadcastReceiver, IntentFilter(INTENT_WEBVIEW_INJECT_JAVASCRIPT))
        }

        if (!mSharedPreferences.getBoolean(SP_SETTINGS_EVER_SHOWN, false))
            startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).apply {
            unregisterReceiver(settingsChangedBroadcastReceiver)
            unregisterReceiver(webviewJavascriptInjectorBroadcastReceiver)
        }
        super.onDestroy()
    }
}