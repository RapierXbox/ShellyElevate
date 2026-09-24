package me.rapierxbox.shellyelevatev2

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mButtonHandler
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwInputHandler
import me.rapierxbox.shellyelevatev2.databinding.SettingsActivityBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: SettingsActivityBinding

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

    // sw terminal edges must keep working while the settings screen holds focus
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (mSwInputHandler?.onKeyEvent(event) == true)
                || (mButtonHandler?.onKeyEvent(event) == true)
                || super.dispatchKeyEvent(event)
}