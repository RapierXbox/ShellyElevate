package me.rapierxbox.shellyelevatev2.screensavers.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.TextView
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DigitalClockScreenSaverActivity: Activity() {
    private lateinit var digitalClockTextView: TextView
    private lateinit var swipeDetectionOverlayView: View

    private val mSimpleDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val mTimeTickBroadCastReciver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateTime()
        }
    }

    private fun findViews() {
        digitalClockTextView = findViewById(R.id.clockText)
        swipeDetectionOverlayView = findViewById(R.id.swipeDetectionOverlay)
    }

    private fun updateTime() {
        digitalClockTextView.text = mSimpleDateFormat.format(Date())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.digital_clock_screen_saver)

        findViews()
        updateTime()

        swipeDetectionOverlayView.setOnTouchListener { _, event ->
            ShellyElevateApplication.mScreenSaverManager.onTouchEvent()
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