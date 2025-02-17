package me.rapierxbox.shellyelevatev2

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
    private lateinit var switchOnSwipeSwitch: Switch
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

        backButton = findViewById(R.id.backButton)
        backButton.setOnClickListener {
            sharedPreferences.edit().putString("homeAssistantIp", ipEditText.text.toString()).apply()
            sharedPreferences.edit().putBoolean("switchOnSwipe", switchOnSwipeSwitch.isChecked).apply()
            finish()
        }
    }
}