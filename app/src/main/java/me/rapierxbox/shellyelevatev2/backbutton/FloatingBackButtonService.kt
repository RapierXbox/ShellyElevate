package me.rapierxbox.shellyelevatev2.backbutton

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.edit
import me.rapierxbox.shellyelevatev2.R

class FloatingBackButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null

    private lateinit var prefs: SharedPreferences

    private var wasVisibleBeforePause = false

    fun pauseFloatingButton() {
        wasVisibleBeforePause = (floatingView != null)
        hideFloatingButton()
    }

    fun resumeFloatingButton() {
        if (wasVisibleBeforePause) showFloatingButton()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("floating_button_prefs", MODE_PRIVATE)
        showFloatingButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("FloatingBackButtonService", "onStartCommand() called with: intent = $intent, flags = $flags, startId = $startId, action = ${intent?.action}")

        when (intent?.action) {
            SHOW_FLOATING_BUTTON -> showFloatingButton()
            HIDE_FLOATING_BUTTON -> hideFloatingButton()
            PAUSE_BUTTON -> pauseFloatingButton()
            RESUME_BUTTON -> resumeFloatingButton()
            else -> showFloatingButton()
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showFloatingButton() {
        //This overrides pause status
        wasVisibleBeforePause = true

        if (floatingView != null) return

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_button_layout, null)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = prefs.getInt("pos_x", 0)
        layoutParams.y = prefs.getInt("pos_y", 300)

        val button = floatingView!!.findViewById<ImageView>(R.id.floating_back_button)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingView, layoutParams)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY + dy
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    if (dx != 0 || dy != 0) isClick = false
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        performClick()
                    } else {
                        prefs.edit {
                            putInt("pos_x", layoutParams.x)
                            putInt("pos_y", layoutParams.y)
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun performClick() {

        if (BackAccessibilityService.isAccessibilityEnabled(this)) {
            sendBroadcast(Intent(BackAccessibilityService.ACTION_BACK))
        } else {
            Toast.makeText(this, getString(R.string.accessibility_service_not_enabled), Toast.LENGTH_SHORT).show()
        }

    }

    fun hideFloatingButton() {
        //This overrides pause status
        wasVisibleBeforePause = false

        if (floatingView != null) {
            windowManager.removeView(floatingView)
            floatingView = null
        }
    }

    override fun onDestroy() {
        hideFloatingButton()
        super.onDestroy()
    }


    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val SHOW_FLOATING_BUTTON = "SHOW_FLOATING_BUTTON"
        const val HIDE_FLOATING_BUTTON = "HIDE_FLOATING_BUTTON"
        const val PAUSE_BUTTON = "PAUSE_BUTTON"
        const val RESUME_BUTTON = "RESUME_BUTTON"
    }
}
