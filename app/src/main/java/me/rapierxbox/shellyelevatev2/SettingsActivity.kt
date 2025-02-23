package me.rapierxbox.shellyelevatev2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch

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
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        sharedPreferences = getSharedPreferences("ShellyElevateV2", MODE_PRIVATE)

        ipEditText = findViewById(R.id.homeAssistantIp)
        ipEditText.setText(sharedPreferences.getString("homeAssistantIp", ""))

        findIPButton = findViewById(R.id.findIPButton)
        findIPButton.setOnClickListener {
            ServiceHelper.getHAIP(applicationContext) {ip: String ->
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

        backButton = findViewById(R.id.backButton)
        backButton.setOnClickListener {
            sharedPreferences.edit().putString("homeAssistantIp", ipEditText.text.toString())
                .putBoolean("switchOnSwipe", switchOnSwipeSwitch.isChecked)
                .putBoolean("automaticBrightness", automaticBrightnessSwitch.isChecked)
                .putBoolean("screenSaver", screenSaverSwitch.isChecked).apply()
            finish()
        }
    }
}