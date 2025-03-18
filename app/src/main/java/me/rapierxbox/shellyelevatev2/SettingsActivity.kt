package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import me.rapierxbox.shellyelevatev2.Constants.*
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mHttpServer
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwipeHelper
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper

@SuppressLint("UseSwitchCompatOrMaterialCode")
class SettingsActivity: Activity() {
    private lateinit var findURLButton: Button
    private lateinit var backButton: Button
    private lateinit var httpServerButton: Button
    private lateinit var switchOnSwipeSwitch: Switch
    private lateinit var automaticBrightnessSwitch: Switch
    private lateinit var screenSaverSwitch: Switch
    private lateinit var httpServerSwitch: Switch
    private lateinit var extendedJSInterfaceSwitch: Switch
    private lateinit var liteModeSwitch: Switch
    private lateinit var mqttEnabledSwitch: Switch
    private lateinit var urlEditText: EditText
    private lateinit var screenSaverDelayEditText: EditText
    private lateinit var mqttBrokerEditText: EditText
    private lateinit var mqttPortEditText: EditText
    private lateinit var mqttUsernameEditText: EditText
    private lateinit var mqttPasswordEditText: EditText
    private lateinit var httpServerText: TextView
    private lateinit var screenSaverTypeSpinner: Spinner
    private lateinit var swipeDetectionOverlayView: View
    private lateinit var screenSaverTypeLayout: LinearLayout
    private lateinit var screenSaverDelayLayout: LinearLayout
    private lateinit var httpServerLayout: LinearLayout
    private lateinit var brightnessSettingLayout: LinearLayout
    private lateinit var mqttBrokerLayout: LinearLayout
    private lateinit var mqttPortLayout: LinearLayout
    private lateinit var mqttUsernameLayout: LinearLayout
    private lateinit var mqttPasswordLayout: LinearLayout
    private lateinit var brightnessSetting: SeekBar

    private fun findViews() {
        urlEditText = findViewById(R.id.webviewURL)
        findURLButton = findViewById(R.id.findURLButton)
        switchOnSwipeSwitch = findViewById(R.id.switchOnSwipe)
        automaticBrightnessSwitch = findViewById(R.id.automaticBrightness)
        screenSaverSwitch = findViewById(R.id.screenSaver)
        screenSaverDelayLayout = findViewById(R.id.screenSaverDelayLayout)
        screenSaverDelayEditText = findViewById(R.id.screenSaverDelay)
        screenSaverTypeLayout = findViewById(R.id.screenSaverTypeLayout)
        screenSaverTypeSpinner = findViewById(R.id.screenSaverType)
        httpServerSwitch = findViewById(R.id.httpServerEnabled)
        extendedJSInterfaceSwitch = findViewById(R.id.extendedJavascriptInterface)
        httpServerLayout = findViewById(R.id.httpServerLayout)
        httpServerText = findViewById(R.id.httpServerText)
        httpServerButton = findViewById(R.id.httpServerButton)
        liteModeSwitch = findViewById(R.id.liteMode)
        backButton = findViewById(R.id.backButton)
        swipeDetectionOverlayView = findViewById(R.id.swipeDetectionOverlay)
        brightnessSettingLayout = findViewById(R.id.brightnessSettingLayout)
        brightnessSetting = findViewById(R.id.brightnessSetting)

        mqttEnabledSwitch = findViewById(R.id.mqttEnabled)
        mqttBrokerLayout = findViewById(R.id.mqttBrokerLayout)
        mqttBrokerEditText = findViewById(R.id.mqttBroker)
        mqttPortLayout = findViewById(R.id.mqttPortLayout)
        mqttPortEditText = findViewById(R.id.mqttPort)
        mqttUsernameLayout = findViewById(R.id.mqttUsernameLayout)
        mqttUsernameEditText = findViewById(R.id.mqttUsername)
        mqttPasswordLayout = findViewById(R.id.mqttPasswordLayout)
        mqttPasswordEditText = findViewById(R.id.mqttPassword)
    }

    private fun loadValues() {
        urlEditText.setText(ServiceHelper.getWebviewUrl())
        switchOnSwipeSwitch.isChecked = mSharedPreferences.getBoolean(SP_SWITCH_ON_SWIPE, true)
        automaticBrightnessSwitch.isChecked = mSharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true)
        brightnessSetting.progress = mSharedPreferences.getInt(SP_BRIGHTNESS, 255)
        screenSaverSwitch.isChecked = mSharedPreferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true)
        screenSaverDelayEditText.setText(mSharedPreferences.getInt(SP_SCREEN_SAVER_DELAY, 45).toString())
        screenSaverTypeSpinner.setSelection(mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0))
        httpServerSwitch.isChecked = mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)
        httpServerText.text = if (mHttpServer.isAlive) "HTTP Server: Running" else "HTTP Server: Not running"
        extendedJSInterfaceSwitch.isChecked = mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false)
        liteModeSwitch.isChecked = mSharedPreferences.getBoolean(SP_LITE_MODE, false)
        mqttEnabledSwitch.isChecked = mSharedPreferences.getBoolean(SP_MQTT_ENABLED, false)
        mqttBrokerEditText.setText(mSharedPreferences.getString(SP_MQTT_BROKER, ""))
        mqttPortEditText.setText(mSharedPreferences.getInt(SP_MQTT_PORT, 1883).toString())
        mqttUsernameEditText.setText(mSharedPreferences.getString(SP_MQTT_USERNAME, ""))
        mqttPasswordEditText.setText(mSharedPreferences.getString(SP_MQTT_PASSWORD, ""))


        screenSaverDelayLayout.visibility = if (screenSaverSwitch.isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
        screenSaverTypeLayout.visibility = if (screenSaverSwitch.isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
        brightnessSettingLayout.visibility = if (automaticBrightnessSwitch.isChecked) LinearLayout.GONE else LinearLayout.VISIBLE
        httpServerLayout.visibility = if (screenSaverSwitch.isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
        httpServerButton.visibility = if (mHttpServer.isAlive) Button.GONE else Button.VISIBLE
        mqttBrokerLayout.visibility = if (mqttEnabledSwitch.isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
        mqttPortLayout.visibility = if (mqttEnabledSwitch.isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
        mqttUsernameLayout.visibility = if (mqttEnabledSwitch.isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
        mqttPasswordLayout.visibility = if (mqttEnabledSwitch.isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        findViews()
        screenSaverTypeSpinner.adapter = mScreenSaverManager.screenSaverSpinnerAdapter
        loadValues()

        findURLButton.setOnClickListener {
            ServiceHelper.getHAURL(applicationContext) { url: String ->
                runOnUiThread {
                    urlEditText.setText(url)
                }
            }
        }

        brightnessSetting.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (seekBar != null) {
                    mDeviceHelper.forceScreenBrightness(seekBar.progress)
                }
            }
        })

        screenSaverSwitch.setOnCheckedChangeListener { _, isChecked ->
            screenSaverDelayLayout.visibility = if (isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
            screenSaverTypeLayout.visibility = if (isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
        }

        automaticBrightnessSwitch.setOnCheckedChangeListener { _, isChecked ->
            brightnessSettingLayout.visibility = if (isChecked) LinearLayout.GONE else LinearLayout.VISIBLE
        }

        mqttEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            mqttBrokerLayout.visibility = if (isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
            mqttPortLayout.visibility = if (isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
            mqttUsernameLayout.visibility = if (isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
            mqttPasswordLayout.visibility = if (isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
        }

        httpServerSwitch.setOnCheckedChangeListener { _, isChecked ->
            httpServerLayout.visibility = if (isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
        }

        httpServerButton.setOnClickListener {
            mHttpServer.start()
            httpServerText.text = "HTTP Server: Running"
            httpServerButton.visibility = Button.GONE
        }

        backButton.setOnClickListener {
            mSharedPreferences.edit()
                .putString(SP_WEBVIEW_URL, urlEditText.text.toString())
                .putString(SP_MQTT_BROKER, mqttBrokerEditText.text.toString())
                .putString(SP_MQTT_USERNAME, mqttUsernameEditText.text.toString())
                .putString(SP_MQTT_PASSWORD, mqttPasswordEditText.text.toString())
                .putBoolean(SP_SWITCH_ON_SWIPE, switchOnSwipeSwitch.isChecked)
                .putBoolean(SP_AUTOMATIC_BRIGHTNESS, automaticBrightnessSwitch.isChecked)
                .putBoolean(SP_SCREEN_SAVER_ENABLED, screenSaverSwitch.isChecked)
                .putBoolean(SP_HTTP_SERVER_ENABLED, httpServerSwitch.isChecked)
                .putBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, extendedJSInterfaceSwitch.isChecked)
                .putBoolean(SP_LITE_MODE, liteModeSwitch.isChecked)
                .putBoolean(SP_MQTT_ENABLED, mqttEnabledSwitch.isChecked)
                .putInt(SP_SCREEN_SAVER_DELAY, Integer.parseInt(screenSaverDelayEditText.text.toString()))
                .putInt(SP_SCREEN_SAVER_ID, screenSaverTypeSpinner.selectedItemPosition)
                .putInt(SP_BRIGHTNESS, brightnessSetting.progress)
                .putInt(SP_MQTT_PORT, Integer.parseInt(mqttPortEditText.text.toString()))
                .apply()

            if (!httpServerSwitch.isChecked && mHttpServer.isAlive) {
                mHttpServer.stop()
            } else if (httpServerSwitch.isChecked && !mHttpServer.isAlive) {
                mHttpServer.start()
            }

            ShellyElevateApplication.updateSPValues()
            Toast.makeText(mApplicationContext, "Settings saved", Toast.LENGTH_SHORT).show()

            val intent = Intent(INTENT_WEBVIEW_REFRESH)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

            finish()
        }

        swipeDetectionOverlayView.setOnTouchListener { _, event ->
            if (mScreenSaverManager.onTouchEvent()) {
                Log.d("ShellyElevateV2", "Touch blocked by ScreenSaverManager")
                return@setOnTouchListener true
            }
            mSwipeHelper.onTouchEvent(event)

            return@setOnTouchListener false
        }

        screenSaverDelayEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (Integer.parseInt(screenSaverDelayEditText.text.toString()) < 5) {
                    screenSaverDelayEditText.setText("5")
                    Toast.makeText(mApplicationContext, "Delay must be bigger then 5s", Toast.LENGTH_SHORT).show()
                }
            }

            return@setOnEditorActionListener false
        }
    }
}