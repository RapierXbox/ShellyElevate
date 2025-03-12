package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import me.rapierxbox.shellyelevatev2.Constants.*
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mHttpServer
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper

@SuppressLint("UseSwitchCompatOrMaterialCode")
class SettingsActivity: Activity() {
    private lateinit var findURLButton: Button
    private lateinit var backButton: Button
    private lateinit var httpServerButton: Button
    private lateinit var switchOnSwipeSwitch: Switch
    private lateinit var automaticBrightnessSwitch: Switch
    private lateinit var screenSaverSwitch: Switch
    private lateinit var liteModeSwitch: Switch
    private lateinit var urlEditText: EditText
    private lateinit var screenSaverDelayEditText: EditText
    private lateinit var httpServerText: TextView
    private lateinit var screenSaverTypeSpinner: Spinner
    private lateinit var swipeDetectionOverlayView: View

    private fun findViews() {
        urlEditText = findViewById(R.id.webviewURL)
        findURLButton = findViewById(R.id.findURLButton)
        switchOnSwipeSwitch = findViewById(R.id.switchOnSwipe)
        automaticBrightnessSwitch = findViewById(R.id.automaticBrightness)
        screenSaverSwitch = findViewById(R.id.screenSaver)
        screenSaverDelayEditText = findViewById(R.id.screenSaverDelay)
        screenSaverTypeSpinner = findViewById(R.id.screenSaverType)
        httpServerText = findViewById(R.id.httpServerText)
        httpServerButton = findViewById(R.id.httpServerButton)
        liteModeSwitch = findViewById(R.id.liteMode)
        backButton = findViewById(R.id.backButton)
        swipeDetectionOverlayView = findViewById(R.id.swipeDetectionOverlay)
    }

    private fun loadValues() {
        urlEditText.setText(ServiceHelper.getWebviewUrl())
        switchOnSwipeSwitch.isChecked = mSharedPreferences.getBoolean(SP_SWITCH_ON_SWIPE, true)
        automaticBrightnessSwitch.isChecked = mSharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true)
        screenSaverSwitch.isChecked = mSharedPreferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true)
        screenSaverDelayEditText.setText(mSharedPreferences.getInt(SP_SCREEN_SAVER_DELAY, 45).toString())
        screenSaverTypeSpinner.setSelection(mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0))
        httpServerText.text = if (mHttpServer.isAlive) "HTTP Server: Running" else "HTTP Server: Not running"
        httpServerButton.visibility = if (mHttpServer.isAlive) Button.GONE else Button.VISIBLE
        liteModeSwitch.isChecked = mSharedPreferences.getBoolean(SP_LITE_MODE, true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        findViews()

        loadValues()

        findURLButton.setOnClickListener {
            ServiceHelper.getHAURL(applicationContext) { url: String ->
                runOnUiThread {
                    urlEditText.setText(url)
                }
            }
        }

        screenSaverTypeSpinner.adapter = ShellyElevateApplication.mScreenSaverManager.screenSaverSpinnerAdapter

        httpServerButton.setOnClickListener {
            mHttpServer.start()
            httpServerText.text = "HTTP Server: Running"
            httpServerButton.visibility = Button.GONE
        }

        backButton.setOnClickListener {
            mSharedPreferences.edit()
                .putString(SP_WEBVIEW_URL, urlEditText.text.toString())
                .putBoolean(SP_SWITCH_ON_SWIPE, switchOnSwipeSwitch.isChecked)
                .putBoolean(SP_AUTOMATIC_BRIGHTNESS, automaticBrightnessSwitch.isChecked)
                .putBoolean(SP_SCREEN_SAVER_ENABLED, screenSaverSwitch.isChecked)
                .putBoolean(SP_LITE_MODE, liteModeSwitch.isChecked)
                .putInt(SP_SCREEN_SAVER_DELAY, Integer.parseInt(screenSaverDelayEditText.text.toString()))
                .putInt(SP_SCREEN_SAVER_ID, screenSaverTypeSpinner.selectedItemPosition)
                .apply()
            finish()
        }

        swipeDetectionOverlayView.setOnTouchListener { _, event ->
            ShellyElevateApplication.mScreenSaverManager.onTouchEvent()
            ShellyElevateApplication.mSwipeHelper.onTouchEvent(event)

            return@setOnTouchListener false
        }

        screenSaverDelayEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (Integer.parseInt(screenSaverDelayEditText.text.toString()) < 5) {
                    screenSaverDelayEditText.setText("5")
                    Toast.makeText(mApplicationContext, "Delay must be over 5s", Toast.LENGTH_SHORT).show()
                }
            }

            return@setOnEditorActionListener false
        }
    }
}