package me.rapierxbox.shellyelevatev2

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import me.rapierxbox.shellyelevatev2.databinding.SettingsWifiSectionBinding
import me.rapierxbox.shellyelevatev2.databinding.WifiNetworkDialogBinding
import me.rapierxbox.shellyelevatev2.databinding.WifiNetworkItemBinding
import me.rapierxbox.shellyelevatev2.helper.WifiNetworkManager
import me.rapierxbox.shellyelevatev2.helper.WifiNetworkManager.Security

// settings section that lists nearby wifi networks and connects to them
// lives as long as the fragment view and hooks itself into its lifecycle
class WifiSettingsSection(
    private val fragment: Fragment,
    private val binding: SettingsWifiSectionBinding
) : DefaultLifecycleObserver {

    private val appContext: Context = fragment.requireContext().applicationContext
    private val manager = WifiNetworkManager(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var scanning = false
    private var statusIsScan = false
    private var hadLocationPermission = false
    private var destroyed = false

    private val scanTimeout = Runnable { finishScan() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> finishScan()
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    // a scan that had to turn wifi on first continues here
                    if (scanning && manager.isWifiEnabled) manager.startScan()
                    refresh()
                }
                else -> refresh()
            }
        }
    }

    init {
        binding.wifiScanButton.setOnClickListener { startScan() }
        binding.wifiAddHiddenButton.setOnClickListener { showCredentialsDialog(null) }
        fragment.viewLifecycleOwner.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!manager.isAvailable) {
            binding.wifiCurrent.text = str(R.string.wifi_unavailable)
            binding.wifiScanButton.isEnabled = false
            binding.wifiAddHiddenButton.isEnabled = false
            return
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        hadLocationPermission = manager.hasLocationPermission()
        refresh()
        if (hadLocationPermission) startScan()
    }

    override fun onResume(owner: LifecycleOwner) {
        // the permission prompt is its own activity so a grant shows up here
        val has = manager.hasLocationPermission()
        if (has && !hadLocationPermission) startScan()
        hadLocationPermission = has
        if (manager.isAvailable) updateHint()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (receiverRegistered) {
            try { appContext.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
            receiverRegistered = false
        }
        handler.removeCallbacks(scanTimeout)
        scanning = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        // a running attempt still saves or rolls back on its own
        manager.detachListener()
    }

    private fun str(@StringRes res: Int, vararg args: Any): String = appContext.getString(res, *args)

    private fun toast(text: String) = Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()

    private fun refresh() {
        if (destroyed) return
        updateCurrent()
        updateHint()
        renderNetworks(manager.networks)
        updateBusy()
    }

    private fun updateCurrent() {
        val cur = manager.current
        binding.wifiCurrent.text = when {
            !manager.isWifiEnabled -> str(R.string.wifi_off)
            cur == null -> str(R.string.wifi_not_connected)
            else -> str(R.string.wifi_current, cur.ssid, listOfNotNull(
                str(R.string.wifi_dbm, cur.rssi),
                bandLabel(cur.frequency),
                cur.ip ?: str(R.string.wifi_no_ip)
            ).joinToString(" · "))
        }
    }

    private fun updateHint() {
        when {
            !manager.hasLocationPermission() ->
                showHint(R.string.wifi_hint_permission, R.string.wifi_grant_permission) { requestLocationPermission() }
            !manager.isLocationEnabled ->
                showHint(R.string.wifi_hint_location_off, R.string.wifi_open_location_settings) { openLocationSettings() }
            else -> {
                binding.wifiHint.isVisible = false
                binding.wifiHintButton.isVisible = false
            }
        }
    }

    private fun showHint(@StringRes text: Int, @StringRes button: Int, action: () -> Unit) {
        binding.wifiHint.text = str(text)
        binding.wifiHint.isVisible = true
        binding.wifiHintButton.text = str(button)
        binding.wifiHintButton.isVisible = true
        binding.wifiHintButton.setOnClickListener { action() }
    }

    private fun requestLocationPermission() {
        val activity = fragment.activity ?: return
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
    }

    private fun openLocationSettings() {
        try {
            fragment.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            fragment.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun updateBusy() {
        val connecting = manager.isConnecting
        binding.wifiProgress.isVisible = scanning || connecting
        binding.wifiScanButton.isEnabled = !scanning && !connecting
        binding.wifiAddHiddenButton.isEnabled = !connecting
    }

    private fun setStatus(text: String, scan: Boolean = false) {
        binding.wifiStatus.text = text
        binding.wifiStatus.isVisible = true
        statusIsScan = scan
    }

    private fun startScan() {
        if (destroyed || manager.isConnecting) return
        if (!manager.startScan()) {
            setStatus(str(R.string.wifi_scan_failed))
            return
        }
        scanning = true
        setStatus(str(R.string.wifi_scanning), scan = true)
        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        updateBusy()
    }

    private fun finishScan() {
        if (destroyed) return
        if (scanning) {
            scanning = false
            handler.removeCallbacks(scanTimeout)
            if (statusIsScan) binding.wifiStatus.isVisible = false
        }
        refresh()
    }

    private fun renderNetworks(networks: List<WifiNetworkManager.Network>) {
        val list = binding.wifiNetworkList
        list.removeAllViews()
        val inflater = LayoutInflater.from(list.context)
        for (n in networks) {
            val row = WifiNetworkItemBinding.inflate(inflater, list, false)
            row.wifiItemSsid.text = n.ssid
            row.wifiItemSsid.setTypeface(null, if (n.connected) Typeface.BOLD else Typeface.NORMAL)
            row.wifiItemDetails.text = details(n)
            row.wifiItemSignal.setImageLevel(SIGNAL_CLIP_LEVELS[n.signalLevel().coerceIn(0, SIGNAL_CLIP_LEVELS.size - 1)])
            row.root.alpha = if (n.isSupported || n.isSaved) 1f else 0.5f
            row.root.setOnClickListener { onNetworkClicked(n) }
            list.addView(row.root)
        }
        // without the permission or location the list is empty for another reason shown in the hint
        binding.wifiEmpty.isVisible = networks.isEmpty() && !scanning && manager.isWifiEnabled
                && manager.hasLocationPermission() && manager.isLocationEnabled
    }

    private fun details(n: WifiNetworkManager.Network): String = listOfNotNull(
        when {
            n.connected -> str(R.string.wifi_state_connected)
            n.isSaved -> str(R.string.wifi_state_saved)
            else -> null
        },
        securityLabel(n),
        bandLabel(n.frequency),
        str(R.string.wifi_dbm, n.rssi)
    ).joinToString(" · ")

    private fun securityLabel(n: WifiNetworkManager.Network): String = when (n.security) {
        Security.OPEN -> str(R.string.wifi_security_open)
        Security.WEP -> "WEP"
        Security.PSK -> n.pskLabel()
        Security.EAP -> str(R.string.wifi_security_enterprise)
        else -> str(R.string.wifi_security_unsupported)
    }

    private fun bandLabel(frequency: Int): String? = when (frequency) {
        in 2400..2500 -> str(R.string.wifi_band_24)
        in 4900..5900 -> str(R.string.wifi_band_5)
        else -> null
    }

    private fun switchWarning(): String? = manager.current?.let { str(R.string.wifi_switch_warning, it.ssid) }

    private fun onNetworkClicked(n: WifiNetworkManager.Network) {
        if (manager.isConnecting) {
            toast(str(R.string.wifi_busy))
            return
        }
        when {
            n.connected -> showConnectedDialog(n)
            n.isSaved -> showSavedDialog(n)
            !n.isSupported -> toast(str(R.string.wifi_unsupported_security, n.ssid, securityLabel(n)))
            n.security == Security.OPEN -> confirmSwitch {
                connect(n.ssid) { l -> manager.connectNew(n.ssid, Security.OPEN, "", false, l) }
            }
            else -> showCredentialsDialog(n)
        }
    }

    private fun confirmSwitch(action: () -> Unit) {
        val warning = switchWarning()
        if (warning == null) {
            action()
            return
        }
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.wifi_switch_title)
            .setMessage(warning)
            .setPositiveButton(R.string.wifi_connect) { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showConnectedDialog(n: WifiNetworkManager.Network) {
        val cur = manager.current
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(n.ssid)
            .setMessage(str(R.string.wifi_connected_message, cur?.rssi ?: n.rssi, securityLabel(n),
                cur?.ip ?: str(R.string.wifi_no_ip)))
            .setPositiveButton(R.string.wifi_close, null)
            .setNeutralButton(R.string.wifi_forget) { _, _ ->
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(str(R.string.wifi_forget_connected_title, n.ssid))
                    .setMessage(R.string.wifi_forget_connected_message)
                    .setPositiveButton(R.string.wifi_forget) { _, _ -> forget(n.ssid, n.savedNetworkId) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }

    private fun showSavedDialog(n: WifiNetworkManager.Network) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(n.ssid)
            .setMessage(switchWarning() ?: str(R.string.wifi_saved_message))
            .setPositiveButton(R.string.wifi_connect) { _, _ ->
                connect(n.ssid) { l -> manager.connectSaved(n.savedNetworkId, n.ssid, l) }
            }
            .setNeutralButton(R.string.wifi_forget) { _, _ -> forget(n.ssid, n.savedNetworkId) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun forget(ssid: String, networkId: Int) {
        val msg = when {
            manager.forget(networkId) -> R.string.wifi_forgotten
            !manager.hasOverridePermission() -> R.string.wifi_forget_failed_permission
            else -> R.string.wifi_forget_failed
        }
        toast(str(msg, ssid))
        refresh()
    }

    // password prompt for a scanned network or the full form for a hidden one when network is null
    private fun showCredentialsDialog(network: WifiNetworkManager.Network?) {
        val ctx = fragment.context ?: return
        val hidden = network == null
        val d = WifiNetworkDialogBinding.inflate(LayoutInflater.from(ctx))
        d.wifiDialogSsidLayout.isVisible = hidden
        d.wifiDialogSecurityLayout.isVisible = hidden
        if (hidden) {
            d.wifiDialogSecurity.adapter = ArrayAdapter.createFromResource(ctx, R.array.wifi_security_options,
                android.R.layout.simple_spinner_item).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            d.wifiDialogSecurity.setSelection(SECURITY_OPTIONS.indexOf(Security.PSK))
            d.wifiDialogSecurity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    d.wifiDialogPasswordLayout.isVisible = SECURITY_OPTIONS[position] != Security.OPEN
                    d.wifiDialogPasswordLayout.error = null
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(network?.ssid ?: str(R.string.wifi_add_hidden))
            .setMessage(switchWarning())
            .setView(d.root)
            .setPositiveButton(R.string.wifi_connect, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // validate before closing so a typo does not throw the input away
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ssid = network?.ssid ?: d.wifiDialogSsid.text?.toString().orEmpty()
                val security = network?.security ?: SECURITY_OPTIONS[d.wifiDialogSecurity.selectedItemPosition]
                val password = if (security == Security.OPEN) "" else d.wifiDialogPassword.text?.toString().orEmpty()
                d.wifiDialogSsidLayout.error =
                    if (hidden && !WifiNetworkManager.isValidSsid(ssid)) str(R.string.wifi_ssid_invalid) else null
                d.wifiDialogPasswordLayout.error = when {
                    WifiNetworkManager.isValidPassword(security, password) -> null
                    security == Security.WEP -> str(R.string.wifi_password_invalid_wep)
                    else -> str(R.string.wifi_password_invalid_psk)
                }
                if (d.wifiDialogSsidLayout.error != null || d.wifiDialogPasswordLayout.error != null) return@setOnClickListener
                dialog.dismiss()
                connect(ssid) { l -> manager.connectNew(ssid, security, password, hidden, l) }
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        (if (hidden) d.wifiDialogSsid else d.wifiDialogPassword).requestFocus()
    }

    private fun connect(ssid: String, start: (WifiNetworkManager.ConnectListener) -> Unit) {
        if (destroyed) return
        // a running scan would overwrite the progress text
        scanning = false
        handler.removeCallbacks(scanTimeout)
        setStatus(str(R.string.wifi_connecting, ssid))
        start(object : WifiNetworkManager.ConnectListener {
            override fun onProgress(state: SupplicantState) {
                if (destroyed) return
                val step = stepLabel(state) ?: return
                setStatus(str(R.string.wifi_connecting_step, ssid, step))
            }

            override fun onConnected(connectedSsid: String, ip: String?) {
                if (destroyed) return
                setStatus(str(R.string.wifi_connected_result, connectedSsid, ip ?: str(R.string.wifi_no_ip)))
                refresh()
            }

            override fun onFailed(reason: Int) {
                if (destroyed) return
                val msg = str(when (reason) {
                    WifiNetworkManager.FAIL_AUTH -> R.string.wifi_failed_auth
                    WifiNetworkManager.FAIL_TIMEOUT -> R.string.wifi_failed_timeout
                    else -> R.string.wifi_failed
                }, ssid)
                setStatus(msg)
                toast(msg)
                refresh()
            }
        })
        updateBusy()
    }

    private fun stepLabel(state: SupplicantState): String? = when (state) {
        SupplicantState.SCANNING -> str(R.string.wifi_step_scanning)
        SupplicantState.AUTHENTICATING,
        SupplicantState.ASSOCIATING,
        SupplicantState.ASSOCIATED -> str(R.string.wifi_step_associating)
        SupplicantState.FOUR_WAY_HANDSHAKE,
        SupplicantState.GROUP_HANDSHAKE -> str(R.string.wifi_step_authenticating)
        SupplicantState.COMPLETED -> str(R.string.wifi_step_obtaining_ip)
        else -> null
    }

    companion object {
        private const val REQUEST_LOCATION = 0x5741
        private const val SCAN_TIMEOUT_MS = 15_000L
        // order of R.array.wifi_security_options
        private val SECURITY_OPTIONS = arrayOf(Security.OPEN, Security.WEP, Security.PSK)
        // clip levels that show the signal wedge at roughly 0 30 55 78 and 100 percent
        private val SIGNAL_CLIP_LEVELS = intArrayOf(1500, 3350, 5300, 7050, 10000)
    }
}
