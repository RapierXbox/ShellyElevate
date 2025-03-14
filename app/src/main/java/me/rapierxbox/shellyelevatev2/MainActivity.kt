package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_INJECT_JAVASCRIPT
import me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_REFRESH
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mHttpServer
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwipeHelper
import me.rapierxbox.shellyelevatev2.helper.DeviceSensorManager
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper
import me.rapierxbox.shellyelevatev2.helper.SwipeHelper
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManager

class MainActivity : ComponentActivity() {
    private lateinit var myWebView: WebView
    private lateinit var settingsButtonOverlay1: View
    private lateinit var settingsButtonOverlay2: View
    private lateinit var swipeDetectionOverlay: View

    private var clicksButton1: Int = 0
    private var clicksButton2: Int = 0

    private var webviewRefreshBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            myWebView.reload()
        }
    }

    private var webviewJavascriptInjectorBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            myWebView.evaluateJavascript(intent!!.getStringExtra("javascript")!!, null)
        }
    }


    private fun findViews() {
        myWebView = findViewById(R.id.myWebView)
        settingsButtonOverlay1 = findViewById(R.id.settingButtonOverlay1)
        settingsButtonOverlay2 = findViewById(R.id.settingButtonOverlay2)
        swipeDetectionOverlay = findViewById(R.id.swipeDetectionOverlay)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSettingsButtons() {
        settingsButtonOverlay1.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                clicksButton1++
            }
            return@setOnTouchListener false
        }

        settingsButtonOverlay2.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (clicksButton1 == 10) {
                    clicksButton2++
                } else {
                    clicksButton1 = 0
                    clicksButton2 = 0
                }

                if (clicksButton2 == 10) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    ShellyElevateApplication.updateSPValues()
                    myWebView.loadUrl(ServiceHelper.getWebviewUrl())

                    clicksButton1 = 0
                    clicksButton2 = 0
                }
            }
            return@setOnTouchListener false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val webSettings: WebSettings = myWebView.settings

        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true

        webSettings.javaScriptCanOpenWindowsAutomatically = true

        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        myWebView.webViewClient = WebViewClient()
        myWebView.webChromeClient = WebChromeClient()

        myWebView.loadUrl(ServiceHelper.getWebviewUrl())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.main_activity)

        findViews()
        configureWebView()
        setupSettingsButtons()

        swipeDetectionOverlay.setOnTouchListener { _, event ->
            mScreenSaverManager.onTouchEvent()
            mSwipeHelper.onTouchEvent(event)
            myWebView.onTouchEvent(event)

            return@setOnTouchListener true
        }

        val localBroadcastManager : LocalBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.registerReceiver(webviewRefreshBroadcastReceiver, IntentFilter(INTENT_WEBVIEW_REFRESH))
        localBroadcastManager.registerReceiver(webviewJavascriptInjectorBroadcastReceiver, IntentFilter(INTENT_WEBVIEW_INJECT_JAVASCRIPT))
    }
}