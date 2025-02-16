package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.GestureDetectorCompat
import me.rapierxbox.shellyelevatev2.ui.theme.ShellyElevateV2Theme

class MainActivity : ComponentActivity() {
    private lateinit var myWebView: WebView
    private lateinit var settingsButtonOverlay1: View
    private lateinit var settingsButtonOverlay2: View

    private lateinit var sharedPreferences: SharedPreferences

    private var clicksButton1: Int = 0
    private var clicksButton2: Int = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.main_activity)

        sharedPreferences = getSharedPreferences("ShellyElevateV2", MODE_PRIVATE)
        var ip = sharedPreferences.getString("homeAssistantIp", "")
        var url = "http://$ip:8123"


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

    }
}
