package me.rapierxbox.shellyelevatev2

import android.app.Activity
import android.content.SharedPreferences
import android.net.nsd.NsdManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText

class SettingsActivity: Activity() {
    private lateinit var findIPButton: Button
    private lateinit var IPEditText: EditText
    private lateinit var backButton: Button
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        sharedPreferences = getSharedPreferences("ShellyElevateV2", MODE_PRIVATE)

        IPEditText = findViewById(R.id.homeAssistantIp)
        IPEditText.setText(sharedPreferences.getString("homeAssistantIp", ""))

        findIPButton = findViewById(R.id.findIPButton)
        findIPButton.setOnClickListener {
            ServiceHelper.getHAIP(applicationContext) {ip: String ->
                runOnUiThread {
                    IPEditText.setText(ip)
                    sharedPreferences.edit().putString("homeAssistantIp", ip).apply()
                }
            }
        }

        backButton = findViewById(R.id.backButton)
        backButton.setOnClickListener {
            finish()
        }
    }
}