package me.rapierxbox.shellyelevatev2

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.activity.OnBackPressedCallback
import androidx.core.view.MenuProvider
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import me.rapierxbox.shellyelevatev2.Constants.*
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.*
import me.rapierxbox.shellyelevatev2.helper.ThermalZoneReader
import me.rapierxbox.shellyelevatev2.voice.WakeWordDetector
import me.rapierxbox.shellyelevatev2.voice.WakeWordModel
import me.rapierxbox.shellyelevatev2.voice.WakeWordModelDownloader
import me.rapierxbox.shellyelevatev2.voice.WakeWordModelManager
import me.rapierxbox.shellyelevatev2.databinding.SettingsFragmentBinding
import me.rapierxbox.shellyelevatev2.databinding.SettingsPageAudioBinding
import me.rapierxbox.shellyelevatev2.databinding.SettingsPageBluetoothBinding
import me.rapierxbox.shellyelevatev2.databinding.SettingsPageControlsBinding
import me.rapierxbox.shellyelevatev2.databinding.SettingsPageDashboardBinding
import me.rapierxbox.shellyelevatev2.databinding.SettingsPageDisplayBinding
import me.rapierxbox.shellyelevatev2.databinding.SettingsPageHomeAssistantBinding
import me.rapierxbox.shellyelevatev2.databinding.SettingsPageNetworkBinding
import me.rapierxbox.shellyelevatev2.databinding.SettingsPageScreensaverBinding
import me.rapierxbox.shellyelevatev2.databinding.SettingsPageSensorsBinding
import me.rapierxbox.shellyelevatev2.databinding.SettingsPageUpdatesBinding
import me.rapierxbox.shellyelevatev2.api.IntegrationSettingsSection
import me.rapierxbox.shellyelevatev2.display.DisplayController
import me.rapierxbox.shellyelevatev2.display.DisplayModuleSettings
import me.rapierxbox.shellyelevatev2.helper.ScreenManager.DEFAULT_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.helper.ScreenManager.MIN_BRIGHTNESS_DEFAULT
import me.rapierxbox.shellyelevatev2.helper.AdbHelper
import me.rapierxbox.shellyelevatev2.helper.AppUpdater
import me.rapierxbox.shellyelevatev2.helper.HttpDownloader
import me.rapierxbox.shellyelevatev2.helper.RebootHelper
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper
import me.rapierxbox.shellyelevatev2.helper.WebViewUpdater
import me.rapierxbox.shellyelevatev2.helper.WifiIpConfig
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManager
import me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities
import me.rapierxbox.shellyelevatev2.settings.SettingVisibility
import me.rapierxbox.shellyelevatev2.settings.SettingsRegistry
import me.rapierxbox.shellyelevatev2.switcher.AppSwitcherSettings
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Locale
import java.util.UUID

class SettingsFragment : Fragment() {

    private var _binding: SettingsFragmentBinding? = null
    private val binding get() = _binding!!
    // restored in onDestroyView so leaving settings does not leave the screen at 100%
    private var savedBrightness = DEFAULT_BRIGHTNESS
    private val device by lazy { DeviceModel.getReportedDevice() }

    // shared validating client with the bundled roots see HttpDownloader
    private val okHttpClient by lazy { HttpDownloader.defaultClient() }
    private var modelList: MutableList<WakeWordModel> = mutableListOf()
    private var selectedModelName: String = ""
    // the stored model name the page knows so a save only writes a pick the user made
    private var storedModelName: String = ""
    private var downloadJob: Job? = null

    // pages are inflated on first open and stay null until then
    private var dashboardPage: SettingsPageDashboardBinding? = null
    private var displayPage: SettingsPageDisplayBinding? = null
    private var screenSaverPage: SettingsPageScreensaverBinding? = null
    private var networkPage: SettingsPageNetworkBinding? = null
    private var controlsPage: SettingsPageControlsBinding? = null
    private var homeAssistantPage: SettingsPageHomeAssistantBinding? = null
    private var sensorsPage: SettingsPageSensorsBinding? = null
    private var audioPage: SettingsPageAudioBinding? = null
    private var bluetoothPage: SettingsPageBluetoothBinding? = null
    private var updatesPage: SettingsPageUpdatesBinding? = null

    // one binder per opened page so saving never writes defaults for pages that were never loaded
    private val binders = mutableListOf<SettingsBinder>()
    private val pageViews = mutableMapOf<Int, View>()
    private var displaySettings: DisplayModuleSettings? = null
    // the zone list loads in the background and must not be saved before it is there
    private var zonesLoaded = false
    private var menuScrollY = 0
    // what the requires rules of the settings are checked against
    private var caps: Map<String, Any> = emptyMap()
    // set while the adb switch follows the real state so that does not count as a user toggle
    private var adbWifiSyncing = false

    override fun onDestroyView() {
        super.onDestroyView()
        downloadJob?.cancel()
        // let the screen manager pick the level so automatic brightness is honored
        val screenManager = mScreenManager
        if (screenManager != null) screenManager.reapplyBrightness() else mDeviceHelper?.setScreenBrightness(savedBrightness)
        dashboardPage = null
        displayPage = null
        screenSaverPage = null
        networkPage = null
        controlsPage = null
        homeAssistantPage = null
        sensorsPage = null
        audioPage = null
        bluetoothPage = null
        updatesPage = null
        binders.clear()
        pageViews.clear()
        displaySettings = null
        zonesLoaded = false
        openCategory = null
        categoryBack.isEnabled = false
        _binding = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SettingsFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        activity?.setTitle(R.string.settings)

        // force max brightness while settings is open previous value is restored in onDestroyView
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
                        // a deliberate trip to android settings so the watchdog must not pull us back
                        DisplayController.markUserAway("android settings")
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                        true
                    }
                    R.id.action_restart -> {
                        saveSettings()
                        RebootHelper.reboot()
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

        mSharedPreferences.edit { putBoolean(SP_SETTINGS_EVER_SHOWN, true) }
        caps = DeviceCapabilities.snapshot(requireContext())
        setupCategories()
    }

    // ---- categories ----

    // a page whose only content are settings lists them in only so its row hides when none of them applies
    private class Category(
        val row: View,
        @StringRes val title: Int,
        val create: (ViewGroup) -> View,
        val only: List<String>? = null
    )
    private var categories: List<Category> = emptyList()

    // back closes an open category before it leaves settings
    private val categoryBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showCategoryMenu()
    }
    private var openCategory: View? = null

    private fun setupCategories() {
        categories = listOf(
            Category(binding.catDashboardRow, R.string.settings_cat_dashboard, ::createDashboardPage),
            Category(binding.catDisplayRow, R.string.settings_cat_display, ::createDisplayPage),
            Category(binding.catScreenSaverRow, R.string.settings_cat_screensaver, ::createScreenSaverPage),
            Category(binding.catNetworkRow, R.string.settings_cat_network, ::createNetworkPage),
            Category(binding.catControlsRow, R.string.settings_cat_controls, ::createControlsPage),
            Category(binding.catHomeAssistantRow, R.string.settings_cat_home_assistant, ::createHomeAssistantPage),
            Category(binding.catSensorsRow, R.string.settings_cat_sensors, ::createSensorsPage,
                only = listOf(SP_PUBLISH_THERMAL_SENSORS, SP_DYNAMIC_TEMP_OFFSET_ENABLED)),
            Category(binding.catAudioRow, R.string.settings_cat_audio, ::createAudioPage),
            Category(binding.catBluetoothRow, R.string.settings_cat_bluetooth, ::createBluetoothPage,
                only = listOf(SP_BLE_SCANNER_ENABLED)),
            Category(binding.catUpdatesRow, R.string.settings_cat_updates, ::createUpdatesPage),
        )
        for (category in categories) {
            category.row.setOnClickListener { showCategory(category) }
        }
        refreshCategories()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, categoryBack)
    }

    private fun refreshCategories() {
        val values = currentValues()
        for (category in categories) {
            val only = category.only ?: continue
            category.row.isVisible = only.any { isSettingVisible(it, values) }
        }
    }

    // ---- visibility ----

    // unsaved values of every opened page first so a parent on another page counts right away
    private fun currentValues(): SettingVisibility.Values {
        val stored = mSharedPreferences.all
        val unsaved = HashMap<String, Any?>()
        binders.forEach { unsaved.putAll(it.values()) }
        return SettingVisibility.Values { key ->
            if (unsaved.containsKey(key)) unsaved[key]
            else SettingsRegistry.get(key)?.let { SettingsRegistry.resolve(it, stored) }
        }
    }

    private fun isSettingVisible(key: String, values: SettingVisibility.Values): Boolean {
        val def = SettingsRegistry.get(key) ?: return true
        return SettingVisibility.visible(def, values, caps)
    }

    // the rules span pages so every opened page is checked again
    private fun refreshVisibility() {
        if (_binding == null) return
        val values = currentValues()
        binders.forEach { binder -> binder.applyVisibility { isSettingVisible(it, values) } }
    }

    private fun showCategory(category: Category) {
        // a second queued tap must not stack another page on top
        if (openCategory != null) return
        val page = pageViews.getOrPut(category.title) { category.create(binding.settingsPages) }
        // a parent may have changed on another page since this one was built
        refreshVisibility()
        menuScrollY = binding.settingsScroll.scrollY
        binding.settingsMenu.isVisible = false
        page.isVisible = true
        openCategory = page
        categoryBack.isEnabled = true
        activity?.setTitle(category.title)
        binding.settingsScroll.scrollTo(0, 0)
        fadeIn(page)
    }

    private fun showCategoryMenu() {
        openCategory?.let {
            it.animate().cancel()
            it.alpha = 1f
            it.isVisible = false
        }
        openCategory = null
        refreshCategories()
        binding.settingsMenu.isVisible = true
        categoryBack.isEnabled = false
        activity?.setTitle(R.string.settings)
        // back to where the menu was once it is measured again
        val menuY = menuScrollY
        binding.settingsScroll.doOnNextLayout { it.scrollTo(0, menuY) }
        fadeIn(binding.settingsMenu)
    }

    private fun fadeIn(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(PAGE_FADE_MS).withLayer().start()
    }

    // runs once the next frame is drawn so heavy setup never holds back a page switch
    private fun afterNextFrame(block: () -> Unit) {
        val root = binding.root
        Choreographer.getInstance().postFrameCallback {
            root.post { if (_binding?.root === root) block() }
        }
    }

    // builds and loads a binder for one page and keeps it for saving
    private inline fun bindPage(setup: SettingsBinder.() -> Unit) {
        val binder = SettingsBinder(mSharedPreferences) { refreshVisibility() }
        binder.setup()
        binder.loadAll()
        binders += binder
        refreshVisibility()
    }

    private fun launchIpLookup(apply: (String?) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ip = withContext(Dispatchers.IO) { getLocalIpAddress() }
            apply(ip)
        }
    }

    // ---- pages ----

    private fun createDashboardPage(parent: ViewGroup): View {
        val b = SettingsPageDashboardBinding.inflate(layoutInflater, parent, true)
        dashboardPage = b
        // runs the legacy ha ip migration before the url option loads
        ServiceHelper.getWebviewUrl()
        val modules = DisplayModuleSettings(this, b.displayModule, b.displayModuleOptions)
        displaySettings = modules
        bindPage {
            +modules
            +SwitchPref(b.liteMode, SP_LITE_MODE, false)
            +AppSwitcherSettings(b.appSwitcherFingers, b.appSwitcherDirection,
                b.appSwitcherDirectionLayout, b.appSwitcherPreviews)
            row(SP_APP_SWITCHER_PREVIEWS, b.appSwitcherPreviews)
            parent(b.appSwitcherFingers)
        }
        return b.root
    }

    private fun createDisplayPage(parent: ViewGroup): View {
        val b = SettingsPageDisplayBinding.inflate(layoutInflater, parent, true)
        displayPage = b
        bindPage {
            +SwitchPref(b.automaticBrightness, SP_AUTOMATIC_BRIGHTNESS, true)
            row(SP_AUTOMATIC_BRIGHTNESS, b.automaticBrightness)
            parent(b.automaticBrightness)
            row(SP_BRIGHTNESS, b.brightnessSettingLayout)
            row(SP_MIN_BRIGHTNESS, b.minBrightnessLayout)
            +SliderPref(b.brightnessSetting, SP_BRIGHTNESS, DEFAULT_BRIGHTNESS) { mDeviceHelper.setScreenBrightness(it) }
            +SliderPref(b.minBrightness, SP_MIN_BRIGHTNESS, 48) { mDeviceHelper.setScreenBrightness(it) }

            +SwitchPref(b.nightModeEnabled, SP_NIGHT_MODE_ENABLED, false) { enabled ->
                mNightModeManager?.setEnabled(enabled)
            }
        }
        return b.root
    }

    private fun createScreenSaverPage(parent: ViewGroup): View {
        val b = SettingsPageScreensaverBinding.inflate(layoutInflater, parent, true)
        screenSaverPage = b
        b.screenSaverType.adapter = getScreenSaverSpinnerAdapter()
        b.sleepOptimizationLevel.adapter = getSleepOptimizationSpinnerAdapter()
        bindPage {
            +SwitchPref(b.screenSaver, SP_SCREEN_SAVER_ENABLED, true)
            parent(b.screenSaver)
            // min matches the ime action check so leaving settings cannot persist 0
            +IntTextPref(b.screenSaverDelay, SP_SCREEN_SAVER_DELAY, SCREEN_SAVER_DEFAULT_DELAY, min = 5)
            row(SP_SCREEN_SAVER_DELAY, b.screenSaverDelayLayout)
            +SpinnerPref(b.screenSaverType, SP_SCREEN_SAVER_ID, 0)
            row(SP_SCREEN_SAVER_ID, b.screenSaverTypeLayout)
            parent(b.screenSaverType)
            +SpinnerPref(b.sleepOptimizationLevel, SP_SLEEP_OPTIMIZATION_LEVEL, SLEEP_OPT_NONE)
            row(SP_SLEEP_OPTIMIZATION_LEVEL, b.sleepOptimizationLevelLayout)
            +SwitchPref(b.wakeOnProximity, SP_WAKE_ON_PROXIMITY, true)
            row(SP_WAKE_ON_PROXIMITY, b.wakeOnProximity)
            parent(b.wakeOnProximity)
            +IntTextPref(b.proximityKeepAwakeSeconds, SP_PROXIMITY_KEEP_AWAKE_SECONDS, PROXIMITY_KEEP_AWAKE_DEFAULT_SECONDS, min = 0)
            row(SP_PROXIMITY_KEEP_AWAKE_SECONDS, b.proximityKeepAwakeLayout)
            +SliderPref(b.screensaverMinBrightness, SP_SCREEN_SAVER_MIN_BRIGHTNESS, MIN_BRIGHTNESS_DEFAULT) { mDeviceHelper.setScreenBrightness(it) }
            row(SP_SCREEN_SAVER_MIN_BRIGHTNESS, b.minBrightnessScreenSaverLayout)
        }

        b.screenSaverDelay.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE &&
                (b.screenSaverDelay.text.toString().toIntOrNull() ?: 5) < 5) {
                b.screenSaverDelay.setText("5")
                Toast.makeText(requireContext(), R.string.delay_must_be_bigger_then_5s, Toast.LENGTH_SHORT).show()
            }
            false
        }

        b.proximityKeepAwakeSeconds.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val v = b.proximityKeepAwakeSeconds.text.toString().toIntOrNull() ?: PROXIMITY_KEEP_AWAKE_DEFAULT_SECONDS
                if (v < 0) {
                    b.proximityKeepAwakeSeconds.setText(PROXIMITY_KEEP_AWAKE_DEFAULT_SECONDS.toString())
                    Toast.makeText(requireContext(), R.string.proximity_keep_awake_minimum, Toast.LENGTH_SHORT).show()
                }
            }
            false
        }
        return b.root
    }

    private fun createNetworkPage(parent: ViewGroup): View {
        val b = SettingsPageNetworkBinding.inflate(layoutInflater, parent, true)
        networkPage = b
        bindPage {
            // live only runs on a flip so opening the page never bounces adbd
            +SwitchPref(b.adbWifiEnabled, SP_ADB_WIFI_ENABLED, false) { enabled ->
                if (!adbWifiSyncing) applyAdbWifi(enabled)
            }
            visibleWhen(b.adbWifiEnabled, b.adbWifiAddressLayout)

            +SwitchPref(b.httpServerEnabled, SP_HTTP_SERVER_ENABLED, true)
            visibleWhen(b.httpServerEnabled, b.httpServerAddressLayout, b.httpServerLayout)
        }

        val httpAlive = mHttpServer.isAlive
        b.httpServerStatus.text = getString(if (httpAlive) R.string.http_server_running else R.string.http_server_not_running)
        b.httpServerButton.isVisible = !httpAlive
        b.httpServerButton.setOnClickListener {
            // goes through the application so a failed start gets the usual retry backoff
            val app = requireActivity().application as ShellyElevateApplication
            if (app.startHttpServerNow()) {
                b.httpServerStatus.text = getString(R.string.http_server_running)
                b.httpServerButton.isVisible = false
            } else {
                Toast.makeText(requireContext(), R.string.http_server_not_running, Toast.LENGTH_SHORT).show()
            }
        }

        launchIpLookup { ip ->
            val page = networkPage ?: return@launchIpLookup
            page.httpServerAddress.text = getString(R.string.server_url, ip)
            page.adbWifiAddress.text = getString(R.string.adb_wifi_url, ip)
        }

        // the stored value can lag behind what the install script or the integration did over adb
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { AdbHelper.readState() }
            // a toggle that started meanwhile reports its own result
            if (networkPage?.adbWifiEnabled?.isEnabled == true) showAdbWifi(state)
        }

        // the wifi list inflates a row per network so let the page show first
        afterNextFrame {
            val page = networkPage ?: return@afterNextFrame
            // registers itself on the view lifecycle
            WifiSettingsSection(this, page.wifiSection)
            setupWifiIpSection(page)
        }
        return b.root
    }

    private fun applyAdbWifi(enabled: Boolean) {
        val b = networkPage ?: return
        b.adbWifiEnabled.isEnabled = false
        b.adbWifiStatus.setText(R.string.adb_wifi_status_applying)
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { AdbHelper.apply(enabled) }
            networkPage?.adbWifiEnabled?.isEnabled = true
            showAdbWifi(state)
            state.error?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
        }
    }

    // the switch always shows what adbd really does
    private fun showAdbWifi(state: AdbHelper.State) {
        val b = networkPage ?: return
        if (b.adbWifiEnabled.isChecked != state.enabled) {
            adbWifiSyncing = true
            b.adbWifiEnabled.isChecked = state.enabled
            adbWifiSyncing = false
        }
        b.adbWifiStatus.text = state.error
            ?: getString(if (state.enabled) R.string.adb_wifi_status_on else R.string.adb_wifi_status_off)
    }

    private fun createControlsPage(parent: ViewGroup): View {
        val b = SettingsPageControlsBinding.inflate(layoutInflater, parent, true)
        controlsPage = b
        val hasButtonRelayCapability = device.buttons > 0 && device.relays > 0
        val hasSwInput = device.inputs > 0
        b.swInputMode.adapter = getSwInputModeSpinnerAdapter()
        // ui edits input 0; every current model has at most one sw input
        val swInputModeKey = String.format(Locale.US, SP_SW_INPUT_MODE_FORMAT, 0)
        val buttonLayouts = listOf(b.buttonRelayMap0Layout, b.buttonRelayMap1Layout, b.buttonRelayMap2Layout, b.buttonRelayMap3Layout)

        bindPage {
            +SwitchPref(b.switchOnSwipe, SP_SWITCH_ON_SWIPE, true)
            row(SP_SWITCH_ON_SWIPE, b.switchOnSwipe)
            +SwitchPref(b.publishSwipeEvents, SP_PUBLISH_SWIPE_EVENTS, true)
            +SwitchPref(b.powerButtonAutoReboot, SP_POWER_BUTTON_AUTO_REBOOT, true)
            row(SP_POWER_BUTTON_AUTO_REBOOT, b.powerButtonAutoReboot)

            if (hasSwInput) {
                +SpinnerPref(b.swInputMode, swInputModeKey, SW_INPUT_MODE_BUTTON)
                parent(b.swInputMode)
                +SwitchPref(b.swInputInvert, String.format(Locale.US, SP_SW_INPUT_INVERT_FORMAT, 0), false)
            }
            row(swInputModeKey, b.swInputModeLayout)
            row(String.format(Locale.US, SP_SW_INPUT_INVERT_FORMAT, 0), b.swInputInvert)
            row(String.format(Locale.US, SP_SW_INPUT_RELAY_MAP_FORMAT, 0), b.swInputRelayLayout)

            if (hasButtonRelayCapability) {
                +SwitchPref(b.buttonRelayEnabled, SP_BUTTON_RELAY_ENABLED, false)
                parent(b.buttonRelayEnabled)
            }
            row(SP_BUTTON_RELAY_ENABLED, b.buttonRelayEnabled)
            // the mapping block shows with its first row since every row needs the switch on
            row(String.format(Locale.US, SP_BUTTON_RELAY_MAP_FORMAT, 0), b.buttonRelayMappingLayout)
            buttonLayouts.forEachIndexed { i, layout -> row(String.format(Locale.US, SP_BUTTON_RELAY_MAP_FORMAT, i), layout) }
        }

        if (hasButtonRelayCapability) setupButtonRelaySpinners(b, device.buttons, device.relays)
        if (hasSwInput) setupSwInputRelaySpinner(b, device.relays)
        return b.root
    }

    private fun createHomeAssistantPage(parent: ViewGroup): View {
        val b = SettingsPageHomeAssistantBinding.inflate(layoutInflater, parent, true)
        homeAssistantPage = b
        val defaultClientId = "shellyelevate-" + UUID.randomUUID().toString().replace("-", "").substring(2, 6)
        bindPage {
            +SwitchPref(b.integrationApiEnabled, SP_INTEGRATION_API_ENABLED, true)
            visibleWhen(b.integrationApiEnabled, b.integrationStatus, b.integrationClients, b.integrationFingerprint)
            // voice and the bluetooth proxy on other pages need the integration api
            parent(b.integrationApiEnabled)
            +SwitchPref(b.mqttEnabled, SP_MQTT_ENABLED, false)
            parent(b.mqttEnabled)
            row(SP_MQTT_BROKER, b.mqttBrokerLayout)
            row(SP_MQTT_PORT, b.mqttPortLayout)
            row(SP_MQTT_USERNAME, b.mqttUsernameLayout)
            row(SP_MQTT_PASSWORD, b.mqttPasswordLayout)
            row(SP_MQTT_CLIENTID, b.mqttClientIdLayout)
            row(SP_MQTT_HA_DISCOVERY, b.mqttHaDiscovery)
            row(SP_MQTT_RETAIN_STATE, b.mqttRetainState)
            +TextPref(b.mqttBroker, SP_MQTT_BROKER)
            +IntTextPref(b.mqttPort, SP_MQTT_PORT, MQTT_DEFAULT_PORT)
            +TextPref(b.mqttUsername, SP_MQTT_USERNAME)
            +TextPref(b.mqttPassword, SP_MQTT_PASSWORD)
            +TextPref(b.mqttClientId, SP_MQTT_CLIENTID, defaultClientId)
            +SwitchPref(b.mqttHaDiscovery, SP_MQTT_HA_DISCOVERY, true)
            +SwitchPref(b.mqttRetainState, SP_MQTT_RETAIN_STATE, true)
        }
        IntegrationSettingsSection(this, b)
        return b.root
    }

    private fun createSensorsPage(parent: ViewGroup): View {
        val b = SettingsPageSensorsBinding.inflate(layoutInflater, parent, true)
        sensorsPage = b
        bindPage {
            +SwitchPref(b.publishThermalSensors, SP_PUBLISH_THERMAL_SENSORS, false)
            row(SP_PUBLISH_THERMAL_SENSORS, b.publishThermalSensors)
            +SwitchPref(b.dynamicTempOffsetEnabled, SP_DYNAMIC_TEMP_OFFSET_ENABLED, false)
            row(SP_DYNAMIC_TEMP_OFFSET_ENABLED, b.dynamicTempOffsetEnabled)
            parent(b.dynamicTempOffsetEnabled)
            // the offset block shares the rule of its first row
            row(SP_DYNAMIC_TEMP_OFFSET_ZONE, b.dynamicTempOffsetLayout, b.dynamicTempOffsetZoneLayout)
            row(SP_DYNAMIC_TEMP_OFFSET_BASELINE, b.dynamicTempOffsetBaselineLayout)
            row(SP_DYNAMIC_TEMP_OFFSET_K, b.dynamicTempOffsetKLayout)
            +FloatTextPref(b.dynamicTempOffsetBaseline, SP_DYNAMIC_TEMP_OFFSET_BASELINE, 40.0f)
            +FloatTextPref(b.dynamicTempOffsetK, SP_DYNAMIC_TEMP_OFFSET_K, 0.3f)
        }

        // zone discovery walks sysfs so keep it off the main thread
        viewLifecycleOwner.lifecycleScope.launch {
            val zoneNames = withContext(Dispatchers.IO) { ThermalZoneReader.discoverZones().map { it.type } }
            val page = sensorsPage ?: return@launch
            page.dynamicTempOffsetZone.adapter = spinnerAdapter(zoneNames)
            val savedZone = mSharedPreferences.getString(SP_DYNAMIC_TEMP_OFFSET_ZONE, null)
            val zoneIdx = zoneNames.indexOf(savedZone)
            if (zoneIdx >= 0) page.dynamicTempOffsetZone.setSelection(zoneIdx)
            zonesLoaded = true
        }
        return b.root
    }

    private fun createAudioPage(parent: ViewGroup): View {
        val b = SettingsPageAudioBinding.inflate(layoutInflater, parent, true)
        audioPage = b
        val wakewordsDir = WakeWordModelManager.getModelDirectory(requireContext())
        bindPage {
            +SwitchPref(b.mediaEnabled, SP_MEDIA_ENABLED, false)

            +SwitchPref(b.haVoiceEnabled, SP_HA_VOICE_ENABLED, false)
            row(SP_HA_VOICE_ENABLED, b.haVoiceEnabled, b.haVoiceHint)
            parent(b.haVoiceEnabled)
            // the engine block shares the rule of its first row
            row(SP_VOICE_ASSISTANT_MAX_RECORD_SECONDS, b.voiceEngineLayout, b.voiceAssistantMaxSecondsLayout)
            +IntTextPref(b.voiceAssistantMaxSeconds, SP_VOICE_ASSISTANT_MAX_RECORD_SECONDS, 10, min = 1)
            +SwitchPref(b.voiceWakeEnabled, SP_VOICE_WAKE_ENABLED, true)
            row(SP_VOICE_WAKE_ENABLED, b.voiceWakeEnabled)
            parent(b.voiceWakeEnabled)
            row(SP_VOICE_WAKE_MODEL_NAME, b.voiceWakeModelLayout)
            row(SP_VOICE_WAKE_EXPERIMENTAL_MODELS, b.voiceWakeExperimentalModels)
            row(SP_VOICE_WAKE_SENSITIVITY, b.voiceWakeSensitivityLayout)
            row(SP_VOICE_WAKE_COOLDOWN_SEC, b.voiceWakeCooldownLayout)
            row(SP_VOICE_WAKE_SOUND_ENABLED, b.voiceWakeSoundEnabled)
            row(SP_VOICE_SCORE_BAR_ENABLED, b.voiceScoreBarEnabled)
            // the vad model is required to suppress false wake triggers so fetch
            // it as soon as wake word detection is enabled registered via the binder
            // so it does not clobber the visibility toggle listener
            onToggle(b.voiceWakeEnabled) { isChecked ->
                if (isChecked && !WakeWordModelManager.isVadPresent(wakewordsDir)) {
                    ensureVadDownloaded(wakewordsDir)
                }
            }
            +SwitchPref(b.voiceWakeExperimentalModels, SP_VOICE_WAKE_EXPERIMENTAL_MODELS, false)
            +SliderPref(b.voiceWakeSensitivity, SP_VOICE_WAKE_SENSITIVITY, 50)
            +SliderPref(b.voiceWakeCooldown, SP_VOICE_WAKE_COOLDOWN_SEC, 5)
            +SwitchPref(b.voiceWakeSoundEnabled, SP_VOICE_WAKE_SOUND_ENABLED, true)
            +SwitchPref(b.voiceScoreBarEnabled, SP_VOICE_SCORE_BAR_ENABLED, false)
        }

        val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        b.volumeSetting.value = (curVol.toFloat() / maxVol * 100f).coerceIn(0f, 100f).roundToInt().toFloat()

        selectedModelName = mSharedPreferences.getString(SP_VOICE_WAKE_MODEL_NAME, "") ?: ""
        storedModelName = selectedModelName
        updateWakeModelStatus()

        var lastAppliedVol = -1
        b.volumeSetting.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val target = (value / 100f * max).roundToInt().coerceIn(0, max)
                if (target != lastAppliedVol) {
                    lastAppliedVol = target
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_PLAY_SOUND)
                }
            }
        }

        setupModelChooser(b, wakewordsDir)
        return b.root
    }

    private fun createBluetoothPage(parent: ViewGroup): View {
        val b = SettingsPageBluetoothBinding.inflate(layoutInflater, parent, true)
        bluetoothPage = b
        bindPage {
            +SwitchPref(b.bleScannerEnabled, SP_BLE_SCANNER_ENABLED, false)
            row(SP_BLE_SCANNER_ENABLED, b.bleScannerEnabled, b.bleScannerHint)
        }
        return b.root
    }


    private fun createUpdatesPage(parent: ViewGroup): View {
        val b = SettingsPageUpdatesBinding.inflate(layoutInflater, parent, true)
        updatesPage = b
        bindPage {
            +SwitchPref(b.appUpdatePrerelease, SP_UPDATE_PRERELEASE, false)
        }
        setupWebViewUpdater(b)
        setupAppUpdater(b)
        return b.root
    }

    // ---- updates ----

    private fun setupWebViewUpdater(b: SettingsPageUpdatesBinding) {
        b.webviewUpdateSection.isVisible = false
        if (!WebViewUpdater.hasUpdateUrl()) return
        b.webviewUpdateButton.setOnClickListener { startWebViewUpdateDownload() }
        val ctx = requireContext().applicationContext
        // package manager lookups stay off the main thread
        viewLifecycleOwner.lifecycleScope.launch {
            val (version, needed) = withContext(Dispatchers.IO) {
                WebViewUpdater.getInstalledWebViewVersion(ctx) to WebViewUpdater.isUpdateNeeded(ctx)
            }
            val page = updatesPage ?: return@launch
            page.webviewUpdateSection.isVisible = needed
            if (!needed) return@launch
            page.webviewUpdateVersion.text = getString(R.string.webview_update_version,
                version.ifEmpty { getString(R.string.webview_update_version_unknown) })
            page.webviewUpdateStatus.text = getString(R.string.webview_update_status_needed)
        }
    }

    private fun startWebViewUpdateDownload() {
        val b = updatesPage ?: return
        if (WebViewUpdater.isDownloadInProgress()) return
        b.webviewUpdateButton.isEnabled = false
        b.webviewUpdateProgressLayout.visibility = View.VISIBLE
        b.webviewUpdateProgressBar.progress = 0
        b.webviewUpdateProgressText.text = "0%"

        WebViewUpdater.downloadAndStage(requireContext(), object : WebViewUpdater.Listener {
            override fun onProgress(percent: Int) {
                val page = updatesPage ?: return
                page.webviewUpdateProgressBar.progress = percent
                page.webviewUpdateProgressText.text = getString(R.string.webview_update_downloading, percent)
            }
            override fun onCompleted(staged: java.io.File) {
                val page = updatesPage ?: return
                page.webviewUpdateProgressLayout.visibility = View.GONE
                page.webviewUpdateButton.isEnabled = true
                showRebootToInstallDialog()
            }
            override fun onFailed(reason: String) {
                val page = updatesPage ?: return
                page.webviewUpdateProgressLayout.visibility = View.GONE
                page.webviewUpdateButton.isEnabled = true
                Toast.makeText(requireContext(), getString(R.string.webview_update_failed, reason), Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun showRebootToInstallDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.webview_update_reboot_title)
            .setMessage(R.string.webview_update_reboot_message)
            .setPositiveButton(R.string.webview_update_reboot_now) { _, _ ->
                val ctx = requireContext().applicationContext
                WebViewUpdater.rebootToInstall(ctx) { reason ->
                    Toast.makeText(ctx, ctx.getString(R.string.webview_update_failed, reason), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.webview_update_reboot_later, null)
            .show()
    }

    // the release found by the last check and offered behind the update button
    private var pendingRelease: AppUpdater.ReleaseInfo? = null

    // checks right away when the page opens so the button already says what it will do
    private fun setupAppUpdater(b: SettingsPageUpdatesBinding) {
        b.appUpdateCurrentVersion.text = getString(R.string.app_update_current, BuildConfig.VERSION_NAME)
        b.appUpdateStatus.text = ""
        b.appUpdateButton.setOnClickListener { onAppUpdateButton() }
        // switching the channel checks again so the offer always matches the switch
        // and is stored right away since an update restarts the app before settings get saved
        b.appUpdatePrerelease.setOnCheckedChangeListener { _, checked ->
            mSharedPreferences.edit { putBoolean(SP_UPDATE_PRERELEASE, checked) }
            startAppUpdateCheck()
        }
        if (AppUpdater.isInProgress()) {
            b.appUpdateStatus.text = getString(R.string.app_update_status_busy)
            b.appUpdateButton.isEnabled = false
        } else {
            startAppUpdateCheck()
        }
    }

    // the one button checks updates or cancels depending on the state
    private fun onAppUpdateButton() {
        val release = pendingRelease
        when {
            AppUpdater.isInProgress() -> AppUpdater.cancelDownload()
            release != null -> startAppUpdateDownload(release)
            else -> startAppUpdateCheck()
        }
    }

    private fun startAppUpdateCheck() {
        val b = updatesPage ?: return
        if (AppUpdater.isInProgress()) return
        pendingRelease = null
        b.appUpdateButton.isEnabled = false
        b.appUpdateButton.setText(R.string.app_update_check_again)
        b.appUpdateStatus.text = getString(R.string.app_update_status_checking)
        // the switch counts before settings are saved so a fresh toggle applies right away
        AppUpdater.checkForUpdate(b.appUpdatePrerelease.isChecked, object : AppUpdater.CheckListener {
            override fun onUpdateAvailable(info: AppUpdater.ReleaseInfo) {
                val page = updatesPage ?: return
                pendingRelease = info
                val res = if (info.prerelease) R.string.app_update_available_prerelease else R.string.app_update_available
                page.appUpdateStatus.text = getString(res, info.versionName)
                page.appUpdateButton.setText(R.string.app_update_install)
                page.appUpdateButton.isEnabled = true
            }
            override fun onUpToDate(current: String) {
                val page = updatesPage ?: return
                page.appUpdateButton.isEnabled = true
                page.appUpdateStatus.text = getString(R.string.app_update_uptodate)
            }
            override fun onFailed(reason: String) {
                val page = updatesPage ?: return
                page.appUpdateButton.isEnabled = true
                page.appUpdateStatus.text = getString(R.string.app_update_failed, reason)
            }
        })
    }

    private fun startAppUpdateDownload(info: AppUpdater.ReleaseInfo) {
        val b = updatesPage ?: return
        b.appUpdateButton.setText(R.string.app_update_cancel)
        b.appUpdatePrerelease.isEnabled = false
        b.appUpdateProgressLayout.visibility = View.VISIBLE
        b.appUpdateProgressBar.progress = 0
        b.appUpdateProgressText.text = "0%"

        AppUpdater.downloadAndInstall(requireContext().applicationContext, info, object : AppUpdater.InstallListener {
            override fun onProgress(percent: Int) {
                val page = updatesPage ?: return
                page.appUpdateProgressBar.progress = percent
                page.appUpdateProgressText.text = getString(R.string.app_update_downloading, percent)
            }
            override fun onInstalling() {
                val page = updatesPage ?: return
                page.appUpdateProgressLayout.visibility = View.GONE
                page.appUpdateStatus.text = getString(R.string.app_update_installing)
                // the installer owns the apk now so there is nothing left to cancel
                page.appUpdateButton.isEnabled = false
            }
            override fun onFailed(reason: String) {
                val page = updatesPage ?: return
                resetAfterDownload(page)
                page.appUpdateStatus.text = getString(R.string.app_update_failed, reason)
                page.appUpdateButton.setText(R.string.app_update_retry)
            }
            override fun onCancelled() {
                val page = updatesPage ?: return
                resetAfterDownload(page)
                page.appUpdateStatus.text = getString(R.string.app_update_cancelled)
                page.appUpdateButton.setText(R.string.app_update_install)
            }
        })
    }

    private fun resetAfterDownload(page: SettingsPageUpdatesBinding) {
        page.appUpdateProgressLayout.visibility = View.GONE
        page.appUpdateButton.isEnabled = true
        page.appUpdatePrerelease.isEnabled = true
    }

    // ---- wifi ip configuration ----

    private var wifiIpState: WifiIpConfig.State? = null
    // fields are filled once so a connectivity refresh never clobbers typed values
    private var wifiIpPrefilled = false
    private var wifiIpApplying = false
    private var wifiIpRefreshJob: Job? = null

    private fun setupWifiIpSection(b: SettingsPageNetworkBinding) {
        // the fragment can outlive its view so start clean
        wifiIpPrefilled = false
        wifiIpApplying = false
        b.wifiIpUseStatic.setOnCheckedChangeListener { _, isChecked -> b.wifiIpStaticLayout.isVisible = isChecked }
        b.wifiIpApplyButton.setOnClickListener { confirmWifiIpApply() }
        val ctx = requireContext().applicationContext
        val callback = WifiIpConfig.watch(ctx) { refreshWifiIp() }
        // unregister with the view so callbacks never touch a dead binding
        viewLifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) WifiIpConfig.unwatch(ctx, callback)
        })
        refreshWifiIp()
    }

    private fun refreshWifiIp() {
        if (networkPage == null) return
        val ctx = requireContext().applicationContext
        wifiIpRefreshJob?.cancel()
        wifiIpRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { WifiIpConfig.read(ctx) }
            showWifiIpState(state)
        }
    }

    private fun showWifiIpState(s: WifiIpConfig.State) {
        val b = networkPage ?: return
        wifiIpState = s
        val unknown = getString(R.string.wifi_ip_unknown)
        b.wifiIpInfo.text = if (!s.connected && s.ssid == null) {
            getString(R.string.wifi_ip_not_connected)
        } else {
            listOfNotNull(
                getString(R.string.wifi_ip_ssid, s.ssid ?: unknown),
                getString(R.string.wifi_ip_address, s.ipAddress ?: unknown),
                if (s.prefixLength in 1..32) getString(R.string.wifi_ip_netmask, s.prefixLength, WifiIpConfig.prefixToNetmask(s.prefixLength)) else null,
                getString(R.string.wifi_ip_gateway, s.gateway ?: unknown),
                getString(R.string.wifi_ip_dns, s.dns.joinToString(", ").ifEmpty { unknown }),
                getString(R.string.wifi_ip_mode, when {
                    !s.configFound -> unknown
                    s.isStatic -> getString(R.string.wifi_ip_mode_static)
                    else -> getString(R.string.wifi_ip_mode_dhcp)
                })
            ).joinToString("\n")
        }

        val editable = s.configFound && s.permitted
        b.wifiIpPermissionHint.isVisible = !s.permitted
        b.wifiIpEditLayout.isVisible = editable
        b.wifiIpApplyButton.isEnabled = editable && !wifiIpApplying

        if (editable && !wifiIpPrefilled) {
            wifiIpPrefilled = true
            // stored static values first otherwise start from what dhcp handed out
            val stored = s.isStatic && s.staticIp != null
            b.wifiIpUseStatic.isChecked = s.isStatic
            b.wifiIpStaticLayout.isVisible = s.isStatic
            b.wifiIpAddress.setText((if (stored) s.staticIp else s.ipAddress) ?: "")
            val prefix = if (stored) s.staticPrefix else s.prefixLength
            b.wifiIpPrefix.setText(if (prefix > 0) prefix.toString() else "")
            b.wifiIpGateway.setText((if (stored) s.staticGateway else s.gateway) ?: "")
            val dns = if (stored) s.staticDns else s.dns.filter { WifiIpConfig.parseIpv4(it) != null }
            b.wifiIpDns1.setText(dns.getOrNull(0) ?: "")
            b.wifiIpDns2.setText(dns.getOrNull(1) ?: "")
        }
    }

    private fun confirmWifiIpApply() {
        val b = networkPage ?: return
        val s = wifiIpState ?: return
        if (!s.configFound || !s.permitted || wifiIpApplying) return
        val unknown = getString(R.string.wifi_ip_unknown)
        val ssid = s.ssid ?: unknown
        val config: WifiIpConfig.StaticConfig?
        val message: String
        if (b.wifiIpUseStatic.isChecked) {
            val result = WifiIpConfig.parseStatic(
                b.wifiIpAddress.text.toString(), b.wifiIpPrefix.text.toString(),
                b.wifiIpGateway.text.toString(), b.wifiIpDns1.text.toString(),
                b.wifiIpDns2.text.toString())
            val parsed = result.config
            if (parsed == null) {
                val field = when (result.field) {
                    WifiIpConfig.FIELD_PREFIX -> b.wifiIpPrefix
                    WifiIpConfig.FIELD_GATEWAY -> b.wifiIpGateway
                    WifiIpConfig.FIELD_DNS1 -> b.wifiIpDns1
                    WifiIpConfig.FIELD_DNS2 -> b.wifiIpDns2
                    else -> b.wifiIpAddress
                }
                field.error = getString(result.error)
                field.requestFocus()
                return
            }
            config = parsed
            message = getString(R.string.wifi_ip_confirm_static, ssid, parsed.ip.hostAddress, parsed.prefix,
                parsed.gateway.hostAddress, parsed.dns.joinToString(", ") { it.hostAddress ?: "" })
        } else {
            config = null
            message = getString(R.string.wifi_ip_confirm_dhcp, ssid, s.ipAddress ?: unknown)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.wifi_ip_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.wifi_ip_confirm_apply) { _, _ -> applyWifiIp(s.networkId, config) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyWifiIp(networkId: Int, config: WifiIpConfig.StaticConfig?) {
        val b = networkPage ?: return
        wifiIpApplying = true
        b.wifiIpApplyButton.isEnabled = false
        b.wifiIpStatus.isVisible = true
        b.wifiIpStatus.text = getString(R.string.wifi_ip_status_applying)
        val ctx = requireContext().applicationContext
        WifiIpConfig.apply(ctx, networkId, config, object : WifiIpConfig.ApplyListener {
            override fun onApplied() {
                if (networkPage == null) return
                verifyWifiIp(ctx, config)
            }
            override fun onFailed(reason: String) {
                if (networkPage == null) return
                finishWifiIp(false, getString(R.string.wifi_ip_failed, reason))
            }
        })
    }

    // the save only starts the change so wait for the link to come back with the new address
    private fun verifyWifiIp(ctx: Context, config: WifiIpConfig.StaticConfig?) {
        val timeoutSeconds = 30
        val target = config?.ip?.hostAddress
        viewLifecycleOwner.lifecycleScope.launch {
            // let the old address drop first so dhcp is not judged on a stale link
            delay(3000)
            var state = WifiIpConfig.State()
            for (i in 0 until timeoutSeconds) {
                state = withContext(Dispatchers.IO) { WifiIpConfig.read(ctx) }
                val ok = state.connected && state.configFound && state.isStatic == (config != null) &&
                    (target == null || state.ipAddress == target)
                if (ok) {
                    finishWifiIp(true, getString(R.string.wifi_ip_result_ok, state.ssid ?: "", state.ipAddress))
                    return@launch
                }
                delay(1000)
            }
            finishWifiIp(false, getString(R.string.wifi_ip_result_timeout, timeoutSeconds + 3,
                state.ipAddress ?: getString(R.string.wifi_ip_unknown)))
        }
    }

    private fun finishWifiIp(ok: Boolean, message: String) {
        val b = networkPage ?: return
        wifiIpApplying = false
        b.wifiIpStatus.isVisible = false
        // pick up the stored config again after a change
        if (ok) wifiIpPrefilled = false
        refreshWifiIp()
        AlertDialog.Builder(requireContext())
            .setTitle(if (ok) R.string.wifi_ip_result_ok_title else R.string.wifi_ip_result_fail_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    // shared "none" + "relay 0".."relay n-1" option list used by both relay pickers
    private fun relayOptionsAdapter(relayCount: Int): ArrayAdapter<String> {
        val options = mutableListOf(getString(R.string.button_relay_none))
        for (i in 0 until relayCount) options.add(getString(R.string.button_relay_relay_label, i))
        return spinnerAdapter(options)
    }

    private fun setupSwInputRelaySpinner(b: SettingsPageControlsBinding, relayCount: Int) {
        val optionCount = relayCount + 1
        b.swInputRelay.adapter = relayOptionsAdapter(relayCount)
        // stored value -1 is none position 0 then relay 0 at position 1 and so on
        val storedRelay = mSharedPreferences.getInt(String.format(Locale.US, SP_SW_INPUT_RELAY_MAP_FORMAT, 0), 0)
        b.swInputRelay.setSelection((storedRelay + 1).coerceIn(0, optionCount - 1))
    }

    private fun setupModelChooser(b: SettingsPageAudioBinding, wakewordsDir: File) {
        // show installed models immediately so the picker is not empty while we
        // wait for github remote results are merged in once they arrive
        rebuildModelList(WakeWordModelManager.getInstalledModels(wakewordsDir), emptyList(), emptyList())
        fetchAndMergeRemoteModels(wakewordsDir)

        b.voiceWakeChooseModel.setOnClickListener { showModelPickerDialog() }

        b.voiceWakeModelRefresh.setOnClickListener {
            fetchAndMergeRemoteModels(wakewordsDir)
        }

        b.voiceWakeExperimentalModels.setOnCheckedChangeListener { _, _ ->
            fetchAndMergeRemoteModels(wakewordsDir)
        }
    }

    private fun ensureVadDownloaded(wakewordsDir: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                WakeWordModelManager.ensureVadDownloaded(okHttpClient, wakewordsDir)
            }
            if (result == WakeWordModelManager.VadResult.FAILED && isAdded) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.voice_wake_vad_download_failed_title)
                    .setMessage(R.string.voice_wake_vad_download_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else if (result == WakeWordModelManager.VadResult.DOWNLOADED) {
                // force a reload so the detector picks up the freshly fetched vad
                mVoiceEngine?.invalidateLoadedModel()
            }
        }
    }

    private fun rebuildModelList(
        installed: List<WakeWordModel.Installed>,
        downloadable: List<WakeWordModel.Downloadable>,
        experimental: List<WakeWordModel.Experimental>
    ) {
        val installedNames = installed.map { it.name }.toSet()
        val filteredDownloadable = downloadable.filter { it.name !in installedNames }
        val filteredExperimental = experimental.filter { it.name !in installedNames }
        modelList = (installed + filteredDownloadable + filteredExperimental + listOf(WakeWordModel.Custom.INSTANCE)).toMutableList()
        updateModelLabel()
    }

    private fun updateModelLabel() {
        val b = audioPage ?: return
        b.voiceWakeModelLabel.text = selectedModelName.ifEmpty { getString(R.string.voice_wake_model_none_selected) }
    }

    private fun fetchAndMergeRemoteModels(wakewordsDir: File) {
        val b = audioPage ?: return
        val showExperimental = b.voiceWakeExperimentalModels.isChecked
        b.voiceWakeModelRefresh.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (official, experimental) = withContext(Dispatchers.IO) {
                    val off = WakeWordModelManager.fetchOfficialModels(okHttpClient)
                    // a failed experimental fetch must not drop the official list
                    val exp = if (!showExperimental) emptyList() else try {
                        WakeWordModelManager.fetchExperimentalModels(okHttpClient)
                    } catch (e: IOException) {
                        Log.w("SettingsFragment", "experimental model fetch failed: ${e.message}")
                        emptyList()
                    }
                    Pair(off, exp)
                }
                val installed = WakeWordModelManager.getInstalledModels(wakewordsDir)
                rebuildModelList(installed, official, experimental)
            } catch (e: Exception) {
                Log.w("SettingsFragment", "GitHub fetch failed: ${e.message}")
                if (isAdded) Toast.makeText(requireContext(), getString(R.string.voice_wake_model_fetch_failed), Toast.LENGTH_SHORT).show()
            } finally {
                audioPage?.voiceWakeModelRefresh?.isEnabled = true
            }
        }
    }

    private fun showModelPickerDialog() {
        if (modelList.isEmpty()) return
        val labels = modelList.map { labelFor(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.voice_wake_model_name)
            .setItems(labels) { _, which ->
                val model = modelList.getOrNull(which) ?: return@setItems
                onModelSelected(model)
            }
            .show()
    }

    private fun labelFor(model: WakeWordModel): String = when (model) {
        is WakeWordModel.Installed    -> "✓  ${model.displayName}"
        is WakeWordModel.Downloadable -> "⬇  ${model.displayName}"
        is WakeWordModel.Experimental -> "⚡  ${model.displayName}"
        else                          -> model.displayName  // custom
    }

    private fun onModelSelected(model: WakeWordModel) {
        when (model) {
            is WakeWordModel.Installed -> {
                selectedModelName = model.name
                updateModelLabel()
            }
            is WakeWordModel.Downloadable -> startModelDownload(model.name, model.tfliteUrl, model.jsonUrl)
            is WakeWordModel.Experimental -> startModelDownload(model.name, model.tfliteUrl, model.jsonUrl)
            else -> showCustomModelPathDialog()  // custom
        }
    }

    private fun startModelDownload(name: String, tfliteUrl: String, jsonUrl: String) {
        val b = audioPage ?: return
        downloadJob?.cancel()
        val wakewordsDir = WakeWordModelManager.getModelDirectory(requireContext())
        val mainHandler = Handler(Looper.getMainLooper())

        b.voiceWakeDownloadProgress.visibility = View.VISIBLE
        b.voiceWakeProgressBar.progress = 0
        b.voiceWakeProgressText.text = "0%"

        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // the downloader keeps an installed model until the new one is complete
                    // and stops between chunks once this job is cancelled
                    WakeWordModelDownloader.download(okHttpClient, wakewordsDir, name, tfliteUrl, jsonUrl, { overall ->
                        mainHandler.post { audioPage?.let {
                            it.voiceWakeProgressBar.progress = overall
                            it.voiceWakeProgressText.text = "$overall%"
                        }}
                    }, { !isActive })
                }
                selectedModelName = name
                storedModelName = name
                mSharedPreferences.edit { putString(SP_VOICE_WAKE_MODEL_NAME, name) }
                val installed = WakeWordModelManager.getInstalledModels(wakewordsDir)
                rebuildModelList(installed,
                    modelList.filterIsInstance<WakeWordModel.Downloadable>(),
                    modelList.filterIsInstance<WakeWordModel.Experimental>())
                LocalBroadcastManager.getInstance(requireContext())
                    .sendBroadcast(Intent(INTENT_SETTINGS_CHANGED))
                updateWakeModelStatus()
                Toast.makeText(requireContext(), getString(R.string.voice_wake_model_downloaded, name), Toast.LENGTH_SHORT).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // a cancelled transfer ends with an io error and is no failure to report
                if (!isActive) return@launch
                Log.e("SettingsFragment", "Download failed for $name", e)
                if (isAdded) Toast.makeText(requireContext(), getString(R.string.voice_wake_model_download_failed), Toast.LENGTH_SHORT).show()
            } finally {
                audioPage?.voiceWakeDownloadProgress?.visibility = View.GONE
            }
        }
    }

    private fun showCustomModelPathDialog() {
        val wakewordsDir = WakeWordModelManager.getModelDirectory(requireContext())
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.voice_wake_model_custom_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val container = android.widget.LinearLayout(requireContext()).apply {
            setPadding(64, 16, 64, 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.voice_wake_model_custom_title)
            .setMessage(R.string.voice_wake_model_custom_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) importModelFromPath(path, wakewordsDir)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importModelFromPath(path: String, wakewordsDir: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val stem = withContext(Dispatchers.IO) {
                    val src = File(path)
                    if (!src.exists()) throw IOException("File not found: $path")
                    val s = src.name.removeSuffix(".tflite")
                    wakewordsDir.mkdirs()
                    src.copyTo(File(wakewordsDir, "$s.tflite"), overwrite = true)
                    val jsonSrc = File(src.parent, "$s.json")
                    if (jsonSrc.exists()) jsonSrc.copyTo(File(wakewordsDir, "$s.json"), overwrite = true)
                    s
                }
                selectedModelName = stem
                val installed = WakeWordModelManager.getInstalledModels(wakewordsDir)
                rebuildModelList(installed,
                    modelList.filterIsInstance<WakeWordModel.Downloadable>(),
                    modelList.filterIsInstance<WakeWordModel.Experimental>())
                updateWakeModelStatus()
                Toast.makeText(requireContext(), getString(R.string.voice_wake_model_imported, stem), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("SettingsFragment", "Custom import failed", e)
                if (isAdded) Toast.makeText(requireContext(), getString(R.string.voice_wake_model_import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupButtonRelaySpinners(b: SettingsPageControlsBinding, buttonCount: Int, relayCount: Int) {
        val optionCount = relayCount + 1
        val adapter = relayOptionsAdapter(relayCount)

        val buttonLayouts = listOf(b.buttonRelayMap0Layout, b.buttonRelayMap1Layout, b.buttonRelayMap2Layout, b.buttonRelayMap3Layout)
        val buttonLabels  = listOf(b.buttonRelayMap0Label,  b.buttonRelayMap1Label,  b.buttonRelayMap2Label,  b.buttonRelayMap3Label)
        val buttonSpinners = listOf(b.buttonRelayMap0, b.buttonRelayMap1, b.buttonRelayMap2, b.buttonRelayMap3)

        // the rows of missing buttons are hidden by their requires rule
        for (i in 0 until buttonCount.coerceAtMost(buttonLayouts.size)) {
            buttonLabels[i].text = getString(R.string.button_relay_button_label, i)
            buttonSpinners[i].adapter = adapter
            // stored value -1 is none position 0 then relay 0 at position 1 and so on
            val storedRelay = mSharedPreferences.getInt(String.format(Locale.US, SP_BUTTON_RELAY_MAP_FORMAT, i), -1)
            buttonSpinners[i].setSelection((storedRelay + 1).coerceIn(0, optionCount - 1))
        }

        if (buttonCount > buttonLayouts.size) {
            Log.w("SettingsFragment", "Device has $buttonCount buttons but UI supports only ${buttonLayouts.size}")
            Toast.makeText(requireContext(), "Only the first ${buttonLayouts.size} buttons can be configured here.", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveSettings() {
        mSharedPreferences.edit {
            // only pages that were opened write anything so unopened ones keep their stored values
            binders.forEach { it.saveTo(this) }

            // home assistant may have set a model meanwhile so only a pick made here is written
            if (audioPage != null && selectedModelName != storedModelName) {
                putString(SP_VOICE_WAKE_MODEL_NAME, selectedModelName)
                storedModelName = selectedModelName
            }

            val sensors = sensorsPage
            if (sensors != null && zonesLoaded) {
                putString(SP_DYNAMIC_TEMP_OFFSET_ZONE, sensors.dynamicTempOffsetZone.selectedItem?.toString() ?: "")
            }

            controlsPage?.let { c ->
                // button-to-relay mapping
                if (device.buttons > 0 && device.relays > 0) {
                    val spinners = listOf(c.buttonRelayMap0, c.buttonRelayMap1, c.buttonRelayMap2, c.buttonRelayMap3)
                    for (i in 0 until device.buttons.coerceAtMost(4))
                        putInt(String.format(Locale.US, SP_BUTTON_RELAY_MAP_FORMAT, i), spinners[i].selectedItemPosition - 1)
                }

                // sw input relay target spinner position 0 is none = -1
                if (device.inputs > 0) {
                    putInt(String.format(Locale.US, SP_SW_INPUT_RELAY_MAP_FORMAT, 0), c.swInputRelay.selectedItemPosition - 1)
                }
            }
        }

        // http server lifecycle is owned by the application settings receiver
        // which reacts to the broadcast below; starting it here as well raced
        // that receiver and crashed on rebind failures
        LocalBroadcastManager.getInstance(ShellyElevateApplication.mApplicationContext).sendBroadcast(Intent(INTENT_SETTINGS_CHANGED))
        Toast.makeText(requireContext(), getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun getLocalIpAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (e: SocketException) {
        // interface listing can fail transiently eg right after wifi reconnects
        Log.w("SettingsFragment", "Cannot list network interfaces", e)
        null
    }

    fun getScreenSaverSpinnerAdapter(): ArrayAdapter<String?> {
        val adapter = ArrayAdapter<String?>(requireContext(), android.R.layout.simple_spinner_item)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        for (screenSaver in ScreenSaverManager.getAvailableScreenSavers()) adapter.add(screenSaver.getName())
        return adapter
    }

    fun getSleepOptimizationSpinnerAdapter(): ArrayAdapter<CharSequence> {
        val adapter = ArrayAdapter.createFromResource(requireContext(), R.array.sleep_optimization_levels, android.R.layout.simple_spinner_item)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    fun getSwInputModeSpinnerAdapter(): ArrayAdapter<CharSequence> {
        val adapter = ArrayAdapter.createFromResource(requireContext(), R.array.sw_input_modes, android.R.layout.simple_spinner_item)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    private fun updateWakeModelStatus() {
        val b = audioPage ?: return
        val manager = mVoiceEngine ?: return
        val statusText = when (manager.wakeModelStatus) {
            WakeWordDetector.ModelStatus.LOADED         -> getString(R.string.voice_wake_model_status_loaded)
            WakeWordDetector.ModelStatus.FILE_NOT_FOUND -> getString(R.string.voice_wake_model_status_not_found)
            WakeWordDetector.ModelStatus.LOAD_ERROR     -> getString(R.string.voice_wake_model_status_error)
            WakeWordDetector.ModelStatus.NOT_LOADED     -> getString(R.string.voice_wake_model_status_none)
        }
        val loadedName = manager.loadedModelName
        val display = if (loadedName.isNotEmpty()) "$loadedName ($statusText)" else statusText
        b.voiceWakeModelStatus.text = getString(R.string.voice_wake_model_status, display)
        b.voiceWakeModelPath.text   = getString(R.string.voice_wake_model_path, manager.wakeModelDirectory)
    }

    companion object {
        const val SCREEN_SAVER_DEFAULT_DELAY = 45
        const val PROXIMITY_KEEP_AWAKE_DEFAULT_SECONDS = 30
        const val MQTT_DEFAULT_PORT = 1883
        private const val PAGE_FADE_MS = 120L
    }
}
