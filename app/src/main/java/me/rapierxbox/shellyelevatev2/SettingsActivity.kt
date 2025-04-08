package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.google.android.material.slider.Slider
import me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME
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
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwipeHelper
import me.rapierxbox.shellyelevatev2.backbutton.BackAccessibilityService
import me.rapierxbox.shellyelevatev2.backbutton.FloatingBackButtonService
import me.rapierxbox.shellyelevatev2.databinding.SettingsActivityBinding
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManagerHolder
import java.net.NetworkInterface

@SuppressLint("UseSwitchCompatOrMaterialCode")
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: SettingsActivityBinding // Declare the binding object

    private fun loadValues() {

        val preferences = getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE)

        binding.webviewURL.setText(ServiceHelper.getWebviewUrl())
        binding.switchOnSwipe.isChecked = preferences.getBoolean(SP_SWITCH_ON_SWIPE, true)
        binding.automaticBrightness.isChecked = preferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true)
        binding.brightnessSetting.value = preferences.getInt(SP_BRIGHTNESS, DEFAULT_BRIGHTNESS).toFloat()
        binding.screenSaver.isChecked = preferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true)
        binding.screenSaverDelay.setText(preferences.getInt(SP_SCREEN_SAVER_DELAY, SCREEN_SAVER_DEFAULT_DELAY).toString())
        binding.screenSaverType.setSelection(preferences.getInt(SP_SCREEN_SAVER_ID, 0))

        binding.httpServerEnabled.isChecked = preferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)
        binding.httpServerAddress.text = getString(R.string.server_url, getLocalIpAddress())

        binding.httpServerStatus.text = getString(if (mHttpServer.isAlive) R.string.http_server_running else R.string.http_server_not_running)
        binding.extendedJavascriptInterface.isChecked = preferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false)
        binding.liteMode.isChecked = preferences.getBoolean(SP_LITE_MODE, false)
        binding.mqttEnabled.isChecked = preferences.getBoolean(SP_MQTT_ENABLED, false)
        binding.mqttBroker.setText(preferences.getString(SP_MQTT_BROKER, ""))
        binding.mqttPort.setText(preferences.getInt(SP_MQTT_PORT, MQTT_DEFAULT_PORT).toString())
        binding.mqttUsername.setText(preferences.getString(SP_MQTT_USERNAME, ""))
        binding.mqttPassword.setText(preferences.getString(SP_MQTT_PASSWORD, ""))

        binding.screenSaverDelayLayout.isVisible = binding.screenSaver.isChecked
        binding.screenSaverTypeLayout.isVisible = binding.screenSaver.isChecked

        binding.brightnessSettingLayout.isVisible = !binding.automaticBrightness.isChecked

        binding.httpServerAddressLayout.isVisible = binding.httpServerEnabled.isChecked
        binding.httpServerLayout.isVisible = binding.httpServerEnabled.isChecked

        binding.httpServerButton.isVisible = !mHttpServer.isAlive

        binding.mqttBrokerLayout.isVisible = binding.mqttEnabled.isChecked
        binding.mqttPortLayout.isVisible = binding.mqttEnabled.isChecked
        binding.mqttUsernameLayout.isVisible = binding.mqttEnabled.isChecked
        binding.mqttPasswordLayout.isVisible = binding.mqttEnabled.isChecked

        preferences.edit {
            putBoolean("settingEverShown", true)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = SettingsActivityBinding.inflate(layoutInflater) // Inflate the binding
        setContentView(binding.root) // Set the content view using binding.root

        setSupportActionBar(binding.toolbar)

        supportActionBar?.let {
            it.setHomeButtonEnabled(true)
            it.setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings)
        }

        binding.screenSaverType.adapter = ScreenSaverManagerHolder.getInstance().screenSaverSpinnerAdapter

        loadValues()

        binding.findURLButton.setOnClickListener {
            ServiceHelper.getHAURL(applicationContext) { url: String ->
                runOnUiThread {
                    binding.webviewURL.setText(url)
                }
            }
        }

        binding.brightnessSetting.addOnChangeListener(object : Slider.OnChangeListener {

            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                mDeviceHelper.forceScreenBrightness(value.toInt())
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
            binding.httpServerAddressLayout.isVisible = isChecked
        }

        binding.httpServerButton.setOnClickListener {
            mHttpServer.start()
            binding.httpServerText.text = getString(R.string.http_server_running)
            binding.httpServerButton.isVisible = false
        }

        binding.swipeDetectionOverlay.setOnTouchListener { _, event ->
            if (ScreenSaverManagerHolder.getInstance().onTouchEvent()) {
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

    override fun onSupportNavigateUp(): Boolean {
        saveSettings()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {

                if (checkAccessibilityPermission()) {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    startActivity(intent)
                }
                true
            }

            R.id.action_exit -> {
                if (checkAccessibilityPermission()) {
                    moveTaskToBack(true)
                    finishAffinity()
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please, grant overlay permission to show the floating back button", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            startActivity(intent)
            return false
        }

        startService(Intent(this, FloatingBackButtonService::class.java))

        if (!BackAccessibilityService.isAccessibilityEnabled(this)) {
            Toast.makeText(this, "Please, grant accessibility permission to use the floating back button", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return false
        }

        return true
    }

    private fun saveSettings() {
        getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE).edit {
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
            putInt(SP_BRIGHTNESS, binding.brightnessSetting.value.toInt())
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

        finish()
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, FloatingBackButtonService::class.java)
        intent.action = FloatingBackButtonService.HIDE_FLOATING_BUTTON
        startService(intent)
    }

    fun getLocalIpAddress() = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { it.isSiteLocalAddress }?.hostAddress

    companion object {
        const val SCREEN_SAVER_DEFAULT_DELAY = 45
        const val MQTT_DEFAULT_PORT = 1833
        const val DEFAULT_BRIGHTNESS = 255
    }
}