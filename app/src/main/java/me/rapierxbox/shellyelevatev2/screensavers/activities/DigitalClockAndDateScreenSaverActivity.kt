package me.rapierxbox.shellyelevatev2.screensavers.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.view.isVisible
import me.rapierxbox.shellyelevatev2.Constants.INTENT_END_SCREENSAVER
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.databinding.DigitalClockAndDateScreenSaverBinding
import java.text.SimpleDateFormat
import java.util.Date

class DigitalClockAndDateScreenSaverActivity : Activity() {
    private var binding: DigitalClockAndDateScreenSaverBinding ?= null // Declare the binding object

    private val timeFormatter = SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT)
    private val dateFormatter = SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM)

    private var showDate = false

    private val mTimeTickBroadCastReciver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateTime()
        }
    }

    private val mEndScreenSaverReciever = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    private fun updateTime() {
        val now = Date()
        binding!!.clockText.text = timeFormatter.format(now)

        if (showDate)
            binding!!.dateText.text = dateFormatter.format(now)
    }

    @SuppressLint("ClickableViewAccessibility", "UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showDate = intent.getBooleanExtra("date", false)

        binding = DigitalClockAndDateScreenSaverBinding.inflate(layoutInflater) // Inflate the binding
        setContentView(binding!!.root) // Set the content view using binding.root

        binding!!.dateText.isVisible = showDate

        updateTime()

        binding!!.swipeDetectionOverlay.setOnTouchListener { _, event ->
            Log.d("DigitalClockAndDateScreenSaverActivity", "Received touch event: $event")
            ShellyElevateApplication.mSwipeHelper?.onTouchEvent(event)
            mScreenSaverManager.onTouchEvent(event)

            true
        }

        registerReceiver(mTimeTickBroadCastReciver, IntentFilter(Intent.ACTION_TIME_TICK))
        registerReceiver(mEndScreenSaverReciever, IntentFilter(INTENT_END_SCREENSAVER))
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver(mTimeTickBroadCastReciver)
        unregisterReceiver(mEndScreenSaverReciever)
        binding = null
    }
}