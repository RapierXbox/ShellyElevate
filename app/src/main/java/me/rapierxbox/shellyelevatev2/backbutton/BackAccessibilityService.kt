package me.rapierxbox.shellyelevatev2.backbutton

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.*

class BackAccessibilityService : AccessibilityService() {
    private val backReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_BACK) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(ACTION_BACK)
        registerReceiver(backReceiver, filter)
    }

    @SuppressLint("SwitchIntDef")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            TYPE_TOUCH_INTERACTION_START,
            TYPE_TOUCH_INTERACTION_END,
            TYPE_VIEW_CLICKED,
            TYPE_VIEW_SCROLLED,
            TYPE_VIEW_FOCUSED,
            TYPE_GESTURE_DETECTION_START,
            TYPE_GESTURE_DETECTION_END -> {
                val intent = Intent(ACTION_USER_INTERACTION)
                sendBroadcast(intent)
            }
        }
    }

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

        const val ACTION_USER_INTERACTION = "shellyelevate.ACTION_USER_INTERACTION"
        const val ACTION_BACK = "shellyelevate.ACTION_USER_BACK"
    }

}

