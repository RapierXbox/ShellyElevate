package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.media.AudioManager
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
import kotlin.math.roundToInt
import me.rapierxbox.shellyelevatev2.Constants.*
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.*
import me.rapierxbox.shellyelevatev2.voice.WakeWordDetector
import me.rapierxbox.shellyelevatev2.databinding.SettingsFragmentBinding
import me.rapierxbox.shellyelevatev2.helper.ScreenManager.DEFAULT_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.helper.ScreenManager.MIN_BRIGHTNESS_DEFAULT
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManager
import java.io.IOException
import java.net.NetworkInterface
import java.util.Locale
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

    private val okHttpClient by lazy {
        //for now trust all certs (hmmm should be fine)
        // android 7 doesnt seem to include sectigo which github uses
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL").apply { init(null, trustAll, SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    private var modelList: MutableList<WakeWordModel> = mutableListOf()
    private var selectedModelName: String = ""
    private var downloadJob: Job? = null

    override fun onDestroyView() {
        super.onDestroyView()
        downloadJob?.cancel()
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
                        try { Runtime.getRuntime().exec("reboot") }
                        catch (e: IOException) { Log.e("SettingsActivity", "Error rebooting:", e) }
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
        setupModelChooser()
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
            "shellyelevate-" + UUID.randomUUID().toString().replace("-".toRegex(), "").substring(2, 6)))

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

        // Volume
        val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSetting.value = (curVol.toFloat() / maxVol * 100f).coerceIn(0f, 100f).roundToInt().toFloat()

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

        // Voice Assistant
        binding.voiceAssistantEnabled.isChecked = mSharedPreferences.getBoolean(SP_VOICE_ASSISTANT_ENABLED, false)
        binding.voiceAssistantToken.setText(mSharedPreferences.getString(SP_VOICE_ASSISTANT_TOKEN, ""))
        binding.voiceAssistantPipelineId.setText(mSharedPreferences.getString(SP_VOICE_ASSISTANT_PIPELINE_ID, ""))
        binding.voiceAssistantMaxSeconds.setText(mSharedPreferences.getInt(SP_VOICE_ASSISTANT_MAX_RECORD_SECONDS, 10).toString())
        binding.voiceWakeEnabled.isChecked = mSharedPreferences.getBoolean(SP_VOICE_WAKE_ENABLED, true)
        binding.voiceWakeExperimentalModels.isChecked = mSharedPreferences.getBoolean(SP_VOICE_WAKE_EXPERIMENTAL_MODELS, false)
        selectedModelName = mSharedPreferences.getString(SP_VOICE_WAKE_MODEL_NAME, "") ?: ""
        // Model label and list populated by setupModelChooser() called from onViewCreated
        binding.voiceWakeModelLayout.isVisible = binding.voiceWakeEnabled.isChecked
        binding.voiceWakeSensitivity.value = mSharedPreferences.getInt(SP_VOICE_WAKE_SENSITIVITY, 50).toFloat()
        binding.voiceWakeCooldown.value    = mSharedPreferences.getInt(SP_VOICE_WAKE_COOLDOWN_SEC, 5).toFloat()
        binding.voiceWakeSoundEnabled.isChecked = mSharedPreferences.getBoolean(SP_VOICE_WAKE_SOUND_ENABLED, true)
        binding.voiceScoreBarEnabled.isChecked = mSharedPreferences.getBoolean(SP_VOICE_SCORE_BAR_ENABLED, false)
        binding.voiceAssistantLayout.isVisible = binding.voiceAssistantEnabled.isChecked
        updateWakeModelStatus()

        // Bluetooth Proxy
        binding.bluetoothProxyEnabled.isChecked = mSharedPreferences.getBoolean(SP_BLUETOOTH_PROXY_ENABLED, false)
        binding.bluetoothProxyName.setText(mSharedPreferences.getString(SP_BLUETOOTH_PROXY_NAME, "ShellyElevate"))
        binding.bluetoothProxyLayout.isVisible = binding.bluetoothProxyEnabled.isChecked
        binding.bluetoothProxyAddress.text = getString(R.string.bt_proxy_address, getLocalIpAddress())

        mSharedPreferences.edit { putBoolean("settingEverShown", true) }
    }

    private fun setupModelChooser() {
        val wakewordsDir = File(requireContext().filesDir, "wakewords")

        // Build list from disk immediately, then merge GitHub models in background
        rebuildModelList(WakeWordModelManager.getInstalledModels(wakewordsDir), emptyList(), emptyList())
        fetchAndMergeRemoteModels(wakewordsDir)

        binding.voiceWakeChooseModel.setOnClickListener { showModelPickerDialog() }

        binding.voiceWakeModelRefresh.setOnClickListener {
            fetchAndMergeRemoteModels(wakewordsDir)
        }

        binding.voiceWakeExperimentalModels.setOnCheckedChangeListener { _, _ ->
            fetchAndMergeRemoteModels(wakewordsDir)
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
        if (_binding == null) return
        binding.voiceWakeModelLabel.text = selectedModelName.ifEmpty { getString(R.string.voice_wake_model_none_selected) }
    }

    private fun fetchAndMergeRemoteModels(wakewordsDir: File) {
        val showExperimental = binding.voiceWakeExperimentalModels.isChecked
        binding.voiceWakeModelRefresh.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (official, experimental) = withContext(Dispatchers.IO) {
                    val off = WakeWordModelManager.fetchOfficialModels(okHttpClient)
                    val exp = if (showExperimental) WakeWordModelManager.fetchExperimentalModels(okHttpClient) else emptyList()
                    Pair(off, exp)
                }
                val installed = WakeWordModelManager.getInstalledModels(wakewordsDir)
                rebuildModelList(installed, official, experimental)
            } catch (e: Exception) {
                Log.w("SettingsFragment", "GitHub fetch failed: ${e.message}")
                if (isAdded) Toast.makeText(requireContext(), getString(R.string.voice_wake_model_fetch_failed), Toast.LENGTH_SHORT).show()
            } finally {
                if (_binding != null) binding.voiceWakeModelRefresh.isEnabled = true
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
        is WakeWordModel.Installed    -> "\u2713  ${model.displayName}"
        is WakeWordModel.Downloadable -> "\u2B07  ${model.displayName}"
        is WakeWordModel.Experimental -> "\u26A1  ${model.displayName}"
        else                          -> model.displayName  // Custom
    }

    private fun onModelSelected(model: WakeWordModel) {
        when (model) {
            is WakeWordModel.Installed -> {
                selectedModelName = model.name
                updateModelLabel()
            }
            is WakeWordModel.Downloadable -> startModelDownload(model.name, model.tfliteUrl, model.jsonUrl)
            is WakeWordModel.Experimental -> startModelDownload(model.name, model.tfliteUrl, model.jsonUrl)
            else -> showCustomModelPathDialog()  // Custom
        }
    }

    private fun startModelDownload(name: String, tfliteUrl: String, jsonUrl: String) {
        downloadJob?.cancel()
        val wakewordsDir = File(requireContext().filesDir, "wakewords")
        val mainHandler = Handler(Looper.getMainLooper())

        binding.voiceWakeDownloadProgress.visibility = View.VISIBLE
        binding.voiceWakeProgressBar.progress = 0
        binding.voiceWakeProgressText.text = "0%"

        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val destTflite = File(wakewordsDir, "$name.tflite")
            val destJson   = File(wakewordsDir, "$name.json")
            val hasJson = jsonUrl.isNotEmpty()
            try {
                withContext(Dispatchers.IO) {
                    WakeWordModelManager.downloadFile(okHttpClient, tfliteUrl, destTflite) { pct ->
                        val overall = if (hasJson) pct / 2 else pct
                        mainHandler.post { if (_binding != null) {
                            binding.voiceWakeProgressBar.progress = overall
                            binding.voiceWakeProgressText.text = "$overall%"
                        }}
                    }
                    if (hasJson) {
                        WakeWordModelManager.downloadFile(okHttpClient, jsonUrl, destJson) { pct ->
                            val overall = 50 + pct / 2
                            mainHandler.post { if (_binding != null) {
                                binding.voiceWakeProgressBar.progress = overall
                                binding.voiceWakeProgressText.text = "$overall%"
                            }}
                        }
                    }
                }
                selectedModelName = name
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
                destTflite.delete(); destJson.delete()
                throw e
            } catch (e: Exception) {
                Log.e("SettingsFragment", "Download failed for $name", e)
                destTflite.delete(); destJson.delete()
                if (isAdded) Toast.makeText(requireContext(), getString(R.string.voice_wake_model_download_failed), Toast.LENGTH_SHORT).show()
            } finally {
                if (_binding != null) binding.voiceWakeDownloadProgress.visibility = View.GONE
            }
        }
    }

    private fun showCustomModelPathDialog() {
        val wakewordsDir = File(requireContext().filesDir, "wakewords")
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

    // ─────────────── Listeners ───────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        binding.findURLButton.setOnClickListener {
            ServiceHelper.getHAURL(requireContext().applicationContext) { url ->
                requireActivity().runOnUiThread { binding.webviewURL.setText(url) }
            }
        }

        binding.brightnessSetting.addOnChangeListener { _, value, _ ->
            mDeviceHelper.setScreenBrightness(value.toInt())
        }

        var lastAppliedVol = -1
        binding.volumeSetting.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val target = (value / 100f * maxVol).roundToInt().coerceIn(0, maxVol)
                if (target != lastAppliedVol) {
                    lastAppliedVol = target
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_PLAY_SOUND)
                }
            }
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
            if (actionId == EditorInfo.IME_ACTION_DONE &&
                (binding.screenSaverDelay.text.toString().toIntOrNull() ?: 5) < 5) {
                binding.screenSaverDelay.setText("5")
                Toast.makeText(requireContext(), R.string.delay_must_be_bigger_then_5s, Toast.LENGTH_SHORT).show()
            }
            false
        }

        binding.proximityKeepAwakeSeconds.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val v = binding.proximityKeepAwakeSeconds.text.toString().toIntOrNull() ?: PROXIMITY_KEEP_AWAKE_DEFAULT_SECONDS
                if (v < 0) {
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

        binding.voiceAssistantEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.voiceAssistantLayout.isVisible = isChecked
            if (isChecked && binding.webviewURL.text.toString().isEmpty()) {
                Toast.makeText(requireContext(), R.string.voice_requires_url, Toast.LENGTH_LONG).show()
                binding.voiceAssistantEnabled.isChecked = false
                binding.voiceAssistantLayout.isVisible = false
            }
        }

        binding.voiceWakeEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.voiceWakeModelLayout.isVisible = isChecked
        }

        binding.bluetoothProxyEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.bluetoothProxyLayout.isVisible = isChecked
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
        for (i in 0 until relayCount) options.add(getString(R.string.button_relay_relay_label, i))
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val buttonLayouts = listOf(binding.buttonRelayMap0Layout, binding.buttonRelayMap1Layout, binding.buttonRelayMap2Layout, binding.buttonRelayMap3Layout)
        val buttonLabels  = listOf(binding.buttonRelayMap0Label,  binding.buttonRelayMap1Label,  binding.buttonRelayMap2Label,  binding.buttonRelayMap3Label)
        val buttonSpinners = listOf(binding.buttonRelayMap0, binding.buttonRelayMap1, binding.buttonRelayMap2, binding.buttonRelayMap3)

        for (i in buttonLayouts.indices) {
            val visible = i < buttonCount
            buttonLayouts[i].isVisible = visible
            if (visible) {
                buttonLabels[i].text = getString(R.string.button_relay_button_label, i)
                buttonSpinners[i].adapter = adapter
                // Stored value: -1 = None (position 0), 0 = Relay 0 (position 1), etc.
                val storedRelay = mSharedPreferences.getInt(String.format(Locale.US, SP_BUTTON_RELAY_MAP_FORMAT, i), -1)
                buttonSpinners[i].setSelection((storedRelay + 1).coerceIn(0, options.size - 1))
            }
        }

        if (buttonCount > buttonLayouts.size) {
            Log.w("SettingsFragment", "Device has $buttonCount buttons but UI supports only ${buttonLayouts.size}")
            Toast.makeText(requireContext(), "Only the first ${buttonLayouts.size} buttons can be configured here.", Toast.LENGTH_LONG).show()
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
                val spinners = listOf(binding.buttonRelayMap0, binding.buttonRelayMap1, binding.buttonRelayMap2, binding.buttonRelayMap3)
                for (i in 0 until device.buttons.coerceAtMost(4))
                    putInt(String.format(Locale.US, SP_BUTTON_RELAY_MAP_FORMAT, i), spinners[i].selectedItemPosition - 1)
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
            putInt(SP_PROXIMITY_KEEP_AWAKE_SECONDS, (binding.proximityKeepAwakeSeconds.text.toString().toIntOrNull() ?: PROXIMITY_KEEP_AWAKE_DEFAULT_SECONDS).coerceAtLeast(0))
            putInt(SP_SCREEN_SAVER_MIN_BRIGHTNESS, binding.screensaverMinBrightness.value.toInt())

            //Http Server
            putBoolean(SP_HTTP_SERVER_ENABLED, binding.httpServerEnabled.isChecked)

            // Voice Assistant
            putBoolean(SP_VOICE_ASSISTANT_ENABLED, binding.voiceAssistantEnabled.isChecked)
            putString(SP_VOICE_ASSISTANT_TOKEN, binding.voiceAssistantToken.text.toString())
            putString(SP_VOICE_ASSISTANT_PIPELINE_ID, binding.voiceAssistantPipelineId.text.toString())
            putInt(SP_VOICE_ASSISTANT_MAX_RECORD_SECONDS, binding.voiceAssistantMaxSeconds.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10)
            putBoolean(SP_VOICE_WAKE_ENABLED, binding.voiceWakeEnabled.isChecked)
            putBoolean(SP_VOICE_WAKE_EXPERIMENTAL_MODELS, binding.voiceWakeExperimentalModels.isChecked)
            putString(SP_VOICE_WAKE_MODEL_NAME, selectedModelName)
            putInt(SP_VOICE_WAKE_SENSITIVITY, binding.voiceWakeSensitivity.value.toInt())
            putInt(SP_VOICE_WAKE_COOLDOWN_SEC, binding.voiceWakeCooldown.value.toInt())
            putBoolean(SP_VOICE_WAKE_SOUND_ENABLED, binding.voiceWakeSoundEnabled.isChecked)
            putBoolean(SP_VOICE_SCORE_BAR_ENABLED, binding.voiceScoreBarEnabled.isChecked)

            // Bluetooth Proxy
            putBoolean(SP_BLUETOOTH_PROXY_ENABLED, binding.bluetoothProxyEnabled.isChecked)
            putString(SP_BLUETOOTH_PROXY_NAME, binding.bluetoothProxyName.text.toString().trim())
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
        for (screenSaver in ScreenSaverManager.getAvailableScreenSavers()) adapter.add(screenSaver.getName())
        return adapter
    }

    private fun updateWakeModelStatus() {
        val manager = mVoiceAssistantManager ?: return
        val statusText = when (manager.wakeModelStatus) {
            WakeWordDetector.ModelStatus.LOADED         -> getString(R.string.voice_wake_model_status_loaded)
            WakeWordDetector.ModelStatus.FILE_NOT_FOUND -> getString(R.string.voice_wake_model_status_not_found)
            WakeWordDetector.ModelStatus.LOAD_ERROR     -> getString(R.string.voice_wake_model_status_error)
            WakeWordDetector.ModelStatus.NOT_LOADED     -> getString(R.string.voice_wake_model_status_none)
        }
        val loadedName = manager.loadedModelName
        val display = if (loadedName.isNotEmpty()) "$loadedName ($statusText)" else statusText
        binding.voiceWakeModelStatus.text = getString(R.string.voice_wake_model_status, display)
        binding.voiceWakeModelPath.text   = getString(R.string.voice_wake_model_path, manager.wakeModelDirectory)
    }

    companion object {
        const val SCREEN_SAVER_DEFAULT_DELAY = 45
        const val PROXIMITY_KEEP_AWAKE_DEFAULT_SECONDS = 30
        const val MQTT_DEFAULT_PORT = 1883
    }
}
