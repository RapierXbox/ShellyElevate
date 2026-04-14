package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import me.rapierxbox.shellyelevatev2.Constants.SP_EXTENDED_JAVASCRIPT_INTERFACE
import me.rapierxbox.shellyelevatev2.Constants.SP_HTTP_SERVER_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_IGNORE_SSL_ERRORS
import me.rapierxbox.shellyelevatev2.Constants.SP_LITE_MODE
import me.rapierxbox.shellyelevatev2.Constants.SP_MEDIA_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_MIN_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_BROKER
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_CLIENTID
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_PASSWORD
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_PORT
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_USERNAME
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_DELAY
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_ID
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_MIN_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.Constants.SP_SWITCH_ON_SWIPE
import me.rapierxbox.shellyelevatev2.Constants.SP_POWER_BUTTON_AUTO_REBOOT
import me.rapierxbox.shellyelevatev2.Constants.SP_BUTTON_RELAY_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_BUTTON_RELAY_MAP_FORMAT
import me.rapierxbox.shellyelevatev2.Constants.SP_PROXIMITY_KEEP_AWAKE_SECONDS
import me.rapierxbox.shellyelevatev2.Constants.SP_WAKE_ON_PROXIMITY
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_URL
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceSensorManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mHttpServer
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenManager
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
import java.util.UUID

class SettingsFragment : Fragment() {

    private var _binding: SettingsFragmentBinding? = null
    private val binding get() = _binding!!
    private var savedBrightness = DEFAULT_BRIGHTNESS // Store previous brightness to restore on exit
    private var hasProximitySensor = false
    private val sensorStatusHandler = Handler(Looper.getMainLooper())
    private val sensorStatusRunnable = object : Runnable {
        override fun run() {
            updateSensorStatus()
            sensorStatusHandler.postDelayed(this, 1000)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sensorStatusHandler.removeCallbacks(sensorStatusRunnable)
        // Restore previous brightness when leaving settings
        mDeviceHelper?.setScreenBrightness(savedBrightness)
        _binding = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SettingsFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        activity?.setTitle(R.string.settings)
        
        // Save current brightness and set to max for settings visibility
        savedBrightness = mScreenManager?.let { mSharedPreferences?.getInt(SP_BRIGHTNESS, DEFAULT_BRIGHTNESS) ?: DEFAULT_BRIGHTNESS } ?: DEFAULT_BRIGHTNESS
        mScreenManager?.setScreenOn(true)
        mDeviceHelper?.setScreenBrightness(255)

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

        binding.screenSaverType.adapter = getScreenSaverSpinnerAdapter()
        loadValues()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        sensorStatusHandler.post(sensorStatusRunnable)
    }

    override fun onPause() {
        super.onPause()
        sensorStatusHandler.removeCallbacks(sensorStatusRunnable)
        saveSettings()
    }

    private fun updateSensorStatus() {
        val sensorManager = mDeviceSensorManager
        val proximityAvailable = sensorManager?.isProximitySensorAvailable() == true
        val proximityAvailableText = if (proximityAvailable) getString(R.string.sensor_available_yes) else getString(R.string.sensor_available_no)
        binding.proximitySensorAvailability.text = getString(R.string.proximity_sensor_available, proximityAvailableText)

        val proximityValue = sensorManager?.lastMeasuredDistance ?: 0f
        binding.proximitySensorValue.text = getString(R.string.proximity_sensor_value, proximityValue)
        val maxProximity = sensorManager?.maxProximitySensorValue ?: 1f
        val threshold = if (maxProximity <= 1.5f) 0.5f else maxOf(0.5f, maxProximity * 0.1f)
        val isNear = proximityAvailable && proximityValue < (maxProximity - threshold)
        val proximityState = when {
            !proximityAvailable -> getString(R.string.proximity_state_unavailable)
            isNear -> getString(R.string.proximity_state_near)
            else -> getString(R.string.proximity_state_far)
        }
        binding.proximitySensorState.text = getString(R.string.proximity_sensor_state, proximityState)

        val lightValue = sensorManager?.lastMeasuredLux ?: 0f
        binding.lightSensorValue.text = getString(R.string.light_sensor_value, lightValue)
    }

    private fun loadValues() {
        val device = DeviceModel.getReportedDevice()
        hasProximitySensor = device.hasProximitySensor

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
        binding.mqttClientId.setText(mSharedPreferences.getString(SP_MQTT_CLIENTID,
            "shellyelevate-" + UUID.randomUUID().toString().replace("-".toRegex(), "")
                .substring(2, 6)))

        //Switch
        binding.switchOnSwipe.isChecked = mSharedPreferences.getBoolean(SP_SWITCH_ON_SWIPE, true)

        // Power button auto-reboot
        binding.powerButtonAutoReboot.isChecked = mSharedPreferences.getBoolean(SP_POWER_BUTTON_AUTO_REBOOT, true)

        // Button-to-Relay Mapping
        val hasButtonRelayCapability = device.buttons > 0 && device.relays > 0
        binding.buttonRelayEnabled.isVisible = hasButtonRelayCapability
        binding.buttonRelayMappingLayout.isVisible = hasButtonRelayCapability && mSharedPreferences.getBoolean(SP_BUTTON_RELAY_ENABLED, false)
        if (hasButtonRelayCapability) {
            binding.buttonRelayEnabled.isChecked = mSharedPreferences.getBoolean(SP_BUTTON_RELAY_ENABLED, false)
            setupButtonRelaySpinners(device.buttons, device.relays)
        }

        // media
        binding.mediaEnabled.isChecked = mSharedPreferences.getBoolean(SP_MEDIA_ENABLED, false)

        //Brightness management
        binding.automaticBrightness.isChecked = mSharedPreferences.getBoolean(SP_AUTOMATIC_BRIGHTNESS, true)
        binding.brightnessSetting.value = mSharedPreferences.getInt(SP_BRIGHTNESS, DEFAULT_BRIGHTNESS).toFloat()
        binding.minBrightness.value = mSharedPreferences.getInt(SP_MIN_BRIGHTNESS, 48).toFloat()

        //Screen saver
        binding.screenSaver.isChecked = mSharedPreferences.getBoolean(SP_SCREEN_SAVER_ENABLED, true)
        binding.screenSaverDelay.setText(mSharedPreferences.getInt(SP_SCREEN_SAVER_DELAY, SCREEN_SAVER_DEFAULT_DELAY).toString())
        binding.screenSaverType.setSelection(mSharedPreferences.getInt(SP_SCREEN_SAVER_ID, 0))
        binding.wakeOnProximity.isChecked = mSharedPreferences.getBoolean(SP_WAKE_ON_PROXIMITY, true)
        binding.proximityKeepAwakeSeconds.setText(mSharedPreferences.getInt(SP_PROXIMITY_KEEP_AWAKE_SECONDS, PROXIMITY_KEEP_AWAKE_DEFAULT_SECONDS).toString())
        binding.screensaverMinBrightness.value = mSharedPreferences.getInt(SP_SCREEN_SAVER_MIN_BRIGHTNESS, MIN_BRIGHTNESS_DEFAULT).toFloat()

        //Http Server
        binding.httpServerEnabled.isChecked = mSharedPreferences.getBoolean(SP_HTTP_SERVER_ENABLED, true)
        binding.httpServerAddress.text = getString(R.string.server_url, getLocalIpAddress())
        binding.httpServerStatus.text = getString(if (mHttpServer.isAlive) R.string.http_server_running else R.string.http_server_not_running)

        //Update Visibility
        //ScreenSaver
        binding.screenSaverDelayLayout.isVisible = binding.screenSaver.isChecked
        binding.screenSaverTypeLayout.isVisible = binding.screenSaver.isChecked
        binding.wakeOnProximity.isVisible = binding.screenSaver.isChecked && hasProximitySensor
        binding.proximityKeepAwakeLayout.isVisible = binding.screenSaver.isChecked && hasProximitySensor
        binding.minBrightnessScreenSaverLayout.isVisible = binding.screenSaver.isChecked

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
        binding.mqttClientIdLayout.isVisible = binding.mqttEnabled.isChecked

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
            binding.wakeOnProximity.isVisible = isChecked && hasProximitySensor
            binding.proximityKeepAwakeLayout.isVisible = isChecked && hasProximitySensor
            binding.minBrightnessScreenSaverLayout.isVisible = isChecked
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

        binding.proximityKeepAwakeSeconds.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val keepAwakeSeconds = binding.proximityKeepAwakeSeconds.text.toString().toIntOrNull() ?: PROXIMITY_KEEP_AWAKE_DEFAULT_SECONDS
                if (keepAwakeSeconds < 0) {
                    binding.proximityKeepAwakeSeconds.setText(PROXIMITY_KEEP_AWAKE_DEFAULT_SECONDS.toString())
                    Toast.makeText(requireContext(), R.string.proximity_keep_awake_minimum, Toast.LENGTH_SHORT).show()
                }
            }
            false
        }

        binding.mqttEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.mqttBrokerLayout.isVisible = isChecked
            binding.mqttPortLayout.isVisible = isChecked
            binding.mqttUsernameLayout.isVisible = isChecked
            binding.mqttPasswordLayout.isVisible = isChecked
            binding.mqttClientIdLayout.isVisible = isChecked
        }

        binding.httpServerEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.httpServerLayout.isVisible = isChecked
            binding.httpServerAddressLayout.isVisible = isChecked
        }

        binding.buttonRelayEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.buttonRelayMappingLayout.isVisible = isChecked
        }

        binding.httpServerButton.setOnClickListener {
            mHttpServer.start()
            binding.httpServerText.text = getString(R.string.http_server_running)
            binding.httpServerButton.isVisible = false
        }

        binding.swipeDetectionOverlay.setOnTouchListener { _, event ->
            // Guard against null SwipeHelper when settings opens before app singletons are ready
            mSwipeHelper?.onTouchEvent(event)
            mScreenSaverManager.onTouchEvent(event)
            false
        }
    }

    private fun setupButtonRelaySpinners(buttonCount: Int, relayCount: Int) {
        // Build options list: "None" + "Relay 0" ... "Relay N-1"
        val options = mutableListOf(getString(R.string.button_relay_none))
        for (i in 0 until relayCount) {
            options.add(getString(R.string.button_relay_relay_label, i))
        }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val buttonLayouts = listOf(
            binding.buttonRelayMap0Layout,
            binding.buttonRelayMap1Layout,
            binding.buttonRelayMap2Layout,
            binding.buttonRelayMap3Layout
        )
        val buttonLabels = listOf(
            binding.buttonRelayMap0Label,
            binding.buttonRelayMap1Label,
            binding.buttonRelayMap2Label,
            binding.buttonRelayMap3Label
        )
        val buttonSpinners = listOf(
            binding.buttonRelayMap0,
            binding.buttonRelayMap1,
            binding.buttonRelayMap2,
            binding.buttonRelayMap3
        )

        for (i in buttonLayouts.indices) {
            val visible = i < buttonCount
            buttonLayouts[i].isVisible = visible
            if (visible) {
                buttonLabels[i].text = getString(R.string.button_relay_button_label, i)
                buttonSpinners[i].adapter = adapter
                // Stored value: -1 = None (position 0), 0 = Relay 0 (position 1), etc.
                val storedRelay = mSharedPreferences.getInt(String.format(SP_BUTTON_RELAY_MAP_FORMAT, i), -1)
                buttonSpinners[i].setSelection((storedRelay + 1).coerceIn(0, options.size - 1))
            }
        }
    }

    private fun saveSettings() {
        requireContext().getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE).edit {
            val device = DeviceModel.getReportedDevice()

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
            putString(SP_MQTT_CLIENTID, binding.mqttClientId.text.toString())
            putInt(SP_MQTT_PORT, binding.mqttPort.text.toString().toIntOrNull() ?: MQTT_DEFAULT_PORT)

            //Switch
            putBoolean(SP_SWITCH_ON_SWIPE, binding.switchOnSwipe.isChecked)

            // Power button auto-reboot
            putBoolean(SP_POWER_BUTTON_AUTO_REBOOT, binding.powerButtonAutoReboot.isChecked)

            // Button-to-Relay Mapping
            if (device.buttons > 0 && device.relays > 0) {
                putBoolean(SP_BUTTON_RELAY_ENABLED, binding.buttonRelayEnabled.isChecked)
                val buttonSpinners = listOf(
                    binding.buttonRelayMap0,
                    binding.buttonRelayMap1,
                    binding.buttonRelayMap2,
                    binding.buttonRelayMap3
                )
                for (i in 0 until device.buttons.coerceAtMost(4)) {
                    // position 0 = None (-1), position 1 = Relay 0 (0), etc.
                    putInt(String.format(SP_BUTTON_RELAY_MAP_FORMAT, i), buttonSpinners[i].selectedItemPosition - 1)
                }
            }

            // media
            putBoolean(SP_MEDIA_ENABLED, binding.mediaEnabled.isChecked)

            //Brightness management
            putBoolean(SP_AUTOMATIC_BRIGHTNESS, binding.automaticBrightness.isChecked)
            putInt(SP_BRIGHTNESS, binding.brightnessSetting.value.toInt())
            putInt(SP_MIN_BRIGHTNESS, binding.minBrightness.value.toInt())

            //Screen saver
            putBoolean(SP_SCREEN_SAVER_ENABLED, binding.screenSaver.isChecked)
            putInt(SP_SCREEN_SAVER_DELAY, binding.screenSaverDelay.text.toString().toIntOrNull() ?: SCREEN_SAVER_DEFAULT_DELAY)
            putInt(SP_SCREEN_SAVER_ID, binding.screenSaverType.selectedItemPosition)
            putBoolean(SP_WAKE_ON_PROXIMITY, binding.wakeOnProximity.isChecked && device.hasProximitySensor)
            putInt(SP_PROXIMITY_KEEP_AWAKE_SECONDS, (binding.proximityKeepAwakeSeconds.text.toString().toIntOrNull()
                ?: PROXIMITY_KEEP_AWAKE_DEFAULT_SECONDS).coerceAtLeast(0))
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
        const val PROXIMITY_KEEP_AWAKE_DEFAULT_SECONDS = 30
        const val MQTT_DEFAULT_PORT = 1883
    }
}
