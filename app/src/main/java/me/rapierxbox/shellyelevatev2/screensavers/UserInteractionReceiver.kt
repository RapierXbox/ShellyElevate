package me.rapierxbox.shellyelevatev2.screensavers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.rapierxbox.shellyelevatev2.backbutton.BackAccessibilityService.Companion.ACTION_USER_INTERACTION

class UserInteractionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_USER_INTERACTION) {
            ScreenSaverManagerHolder.getInstance().onTouchEvent()
        }
    }
}
