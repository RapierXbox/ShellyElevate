package me.rapierxbox.shellyelevatev2.api

import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.databinding.SettingsPageHomeAssistantBinding
import java.util.concurrent.Executors

// pairing status and the paired controllers with a remove button on the home assistant page
class IntegrationSettingsSection(
    private val fragment: Fragment,
    private val b: SettingsPageHomeAssistantBinding,
) : DefaultLifecycleObserver {

    private val store = ClientTokenStore.get(fragment.requireContext())
    private val handler = Handler(Looper.getMainLooper())
    private val listener = ClientTokenStore.Listener { handler.post { render() } }

    init {
        fragment.viewLifecycleOwner.lifecycle.addObserver(this)
        store.addListener(listener)
        render()
        // the keystore can be slow so the fingerprint is read off the main thread
        Executors.newSingleThreadExecutor().apply {
            execute {
                val fingerprint = try {
                    TlsIdentity.get().fingerprint()
                } catch (_: Exception) {
                    null
                }
                handler.post {
                    if (fragment.view == null) return@post
                    b.integrationFingerprint.isVisible = fingerprint != null
                    if (fingerprint != null) {
                        // grouped so it can be compared with home assistant by eye
                        b.integrationFingerprint.text = fragment.getString(R.string.integration_fingerprint,
                            fingerprint.chunked(4).joinToString(" "))
                    }
                }
            }
            shutdown()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        store.removeListener(listener)
        handler.removeCallbacksAndMessages(null)
    }

    private fun render() {
        if (fragment.view == null) return
        val clients = store.list()
        b.integrationStatus.text = when {
            !ApiManager.isRunning() && clients.isEmpty() -> fragment.getString(R.string.integration_api_down)
            clients.isEmpty() -> fragment.getString(R.string.integration_not_paired)
            else -> fragment.getString(R.string.integration_paired)
        }
        val container = b.integrationClients
        container.removeAllViews()
        val context = fragment.requireContext()
        for (client in clients) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val seen = if (client.lastSeen > 0) {
                DateUtils.getRelativeTimeSpanString(client.lastSeen, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString()
            } else {
                "-"
            }
            row.addView(TextView(context).apply {
                text = fragment.getString(R.string.integration_client_row, client.name, seen)
                textSize = 16f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = fragment.getString(R.string.integration_revoke)
                setOnClickListener { confirmRevoke(client) }
            })
            container.addView(row)
        }
    }

    private fun confirmRevoke(client: ClientTokenStore.Client) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.integration_revoke_title, client.name))
            .setMessage(R.string.integration_revoke_message)
            .setPositiveButton(R.string.integration_revoke) { _, _ -> store.revoke(client.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
