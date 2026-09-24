package me.rapierxbox.shellyelevatev2.screensavers.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.core.view.isVisible
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import me.rapierxbox.shellyelevatev2.BuildConfig
import me.rapierxbox.shellyelevatev2.Constants.INTENT_END_SCREENSAVER
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.databinding.DigitalClockAndDateScreenSaverBinding
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
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver(timeTickReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(endScreenSaverReceiver)
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
