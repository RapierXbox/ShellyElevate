package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_MIN_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.Constants.SP_SWITCH_ON_SWIPE
import me.rapierxbox.shellyelevatev2.Constants.SP_WAKE_ON_PROXIMITY
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_URL
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mHttpServer
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwipeHelper
import me.rapierxbox.shellyelevatev2.databinding.SettingsFragmentBinding
import me.rapierxbox.shellyelevatev2.helper.ScreenManager.DEFAULT_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.helper.ScreenManager.MIN_BRIGHTNESS_DEFAULT
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManager
import java.io.IOException
import java.net.NetworkInterface

class SettingsFragment : Fragment() {

    private var _binding: SettingsFragmentBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SettingsFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        activity?.setTitle(R.string.settings)

        activity?.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.settings_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_settings -> {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                        true
                    }

                    R.id.action_restart -> {
                        saveSettings()
                        try {
                            Runtime.getRuntime().exec("reboot")
                        } catch (e: IOException) {
                            Log.e("SettingsActivity", "Error rebooting:", e)
                        }
                        true
                    }

                    R.id.action_exit -> {
                        requireActivity().moveTaskToBack(true)
                        requireActivity().finishAffinity()
                        true
                    }

                    else -> false
                }
            }
        }, viewLifecycleOwner)

        binding.deviceTypeSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, DeviceModel.entries
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.screenSaverType.adapter = getScreenSaverSpinnerAdapter()
        loadValues()
        setupListeners()
    }

    private fun loadValues() {
        //device
        val device = DeviceModel.getDevice(mSharedPreferences)

        for (i in 0 until binding.deviceTypeSpinner.adapter.count) {
            if (binding.deviceTypeSpinner.adapter.getItem(i) == device) {
                binding.deviceTypeSpinner.setSelection(i)
            }
        }
        //Functional mode
        binding.liteMode.isChecked = mSharedPreferences.getBoolean(SP_LITE_MODE, false)

        //WebView
        binding.webviewURL.setText(ServiceHelper.getWebviewUrl())
        binding.ignoreSslErrors.isChecked = mSharedPreferences.getBoolean(SP_IGNORE_SSL_ERRORS, false)
        binding.extendedJavascriptInterface.isChecked = mSharedPreferences.getBoolean(SP_EXTENDED_JAVASCRIPT_INTERFACE, false)

        //MQTT
        binding.mqttEnabled.isChecked = mSharedPreferences.getBoolean(SP_MQTT_ENABLED, false)
        binding.mqttBroker.setText(mSharedPreferences.getString(SP_MQTT_BROKER, ""))
        binding.mqttPort.setText(mSharedPreferences.getInt(SP_MQTT_PORT, MQTT_DEFAULT_PORT).toString())
        binding.mqttUsername.setText(mSharedPreferences.getString(SP_MQTT_USERNAME, ""))
        binding.mqttPassword.setText(mSharedPreferences.getString(SP_MQTT_PASSWORD, ""))

        //Switch
        binding.switchOnSwipe.isChecked = mSharedPreferences.getBoolean(SP_SWITCH_ON_SWIPE, true)

        //Brightness management
        binding.automaticBrightness.isChecked = mSharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true)
        binding.brightnessSetting.value = mSharedPreferences.getInt(SP_BRIGHTNESS, DEFAULT_BRIGHTNESS).toFloat()
        binding.minBrightness.value = mSharedPreferences.getInt(SP_MIN_BRIGHTNESS, 48).toFloat()

        //Screen saver
        binding.screenSaver.isChecked = mSharedPreferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true)
        binding.screenSaverDelay.setText(mSharedPreferences.getInt(SP_SCREEN_SAVER_DELAY, SCREEN_SAVER_DEFAULT_DELAY).toString())
        binding.screenSaverType.setSelection(mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0))
        binding.wakeOnProximity.isChecked = mSharedPreferences.getBoolean(SP_WAKE_ON_PROXIMITY, false)
        binding.screensaverMinBrightness.value = mSharedPreferences.getInt(SP_SCREEN_SAVER_MIN_BRIGHTNESS, MIN_BRIGHTNESS_DEFAULT).toFloat()

        //Http Server
        binding.httpServerEnabled.isChecked = mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)
        binding.httpServerAddress.text = getString(R.string.server_url, getLocalIpAddress())
        binding.httpServerStatus.text = getString(if (mHttpServer.isAlive) R.string.http_server_running else R.string.http_server_not_running)

        //Update Visibility
        //ScreenSaver
        binding.screenSaverDelayLayout.isVisible = binding.screenSaver.isChecked
        binding.screenSaverTypeLayout.isVisible = binding.screenSaver.isChecked
        binding.wakeOnProximity.isVisible = binding.screenSaver.isChecked && device.hasProximitySensor

        //Brightness management
        binding.brightnessSettingLayout.isVisible = !binding.automaticBrightness.isChecked
        binding.minBrightnessLayout.isVisible = binding.automaticBrightness.isChecked

        //Http Server
        binding.httpServerAddressLayout.isVisible = binding.httpServerEnabled.isChecked
        binding.httpServerLayout.isVisible = binding.httpServerEnabled.isChecked
        binding.httpServerButton.isVisible = !mHttpServer.isAlive

        //MQTT
        binding.mqttBrokerLayout.isVisible = binding.mqttEnabled.isChecked
        binding.mqttPortLayout.isVisible = binding.mqttEnabled.isChecked
        binding.mqttUsernameLayout.isVisible = binding.mqttEnabled.isChecked
        binding.mqttPasswordLayout.isVisible = binding.mqttEnabled.isChecked

        mSharedPreferences.edit { putBoolean("settingEverShown", true) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        binding.findURLButton.setOnClickListener {
            ServiceHelper.getHAURL(requireContext().applicationContext) { url ->
                requireActivity().runOnUiThread { binding.webviewURL.setText(url) }
            }
        }

        binding.brightnessSetting.addOnChangeListener { slider, value, fromUser ->
            mDeviceHelper.setScreenBrightness(value.toInt())
        }

        binding.automaticBrightness.setOnCheckedChangeListener { _, isChecked ->
            binding.brightnessSettingLayout.isVisible = !isChecked
            binding.minBrightnessLayout.isVisible = isChecked
        }

        binding.minBrightness.addOnChangeListener { slider, value, fromUser ->
            //Give user a feedback about the configured min brightness
            mDeviceHelper.setScreenBrightness(value.toInt())
        }

        binding.screensaverMinBrightness.addOnChangeListener { slider, value, fromUser ->
            //Give user a feedback about the configured min brightness
            mDeviceHelper.setScreenBrightness(value.toInt())
        }

        binding.screenSaver.setOnCheckedChangeListener { _, isChecked ->
            binding.screenSaverDelayLayout.isVisible = isChecked
            binding.screenSaverTypeLayout.isVisible = isChecked
        }

        binding.screenSaverDelay.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if ((binding.screenSaverDelay.text.toString().toIntOrNull() ?: 5) < 5) {
                    binding.screenSaverDelay.setText("5")
                    Toast.makeText(requireContext(), R.string.delay_must_be_bigger_then_5s, Toast.LENGTH_SHORT).show()
                }
            }

            return@setOnEditorActionListener false
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

        binding.deviceTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                binding.wakeOnProximity.isVisible = binding.screenSaver.isChecked && (parent.adapter.getItem(position) as DeviceModel).hasProximitySensor
            }

            override fun onNothingSelected(AdapterView: AdapterView<*>?) {
            }
        }
    }

    private fun saveSettings() {
        requireContext().getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE).edit {

            val selectedDevice = binding.deviceTypeSpinner.selectedItem as DeviceModel
            // device
            putString(SP_DEVICE, selectedDevice.modelName)

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
            putInt(SP_SCREEN_SAVER_MIN_BRIGHTNESS, binding.screensaverMinBrightness.value.toInt())

            //Http Server
            putBoolean(SP_HTTP_SERVER_ENABLED, binding.httpServerEnabled.isChecked)
        }

        if (!binding.httpServerEnabled.isChecked && mHttpServer.isAlive) mHttpServer.stop()
        else if (binding.httpServerEnabled.isChecked && !mHttpServer.isAlive) mHttpServer.start()

        LocalBroadcastManager.getInstance(ShellyElevateApplication.mApplicationContext).sendBroadcast(Intent(INTENT_SETTINGS_CHANGED))
        Toast.makeText(requireContext(), getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun getLocalIpAddress(): String? =
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { it.isSiteLocalAddress }?.hostAddress

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    fun getScreenSaverSpinnerAdapter(): ArrayAdapter<String?> {
        val adapter = ArrayAdapter<String?>(ShellyElevateApplication.mApplicationContext, android.R.layout.simple_spinner_item)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        for (screenSaver in ScreenSaverManager.getAvailableScreenSavers()) {
            adapter.add(screenSaver.getName())
        }

        return adapter
    }

    companion object {
        const val SCREEN_SAVER_DEFAULT_DELAY = 45
        const val MQTT_DEFAULT_PORT = 1833
    }
}
