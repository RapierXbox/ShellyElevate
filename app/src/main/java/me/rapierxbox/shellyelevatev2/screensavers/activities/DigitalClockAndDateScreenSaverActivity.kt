package me.rapierxbox.shellyelevatev2.screensavers.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.view.isVisible
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication
import me.rapierxbox.shellyelevatev2.databinding.DigitalClockAndDateScreenSaverBinding
import me.rapierxbox.shellyelevatev2.screensavers.ScreenSaverManagerHolder
import java.text.SimpleDateFormat
import java.util.Date

class DigitalClockAndDateScreenSaverActivity : Activity() {
    private lateinit var binding: DigitalClockAndDateScreenSaverBinding // Declare the binding object

    private val timeFormatter = SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT)
    private val dateFormatter = SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM)

    private var showDate = false

    private val mTimeTickBroadCastReciver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateTime()
        }
    }

    private fun updateTime() {
        val now = Date()
        binding.clockText.text = timeFormatter.format(now)

        if (showDate)
            binding.dateText.text = dateFormatter.format(now)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showDate = intent.getBooleanExtra("date", false)

        binding = DigitalClockAndDateScreenSaverBinding.inflate(layoutInflater) // Inflate the binding
        setContentView(binding.root) // Set the content view using binding.root

        binding.dateText.isVisible = showDate

        updateTime()

        binding.swipeDetectionOverlay.setOnTouchListener { _, event ->
            ScreenSaverManagerHolder.getInstance().onTouchEvent()
            ShellyElevateApplication.mSwipeHelper.onTouchEvent(event)

            finish()

            return@setOnTouchListener false
        }

        registerReceiver(mTimeTickBroadCastReciver, IntentFilter(Intent.ACTION_TIME_TICK))
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver(mTimeTickBroadCastReciver)
    }
}