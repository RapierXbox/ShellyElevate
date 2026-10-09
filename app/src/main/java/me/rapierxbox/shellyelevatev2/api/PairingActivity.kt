package me.rapierxbox.shellyelevatev2.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mScreenSaverManager
import java.util.concurrent.atomic.AtomicInteger

// shows the pairing code over whatever is on screen until the controller confirmed it or it expired
class PairingActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var pairingId: String? = null
    private var expiresAtElapsed = 0L
    private var receiver: BroadcastReceiver? = null

    private val tick = object : Runnable {
        override fun run() {
            val left = ((expiresAtElapsed - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
            findViewById<TextView>(R.id.pairingExpiry).text = getString(R.string.pairing_expires, left)
            if (left <= 0) finish() else handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveDialogs.incrementAndGet()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_pairing)
        setFinishOnTouchOutside(false)
        findViewById<MaterialButton>(R.id.pairingCancel).setOnClickListener {
            ApiManager.cancelPairing(pairingId)
            finish()
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getBooleanExtra(EXTRA_SUCCESS, false)) {
                    Toast.makeText(context, R.string.pairing_done, Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver!!, IntentFilter(ACTION_FINISH))
        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    private fun bind(intent: Intent) {
        pairingId = intent.getStringExtra(EXTRA_ID)
        val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        findViewById<TextView>(R.id.pairingTitle).text =
            getString(R.string.pairing_title, intent.getStringExtra(EXTRA_CLIENT).orEmpty())
        // grouped 3 + 3 so it reads easily from a distance
        findViewById<TextView>(R.id.pairingCode).text =
            if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code
        expiresAtElapsed = SystemClock.elapsedRealtime() + intent.getLongExtra(EXTRA_TTL_MS, Pairing.EXPIRY_MS)
        handler.removeCallbacks(tick)
        tick.run()
    }

    override fun onResume() {
        super.onResume()
        mScreenSaverManager?.keepAlive(true)
    }

    override fun onPause() {
        super.onPause()
        mScreenSaverManager?.keepAlive(false)
    }

    override fun onDestroy() {
        liveDialogs.decrementAndGet()
        handler.removeCallbacks(tick)
        receiver?.let { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_FINISH = "me.rapierxbox.shellyelevatev2.PAIRING_FINISH"
        private const val EXTRA_ID = "pairingId"
        private const val EXTRA_CODE = "pairingCode"
        private const val EXTRA_CLIENT = "pairingClient"
        private const val EXTRA_TTL_MS = "pairingTtlMs"
        private const val EXTRA_SUCCESS = "pairingSuccess"

        // a count since a second instance can start before the first one is destroyed
        private val liveDialogs = AtomicInteger()

        // covers the gap between show and onCreate
        private const val START_GRACE_MS = 3_000L
        @Volatile
        private var startingUntil = 0L

        // a code is on screen or about to be so nothing of ours may cover it
        @JvmStatic
        val isShowing: Boolean
            get() = liveDialogs.get() > 0 || SystemClock.elapsedRealtime() < startingUntil

        @JvmStatic
        fun show(context: Context, pending: Pairing.Pending) {
            val intent = Intent(context, PairingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_ID, pending.id)
                .putExtra(EXTRA_CODE, pending.code)
                .putExtra(EXTRA_CLIENT, pending.clientName)
                .putExtra(EXTRA_TTL_MS, pending.expiresAt - System.currentTimeMillis())
            startingUntil = SystemClock.elapsedRealtime() + START_GRACE_MS
            Handler(Looper.getMainLooper()).post {
                mScreenSaverManager?.stopScreenSaver()
                context.startActivity(intent)
            }
        }

        @JvmStatic
        fun finish(context: Context, success: Boolean) {
            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(Intent(ACTION_FINISH).putExtra(EXTRA_SUCCESS, success))
        }
    }
}
