package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import me.rapierxbox.shellyelevatev2.Constants.INTENT_WEBVIEW_REFRESH
import me.rapierxbox.shellyelevatev2.Constants.SP_AUTOMATIC_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.Constants.SP_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.Constants.SP_EXTENDED_JAVASCRIPT_INTERFACE
import me.rapierxbox.shellyelevatev2.Constants.SP_HTTP_SERVER_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_LITE_MODE
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_BROKER
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_PASSWORD
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_PORT
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_USERNAME
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_DELAY
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_ID
import me.rapierxbox.shellyelevatev2.Constants.SP_SWITCH_ON_SWIPE
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_URL
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mHttpServer
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwipeHelper
import me.rapierxbox.shellyelevatev2.databinding.SettingsActivityBinding
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper

@SuppressLint("UseSwitchCompatOrMaterialCode")
class SettingsActivity : Activity() {

    private lateinit var binding: SettingsActivityBinding // Declare the binding object

    private fun loadValues() {
        binding.webviewURL.setText(ServiceHelper.getWebviewUrl())
        binding.switchOnSwipe.isChecked = mSharedPreferences.getBoolean(SP_SWITCH_ON_SWIPE, true)
        binding.automaticBrightness.isChecked = mSharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true)
        binding.brightnessSetting.progress = mSharedPreferences.getInt(SP_BRIGHTNESS, DEFAULT_BRIGHTNESS)
        binding.screenSaver.isChecked = mSharedPreferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true)
        binding.screenSaverDelay.setText(mSharedPreferences.getInt(SP_SCREEN_SAVER_DELAY, SCREEN_SAVER_DEFAULT_DELAY).toString())
        binding.screenSaverType.setSelection(mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0))
        binding.httpServerEnabled.isChecked = mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)
        binding.httpServerText.text = getString(if (mHttpServer.isAlive) R.string.http_server_running else R.string.http_server_not_running)
        binding.extendedJavascriptInterface.isChecked = mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false)
        binding.liteMode.isChecked = mSharedPreferences.getBoolean(SP_LITE_MODE, false)
        binding.mqttEnabled.isChecked = mSharedPreferences.getBoolean(SP_MQTT_ENABLED, false)
        binding.mqttBroker.setText(mSharedPreferences.getString(SP_MQTT_BROKER, ""))
        binding.mqttPort.setText(mSharedPreferences.getInt(SP_MQTT_PORT, MQTT_DEFAULT_PORT).toString())
        binding.mqttUsername.setText(mSharedPreferences.getString(SP_MQTT_USERNAME, ""))
        binding.mqttPassword.setText(mSharedPreferences.getString(SP_MQTT_PASSWORD, ""))

        binding.screenSaverDelayLayout.isVisible = binding.screenSaver.isChecked
        binding.screenSaverTypeLayout.isVisible = binding.screenSaver.isChecked
        binding.brightnessSettingLayout.isVisible = !binding.automaticBrightness.isChecked
        binding.httpServerLayout.isVisible = binding.screenSaver.isChecked
        binding.httpServerButton.isVisible = !mHttpServer.isAlive
        binding.mqttBrokerLayout.isVisible = binding.mqttEnabled.isChecked
        binding.mqttPortLayout.isVisible = binding.mqttEnabled.isChecked
        binding.mqttUsernameLayout.isVisible = binding.mqttEnabled.isChecked
        binding.mqttPasswordLayout.isVisible = binding.mqttEnabled.isChecked

    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = SettingsActivityBinding.inflate(layoutInflater) // Inflate the binding
        setContentView(binding.root) // Set the content view using binding.root

        binding.screenSaverType.adapter = mScreenSaverManager.screenSaverSpinnerAdapter
        loadValues()

        binding.findURLButton.setOnClickListener {
            ServiceHelper.getHAURL(applicationContext) { url: String ->
                runOnUiThread {
                    binding.webviewURL.setText(url)
                }
            }
        }

        binding.brightnessSetting.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar ?: return

                mDeviceHelper.forceScreenBrightness(seekBar.progress)
            }
        })

        binding.screenSaver.setOnCheckedChangeListener { _, isChecked ->
            binding.screenSaverDelayLayout.isVisible = isChecked
            binding.screenSaverTypeLayout.isVisible = isChecked
        }

        binding.automaticBrightness.setOnCheckedChangeListener { _, isChecked ->
            binding.brightnessSettingLayout.isVisible = !isChecked
        }

        binding.mqttEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.mqttBrokerLayout.isVisible = isChecked
            binding.mqttPortLayout.isVisible = isChecked
            binding.mqttUsernameLayout.isVisible = isChecked
            binding.mqttPasswordLayout.isVisible = isChecked
        }

        binding.httpServerEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.httpServerLayout.isVisible = isChecked
        }

        binding.httpServerButton.setOnClickListener {
            mHttpServer.start()
            binding.httpServerText.text = getString(R.string.http_server_running)
            binding.httpServerButton.isVisible = false
        }

        binding.backButton.setOnClickListener {
            mSharedPreferences.edit {
                putString(SP_WEBVIEW_URL, binding.webviewURL.text.toString())
                putString(SP_MQTT_BROKER, binding.mqttBroker.text.toString())
                putString(SP_MQTT_USERNAME, binding.mqttUsername.text.toString())
                putString(SP_MQTT_PASSWORD, binding.mqttPassword.text.toString())
                putBoolean(SP_SWITCH_ON_SWIPE, binding.switchOnSwipe.isChecked)
                putBoolean(SP_AUTOMATIC_BRIGHTNESS, binding.automaticBrightness.isChecked)
                putBoolean(SP_SCREEN_SAVER_ENABLED, binding.screenSaver.isChecked)
                putBoolean(SP_HTTP_SERVER_ENABLED, binding.httpServerEnabled.isChecked)
                putBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, binding.extendedJavascriptInterface.isChecked)
                putBoolean(SP_LITE_MODE, binding.liteMode.isChecked)
                putBoolean(SP_MQTT_ENABLED, binding.mqttEnabled.isChecked)
                putInt(SP_SCREEN_SAVER_DELAY, binding.screenSaverDelay.text.toString().toIntOrNull() ?: SCREEN_SAVER_DEFAULT_DELAY)
                putInt(SP_SCREEN_SAVER_ID, binding.screenSaverType.selectedItemPosition)
                putInt(SP_BRIGHTNESS, binding.brightnessSetting.progress)
                putInt(SP_MQTT_PORT, binding.mqttPort.text.toString().toIntOrNull() ?: MQTT_DEFAULT_PORT)
            }

            val serverEnabled = binding.httpServerEnabled.isChecked

            if (!serverEnabled && mHttpServer.isAlive) {
                mHttpServer.stop()
            } else if (serverEnabled && !mHttpServer.isAlive) {
                mHttpServer.start()
            }

            ShellyElevateApplication.updateSPValues()
            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()

            val intent = Intent(INTENT_WEBVIEW_REFRESH)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

            finish()
        }

        binding.swipeDetectionOverlay.setOnTouchListener { _, event ->
            if (mScreenSaverManager.onTouchEvent()) {
                Log.d("ShellyElevateV2", "Touch blocked by ScreenSaverManager")
                return@setOnTouchListener true
            }
            mSwipeHelper.onTouchEvent(event)

            return@setOnTouchListener false
        }

        binding.screenSaverDelay.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {

                if ((binding.screenSaverDelay.text.toString().toIntOrNull() ?: 5) < 5) {
                    binding.screenSaverDelay.setText("5")
                    Toast.makeText(this, R.string.delay_must_be_bigger_then_5s, Toast.LENGTH_SHORT).show()
                }
            }

            return@setOnEditorActionListener false
        }
    }

    companion object {
        const val SCREEN_SAVER_DEFAULT_DELAY = 45
        const val MQTT_DEFAULT_PORT = 1833
        const val DEFAULT_BRIGHTNESS = 255
    }
}