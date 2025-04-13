package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import me.rapierxbox.shellyelevatev2.BuildConfig
import me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_INJECT_JAVASCRIPT
import me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_REFRESH
import me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mShellyElevateJavascriptInterface
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwipeHelper
import me.rapierxbox.shellyelevatev2.databinding.MainActivityBinding
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManagerHolder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainActivity : ComponentActivity() {
    private lateinit var binding: MainActivityBinding // Declare the binding object

    private var clicksButtonRight: Int = 0
    private var clicksButtonLeft: Int = 0

    private var webviewRefreshBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
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
        webSettings.databaseEnabled = true

        webSettings.javaScriptCanOpenWindowsAutomatically = true

        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        binding.myWebView.webViewClient = WebViewClient()
        binding.myWebView.webChromeClient = WebChromeClient()

        binding.myWebView.addJavascriptInterface(mShellyElevateJavascriptInterface, "ShellyElevate")

        binding.myWebView.loadUrl(ServiceHelper.getWebviewUrl())
    }

    override fun onResume() {
        super.onResume()
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
            if (ScreenSaverManagerHolder.getInstance().onTouchEvent()) {
                Log.d("ShellyElevateV2", "Touch blocked by ScreenSaverManager")
                return@setOnTouchListener true
            }
            mSwipeHelper.onTouchEvent(event)
            binding.myWebView.onTouchEvent(event)

            return@setOnTouchListener true
        }

        val localBroadcastManager: LocalBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.registerReceiver(webviewRefreshBroadcastReceiver, IntentFilter(INTENT_WEBVIEW_REFRESH))
        localBroadcastManager.registerReceiver(webviewJavascriptInjectorBroadcastReceiver, IntentFilter(INTENT_WEBVIEW_INJECT_JAVASCRIPT))

        if (!getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE).getBoolean("settingEverShown", false) || BuildConfig.DEBUG)
            startActivity(Intent(this, SettingsActivity::class.java))
    }
}