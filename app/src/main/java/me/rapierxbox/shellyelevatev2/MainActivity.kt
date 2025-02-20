package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.core.view.GestureDetectorCompat

class MainActivity : ComponentActivity() {
    private lateinit var myWebView: WebView
    private lateinit var settingsButtonOverlay1: View
    private lateinit var settingsButtonOverlay2: View
    private lateinit var swipeDetectionOverlay: View

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var swipeHelper: SwipeHelper


    private var clicksButton1: Int = 0
    private var clicksButton2: Int = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.main_activity)

        sharedPreferences = getSharedPreferences("ShellyElevateV2", MODE_PRIVATE)
        var ip = sharedPreferences.getString("homeAssistantIp", "192.168.4.188")
        var url = "http://$ip:8123"

        swipeHelper = SwipeHelper {
            if (sharedPreferences.getBoolean("switchOnSwipe", true)) {
                DeviceHelper.setRelay(!DeviceHelper.getRelay())
            }
        }

        myWebView = findViewById(R.id.myWebView)
        configureWebView()
        myWebView.loadUrl(url)

        settingsButtonOverlay1 = findViewById(R.id.settingButtonOverlay1)
        settingsButtonOverlay1.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                clicksButton1++
            }
            return@setOnTouchListener false
        }

        settingsButtonOverlay2 = findViewById(R.id.settingButtonOverlay2)
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
                    ip = sharedPreferences.getString("homeAssistantIp", "")
                    url = "http://$ip:8123"
                    myWebView.loadUrl(url)

                    clicksButton1 = 0
                    clicksButton2 = 0
                }
            }
            return@setOnTouchListener false
        }

        swipeDetectionOverlay = findViewById(R.id.swipeDetectionOverlay)
        swipeDetectionOverlay.setOnTouchListener { _, event ->
            if (swipeHelper.onTouchEvent(event)) {
                myWebView.onTouchEvent(event)
            }
            return@setOnTouchListener true
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

    }
}
