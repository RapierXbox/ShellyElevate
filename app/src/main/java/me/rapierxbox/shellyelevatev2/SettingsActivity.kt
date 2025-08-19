package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.slider.Slider
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME
import me.rapierxbox.shellyelevatev2.Constants.SP_AUTOMATIC_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.Constants.SP_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.Constants.SP_DEVICE
import me.rapierxbox.shellyelevatev2.Constants.SP_EXTENDED_JAVASCRIPT_INTERFACE
import me.rapierxbox.shellyelevatev2.Constants.SP_HTTP_SERVER_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_IGNORE_SSL_ERRORS
import me.rapierxbox.shellyelevatev2.Constants.SP_LITE_MODE
import me.rapierxbox.shellyelevatev2.Constants.SP_MIN_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_BROKER
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_PASSWORD
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_PORT
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_USERNAME
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_DELAY
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_ID
import me.rapierxbox.shellyelevatev2.Constants.SP_SWITCH_ON_SWIPE
import me.rapierxbox.shellyelevatev2.Constants.SP_WAKE_ON_PROXIMITY
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_URL
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mHttpServer
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwipeHelper
import me.rapierxbox.shellyelevatev2.databinding.SettingsActivityBinding
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper
import java.io.IOException
import java.net.NetworkInterface

@SuppressLint("UseSwitchCompatOrMaterialCode")
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: SettingsActivityBinding // Declare the binding object

    private fun loadValues() {

        val device = DeviceModel.getDevice(mSharedPreferences)

        for (i in 0 until binding.deviceTypeSpinner.adapter.count) {
            if (binding.deviceTypeSpinner.adapter.getItem(i) == device) {
                binding.deviceTypeSpinner.setSelection(i)
            }
        }

        binding.webviewURL.setText(ServiceHelper.getWebviewUrl())
        binding.ignoreSslErrors.isChecked = mSharedPreferences.getBoolean(SP_IGNORE_SSL_ERRORS, false)
        binding.switchOnSwipe.isChecked = mSharedPreferences.getBoolean(SP_SWITCH_ON_SWIPE, true)
        binding.automaticBrightness.isChecked = mSharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true)
        binding.minBrightness.value = mSharedPreferences.getInt(SP_MIN_BRIGHTNESS, 48).toFloat()
        binding.brightnessSetting.value = mSharedPreferences.getInt(SP_BRIGHTNESS, DEFAULT_BRIGHTNESS).toFloat()
        binding.screenSaver.isChecked = mSharedPreferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true)
        binding.screenSaverDelay.setText(mSharedPreferences.getInt(SP_SCREEN_SAVER_DELAY, SCREEN_SAVER_DEFAULT_DELAY).toString())
        binding.screenSaverType.setSelection(mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0))
        binding.wakeOnProximity.isChecked = mSharedPreferences.getBoolean(SP_WAKE_ON_PROXIMITY, false)

        binding.httpServerEnabled.isChecked = mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)
        binding.httpServerAddress.text = getString(R.string.server_url, getLocalIpAddress())

        binding.httpServerStatus.text = getString(if (mHttpServer.isAlive) R.string.http_server_running else R.string.http_server_not_running)
        binding.extendedJavascriptInterface.isChecked = mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false)
        binding.liteMode.isChecked = mSharedPreferences.getBoolean(SP_LITE_MODE, false)
        binding.mqttEnabled.isChecked = mSharedPreferences.getBoolean(SP_MQTT_ENABLED, false)
        binding.mqttBroker.setText(mSharedPreferences.getString(SP_MQTT_BROKER, ""))
        binding.mqttPort.setText(mSharedPreferences.getInt(SP_MQTT_PORT, MQTT_DEFAULT_PORT).toString())
        binding.mqttUsername.setText(mSharedPreferences.getString(SP_MQTT_USERNAME, ""))
        binding.mqttPassword.setText(mSharedPreferences.getString(SP_MQTT_PASSWORD, ""))

        binding.screenSaverDelayLayout.isVisible = binding.screenSaver.isChecked
        binding.screenSaverTypeLayout.isVisible = binding.screenSaver.isChecked
        binding.wakeOnProximity.isVisible = binding.screenSaver.isChecked && device.hasProximitySensor

        binding.brightnessSettingLayout.isVisible = !binding.automaticBrightness.isChecked
        binding.minBrightnessLayout.isVisible = binding.automaticBrightness.isChecked

        binding.httpServerAddressLayout.isVisible = binding.httpServerEnabled.isChecked
        binding.httpServerLayout.isVisible = binding.httpServerEnabled.isChecked

        binding.httpServerButton.isVisible = !mHttpServer.isAlive

        binding.mqttBrokerLayout.isVisible = binding.mqttEnabled.isChecked
        binding.mqttPortLayout.isVisible = binding.mqttEnabled.isChecked
        binding.mqttUsernameLayout.isVisible = binding.mqttEnabled.isChecked
        binding.mqttPasswordLayout.isVisible = binding.mqttEnabled.isChecked

        mSharedPreferences.edit {
            putBoolean("settingEverShown", true)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = SettingsActivityBinding.inflate(layoutInflater) // Inflate the binding
        setContentView(binding.root) // Set the content view using binding.root

        supportActionBar?.let {
            it.setHomeButtonEnabled(true)
            it.setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings)
        }

        binding.screenSaverType.adapter = mScreenSaverManager.screenSaverSpinnerAdapter
        binding.deviceTypeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, DeviceModel.entries).apply{ setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        loadValues()

        binding.deviceTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                binding.wakeOnProximity.isVisible = binding.screenSaver.isChecked && (parent.adapter.getItem(position) as DeviceModel).hasProximitySensor
            }

            override fun onNothingSelected(AdapterView: AdapterView<*>?) {
            }
        }

        binding.backButton.setOnClickListener {
            saveSettings()
            finish()
        }

        binding.rebootButton.setOnClickListener {
            saveSettings()
            try {
                Runtime.getRuntime().exec("reboot")
            } catch (e: IOException) {
                Log.e("SettingsActivity", "Error rebooting:", e)
            }
        }

        binding.rebootButton.setOnClickListener {
            saveSettings()
            finishAffinity()
        }

        binding.findURLButton.setOnClickListener {
            ServiceHelper.getHAURL(applicationContext) { url: String ->
                runOnUiThread {
                    binding.webviewURL.setText(url)
                }
            }
        }

        binding.brightnessSetting.addOnChangeListener(
            Slider.OnChangeListener { _, value, _ ->
                mDeviceHelper.forceScreenBrightness(value.toInt())
            })

        binding.screenSaver.setOnCheckedChangeListener { _, isChecked ->
            binding.screenSaverDelayLayout.isVisible = isChecked
            binding.screenSaverTypeLayout.isVisible = isChecked
        }

        binding.automaticBrightness.setOnCheckedChangeListener { _, isChecked ->
            binding.brightnessSettingLayout.isVisible = !isChecked
            binding.minBrightnessLayout.isVisible = isChecked
        }

        binding.mqttEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.mqttBrokerLayout.isVisible = isChecked
            binding.mqttPortLayout.isVisible = isChecked
            binding.mqttUsernameLayout.isVisible = isChecked
            binding.mqttPasswordLayout.isVisible = isChecked
        }

        binding.httpServerEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.httpServerLayout.isVisible = isChecked
            binding.httpServerAddressLayout.isVisible = isChecked
        }

        binding.httpServerButton.setOnClickListener {
            mHttpServer.start()
            binding.httpServerText.text = getString(R.string.http_server_running)
            binding.httpServerButton.isVisible = false
        }

        binding.swipeDetectionOverlay.setOnTouchListener { v, event ->
            mSwipeHelper.onTouchEvent(event)
            mScreenSaverManager.onTouchEvent(event)

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


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        return true
    }

    private fun saveSettings() {
        getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE).edit {

            val selectedDevice = binding.deviceTypeSpinner.selectedItem as DeviceModel
            // device
            putString(SP_DEVICE, selectedDevice.boardName)

            //Functional mode
            putBoolean(SP_LITE_MODE, binding.liteMode.isChecked)

            //WebView
            putString(SP_WEBVIEW_URL, binding.webviewURL.text.toString())
            putBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, binding.extendedJavascriptInterface.isChecked)
            putBoolean(SP_IGNORE_SSL_ERRORS, binding.ignoreSslErrors.isChecked)

            //MQTT
            putBoolean(SP_MQTT_ENABLED, binding.mqttEnabled.isChecked)
            putString(SP_MQTT_BROKER, binding.mqttBroker.text.toString())
            putString(SP_MQTT_USERNAME, binding.mqttUsername.text.toString())
            putString(SP_MQTT_PASSWORD, binding.mqttPassword.text.toString())
            putInt(SP_MQTT_PORT, binding.mqttPort.text.toString().toIntOrNull() ?: MQTT_DEFAULT_PORT)

            //Switch
            putBoolean(SP_SWITCH_ON_SWIPE, binding.switchOnSwipe.isChecked)

            //Brightness management
            putBoolean(SP_AUTOMATIC_BRIGHTNESS, binding.automaticBrightness.isChecked)
            putInt(SP_BRIGHTNESS, binding.brightnessSetting.value.toInt())
            putInt(SP_MIN_BRIGHTNESS, binding.minBrightness.value.toInt())

            //Screen saver
            putBoolean(SP_SCREEN_SAVER_ENABLED, binding.screenSaver.isChecked)
            putInt(SP_SCREEN_SAVER_DELAY, binding.screenSaverDelay.text.toString().toIntOrNull() ?: SCREEN_SAVER_DEFAULT_DELAY)
            putInt(SP_SCREEN_SAVER_ID, binding.screenSaverType.selectedItemPosition)
            putBoolean(SP_WAKE_ON_PROXIMITY, binding.wakeOnProximity.isChecked && selectedDevice.hasProximitySensor)

            //Http Server
            putBoolean(SP_HTTP_SERVER_ENABLED, binding.httpServerEnabled.isChecked)
        }
        
        LocalBroadcastManager.getInstance(ShellyElevateApplication.mApplicationContext)
            .sendBroadcast(Intent(INTENT_SETTINGS_CHANGED))
        
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()

        finish()
    }

    private fun getLocalIpAddress() = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { it.isSiteLocalAddress }?.hostAddress

    companion object {
        const val SCREEN_SAVER_DEFAULT_DELAY = 45
        const val MQTT_DEFAULT_PORT = 1833
        const val DEFAULT_BRIGHTNESS = 255
    }
}