package me.rapierxbox.shellyelevatev2.backbutton

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import me.rapierxbox.shellyelevatev2.R

class BackAccessibilityService : AccessibilityService() {
    private val backReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == getString(R.string.back_intent)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(getString(R.string.back_intent))
        registerReceiver(backReceiver, filter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        unregisterReceiver(backReceiver)
        super.onDestroy()
    }

    companion object {
        fun isAccessibilityEnabled(context: Context): Boolean {
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(context.packageName)
        }
    }
}
