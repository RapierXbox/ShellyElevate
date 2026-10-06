package me.rapierxbox.shellyelevatev2.deprecated

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import me.rapierxbox.shellyelevatev2.BuildConfig
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.SP_DEPRECATED_MIGRATION_PROMPTED
import me.rapierxbox.shellyelevatev2.Constants.SP_HA_VOICE_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_ASSISTANT_ENABLED
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.api.ClientTokenStore

/**
 * offers once per app version to move the token voice satellite to the paired integration
 * nothing changes without a tap. the esphome proxy only gets the banner on the deprecated page
 * @deprecated deleted together with the token satellite
 */
@Deprecated("replaced by the Shelly Elevate Home Assistant integration")
class MigrationPrompt : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        markPrompted()
        AlertDialog.Builder(this)
            .setMessage(R.string.deprecated_migration_voice)
            .setPositiveButton(R.string.deprecated_migration_switch) { _, _ ->
                mSharedPreferences.edit {
                    putBoolean(SP_VOICE_ASSISTANT_ENABLED, false)
                    putBoolean(SP_HA_VOICE_ENABLED, true)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(INTENT_SETTINGS_CHANGED))
            }
            .setNegativeButton(R.string.deprecated_migration_later, null)
            .setOnDismissListener { finish() }
            .show()
    }

    companion object {
        // shortly after start so the dashboard is up first
        private const val START_DELAY_MS = 30_000L

        @JvmStatic
        fun install(context: Context) {
            val app = context.applicationContext
            ClientTokenStore.get(app).addListener { Handler(Looper.getMainLooper()).post { maybeShow(app) } }
            Handler(Looper.getMainLooper()).postDelayed({ maybeShow(app) }, START_DELAY_MS)
        }

        private fun maybeShow(context: Context) {
            if (!mSharedPreferences.getBoolean(SP_VOICE_ASSISTANT_ENABLED, false)) return
            if (mSharedPreferences.getInt(SP_DEPRECATED_MIGRATION_PROMPTED, 0) == BuildConfig.VERSION_CODE) return
            if (!ClientTokenStore.get(context).hasClients()) return
            markPrompted()
            context.startActivity(Intent(context, MigrationPrompt::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }

        private fun markPrompted() {
            mSharedPreferences.edit { putInt(SP_DEPRECATED_MIGRATION_PROMPTED, BuildConfig.VERSION_CODE) }
        }
    }
}
