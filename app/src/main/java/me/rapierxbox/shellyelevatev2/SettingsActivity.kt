package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.slider.Slider
import me.rapierxbox.shellyelevatev2.Constants.INTENT_SETTINGS_CHANGED
import me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME
import me.rapierxbox.shellyelevatev2.Constants.SP_AUTOMATIC_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.Constants.SP_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.Constants.SP_DEVICE
import me.rapierxbox.shellyelevatev2.Constants.SP_EXTENDED_JAVASCRIPT_INTERFACE
import me.rapierxbox.shellyelevatev2.Constants.SP_HTTP_SERVER_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_IGNORE_SSL_ERRORS
import me.rapierxbox.shellyelevatev2.Constants.SP_LITE_MODE
import me.rapierxbox.shellyelevatev2.Constants.SP_MIN_BRIGHTNESS
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_BROKER
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_PASSWORD
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_PORT
import me.rapierxbox.shellyelevatev2.Constants.SP_MQTT_USERNAME
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_DELAY
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_ENABLED
import me.rapierxbox.shellyelevatev2.Constants.SP_SCREEN_SAVER_ID
import me.rapierxbox.shellyelevatev2.Constants.SP_SWITCH_ON_SWIPE
import me.rapierxbox.shellyelevatev2.Constants.SP_WAKE_ON_PROXIMITY
import me.rapierxbox.shellyelevatev2.Constants.SP_WEBVIEW_URL
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mDeviceHelper
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mHttpServer
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSwipeHelper
import me.rapierxbox.shellyelevatev2.databinding.SettingsActivityBinding
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper
import java.io.IOException
import java.net.NetworkInterface

@SuppressLint("UseSwitchCompatOrMaterialCode")
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: SettingsActivityBinding // Declare the binding object

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
}