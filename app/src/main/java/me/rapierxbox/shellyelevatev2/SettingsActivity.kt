package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.legacy.content.WakefulBroadcastReceiver
import me.rapierxbox.shellyelevatev2.helper.ServiceHelper

class SettingsActivity: Activity() {
    private lateinit var findIPButton: Button
    private lateinit var ipEditText: EditText
    private lateinit var backButton: Button
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var switchOnSwipeSwitch: Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var automaticBrightnessSwitch: Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var screenSaverSwitch: Switch
    private lateinit var httpServerText: TextView
    private lateinit var httpServerButton: Button
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        val httpServerRunning = httpServiceRunning()

        sharedPreferences = getSharedPreferences("ShellyElevateV2", MODE_PRIVATE)

        ipEditText = findViewById(R.id.homeAssistantIp)
        ipEditText.setText(sharedPreferences.getString("homeAssistantIp", ""))

        findIPButton = findViewById(R.id.findIPButton)
        findIPButton.setOnClickListener {
            ServiceHelper.getHAIP(applicationContext) { ip: String ->
                runOnUiThread {
                    ipEditText.setText(ip)
                }
            }
        }

        switchOnSwipeSwitch = findViewById(R.id.switchOnSwipe)
        switchOnSwipeSwitch.isChecked = sharedPreferences.getBoolean("switchOnSwipe", true)

        automaticBrightnessSwitch = findViewById(R.id.automaticBrightness)
        automaticBrightnessSwitch.isChecked = sharedPreferences.getBoolean("automaticBrightness", true)

        screenSaverSwitch = findViewById(R.id.screenSaver)
        screenSaverSwitch.isChecked = sharedPreferences.getBoolean("screenSaver", true)

        httpServerText = findViewById(R.id.httpServerText)
        httpServerText.text = if (httpServerRunning) "HTTP Server: Running" else "HTTP Server: Not running"

        httpServerButton = findViewById(R.id.httpServerButton)
        httpServerButton.visibility = if (httpServerRunning) Button.GONE else Button.VISIBLE
        httpServerButton.setOnClickListener {
            val serviceIntent = Intent(this, HttpServerService::class.java)
            WakefulBroadcastReceiver.startWakefulService(this, serviceIntent)
            httpServerText.text = "HTTP Server: Running"
            httpServerButton.visibility = Button.GONE
        }


        backButton = findViewById(R.id.backButton)
        backButton.setOnClickListener {
            sharedPreferences.edit().putString("homeAssistantIp", ipEditText.text.toString())
                .putBoolean("switchOnSwipe", switchOnSwipeSwitch.isChecked)
                .putBoolean("automaticBrightness", automaticBrightnessSwitch.isChecked)
                .putBoolean("screenSaver", screenSaverSwitch.isChecked).apply()
            finish()
        }
    }

    private fun httpServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if ("me.rapierxbox.shellyelevatev2.HttpServerService" == service.service.className) {
                return true
            }
        }
        return false
    }
}