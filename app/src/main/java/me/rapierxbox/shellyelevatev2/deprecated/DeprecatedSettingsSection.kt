package me.rapierxbox.shellyelevatev2.deprecated

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.SP_BLE_SCANNER_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_BLUETOOTH_PROXY_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_BLUETOOTH_PROXY_NAME
import me.rapierxbox.shellyelevatev2.Constants.SP_HA_VOICE_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_ASSISTANT_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_ASSISTANT_PIPELINE_ID
import me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_ASSISTANT_TOKEN
import me.rapierxbox.shellyelevatev2.PrefBinding
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.SettingsSection
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.SwitchPref
import me.rapierxbox.shellyelevatev2.TextPref
import me.rapierxbox.shellyelevatev2.api.ClientTokenStore
import me.rapierxbox.shellyelevatev2.databinding.SettingsPageDeprecatedBinding

/**
 * the deprecated settings page: the esphome proxy and the token voice satellite
 * @deprecated deleted together with the features it configures
 */
@Deprecated("replaced by the Shelly Elevate Home Assistant integration")
class DeprecatedSettingsSection(
    private val fragment: Fragment,
    private val b: SettingsPageDeprecatedBinding,
    // the dashboard url as the settings screen shows it right now
    private val dashboardUrl: () -> String?,
    // switches of the replacements on pages that may be open too
    private val onSwitched: (replacementKey: String) -> Unit,
) : SettingsSection {

    override val bindings: List<PrefBinding> = listOf(
        SwitchPref(b.bluetoothProxyEnabled, SP_BLUETOOTH_PROXY_ENABLED, false),
        TextPref(b.bluetoothProxyName, SP_BLUETOOTH_PROXY_NAME, "ShellyElevate", trim = true),
        SwitchPref(b.voiceAssistantEnabled, SP_VOICE_ASSISTANT_ENABLED, false),
        TextPref(b.voiceAssistantToken, SP_VOICE_ASSISTANT_TOKEN),
        TextPref(b.voiceAssistantPipelineId, SP_VOICE_ASSISTANT_PIPELINE_ID),
    )

    private val paired by lazy { ClientTokenStore.get(fragment.requireContext()).hasClients() }

    override fun onLoaded() {
        b.deprecatedLearnMore.setOnClickListener {
            try {
                fragment.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse(fragment.getString(R.string.deprecated_learn_more_url))))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(fragment.requireContext(), R.string.deprecated_learn_more_url, Toast.LENGTH_LONG).show()
            }
        }

        b.bluetoothProxyEnabled.setOnCheckedChangeListener { _, _ -> refresh() }
        b.voiceAssistantEnabled.setOnCheckedChangeListener { _, isChecked ->
            // the token satellite talks to the dashboard host so it needs a url
            if (isChecked && dashboardUrl().isNullOrEmpty()) {
                Toast.makeText(fragment.requireContext(), R.string.voice_requires_url, Toast.LENGTH_LONG).show()
                b.voiceAssistantEnabled.isChecked = false
            }
            refresh()
        }
        b.switchEsphomeNow.setOnClickListener {
            switchToReplacement(SP_BLUETOOTH_PROXY_ENABLED, SP_BLE_SCANNER_ENABLED)
            b.bluetoothProxyEnabled.isChecked = false
        }
        b.switchSatelliteNow.setOnClickListener {
            switchToReplacement(SP_VOICE_ASSISTANT_ENABLED, SP_HA_VOICE_ENABLED)
            b.voiceAssistantEnabled.isChecked = false
        }
        refresh()
    }

    fun setBluetoothAddress(ip: String?) {
        b.bluetoothProxyAddress.text = fragment.getString(R.string.bt_proxy_address, ip)
    }

    private fun refresh() {
        b.bluetoothProxyLayout.isVisible = b.bluetoothProxyEnabled.isChecked
        b.voiceAssistantLayout.isVisible = b.voiceAssistantEnabled.isChecked
        // switching only makes sense once a controller can take over
        b.switchEsphomeNow.isVisible = paired && b.bluetoothProxyEnabled.isChecked
        b.switchSatelliteNow.isVisible = paired && b.voiceAssistantEnabled.isChecked
        b.deprecatedDuplicateWarning.isVisible = b.bluetoothProxyEnabled.isChecked &&
            mSharedPreferences.getBoolean(SP_BLE_SCANNER_ENABLED, false)
    }

    // writes at once so the switch survives leaving settings without saving
    private fun switchToReplacement(deprecatedKey: String, replacementKey: String) {
        mSharedPreferences.edit {
            putBoolean(deprecatedKey, false)
            putBoolean(replacementKey, true)
        }
        LocalBroadcastManager.getInstance(fragment.requireContext()).sendBroadcast(Intent(INTENT_SETTINGS_CHANGED))
        onSwitched(replacementKey)
        Toast.makeText(fragment.requireContext(), R.string.deprecated_switched, Toast.LENGTH_SHORT).show()
    }
}
