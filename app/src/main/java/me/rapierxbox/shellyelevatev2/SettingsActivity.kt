package me.rapierxbox.shellyelevatev2

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mButtonHandler
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwInputHandler
import me.rapierxbox.shellyelevatev2.databinding.SettingsActivityBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: SettingsActivityBinding
    private var consumingWakeGesture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // fed here since a view listener only sees ACTION_DOWN unless it eats the gesture
    // and the screensaver wakes on ACTION_UP
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val screenManager = mScreenManager
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            consumingWakeGesture = screenManager?.shouldConsumeTouchForWake() == true
        }
        mScreenSaverManager?.onTouchEvent(ev)
        screenManager?.onTouchEvent()
        // a tap on a dark screen must not also flip the setting under the finger
        if (consumingWakeGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                consumingWakeGesture = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    // sw terminal edges must keep working while the settings screen holds focus
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (mSwInputHandler?.onKeyEvent(event) == true)
                || (mButtonHandler?.onKeyEvent(event) == true)
                || super.dispatchKeyEvent(event)
}