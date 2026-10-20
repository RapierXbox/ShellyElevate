package me.rapierxbox.shellyelevatev2.screensavers.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.core.view.isVisible
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import me.rapierxbox.shellyelevatev2.BuildConfig
import me.rapierxbox.shellyelevatev2.Constants.INTENT_ALARM_STATE_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.INTENT_END_SCREENSAVER
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_ALARM_ENABLED
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.databinding.DigitalClockAndDateScreenSaverBinding
import me.rapierxbox.shellyelevatev2.mqtt.AlarmState
import java.text.SimpleDateFormat
import java.util.Date

class DigitalClockAndDateScreenSaverActivity : Activity() {
    private var binding: DigitalClockAndDateScreenSaverBinding? = null

    private val timeFormatter = SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT)
    private val dateFormatter = SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM)

    private var showDate = false

    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateTime()
        }
    }

    private val endScreenSaverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    private val alarmStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateAlarmIcon()
        }
    }

    private fun updateAlarmIcon() {
        val b = binding ?: return
        val state = AlarmState.get()
        // the view alpha dims the colors further, disarmed stays plain white
        val (icon, color) = when {
            !ShellyElevateApplication.mSharedPreferences.getBoolean(SP_SCREEN_SAVER_ALARM_ENABLED, false) -> 0 to 0
            state == "disarmed" -> R.drawable.ic_alarm_disarmed to Color.WHITE
            state == "armed_home" -> R.drawable.ic_alarm_home to 0xFF4CAF50.toInt()
            state == "armed_night" -> R.drawable.ic_alarm_night to 0xFF7C6BFF.toInt()
            state.startsWith("armed") || state == "triggered" || state == "arming" || state == "pending" ->
                R.drawable.ic_alarm_away to 0xFFF44336.toInt()
            else -> 0 to 0
        }
        b.alarmStatusIcon.isVisible = icon != 0
        if (icon != 0) {
            b.alarmStatusIcon.setImageResource(icon)
            b.alarmStatusIcon.setColorFilter(color)
        }
    }

    private fun updateTime() {
        val b = binding ?: return
        val now = Date()
        b.clockText.text = timeFormatter.format(now)

        if (showDate)
            b.dateText.text = dateFormatter.format(now)
    }

    @SuppressLint("ClickableViewAccessibility", "UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showDate = intent.getBooleanExtra("date", false)

        val binding = DigitalClockAndDateScreenSaverBinding.inflate(layoutInflater)
        this.binding = binding
        setContentView(binding.root)

        binding.dateText.isVisible = showDate

        updateTime()
        updateAlarmIcon()

        binding.swipeDetectionOverlay.setOnTouchListener { _, event ->
            if (BuildConfig.DEBUG) Log.d(TAG, "Received touch event: $event")
            ShellyElevateApplication.mSwipeHelper?.onTouchEvent(event)
            mScreenSaverManager.onTouchEvent(event)
            true
        }

        registerReceiver(timeTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        // local broadcast so other apps cannot spoof the end intent
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(endScreenSaverReceiver, IntentFilter(INTENT_END_SCREENSAVER))
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(alarmStateReceiver, IntentFilter(INTENT_ALARM_STATE_CHANGED))
        // a proximity wake right after the start can send the end before the receiver existed
        if (!mScreenSaverManager.isScreenSaverRunning) finish()
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver(timeTickReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(endScreenSaverReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(alarmStateReceiver)
        binding = null
    }

    // sw terminal edges must keep working while the screensaver holds focus
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (ShellyElevateApplication.mSwInputHandler?.onKeyEvent(event) == true)
                || (ShellyElevateApplication.mButtonHandler?.onKeyEvent(event) == true)
                || super.dispatchKeyEvent(event)

    companion object {
        private const val TAG = "DigitalClockAndDateScreenSaverActivity"
    }
}
