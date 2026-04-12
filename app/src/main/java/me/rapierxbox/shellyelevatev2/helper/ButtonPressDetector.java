package me.rapierxbox.shellyelevatev2.helper;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import me.rapierxbox.shellyelevatev2.Constants;

/**
 * Detects button press types: short, long, double-click, triple-click.
 * Callback is invoked once per press sequence with the detected type.
 */
public class ButtonPressDetector {
    private static final long SHORT_PRESS_MAX_MS = 500;   // <= 500ms = short press
    private static final long LONG_PRESS_MIN_MS = 1000;   // >= 1000ms = long press
    private static final long MULTI_CLICK_TIMEOUT_MS = 400;  // timeout between clicks for double/triple

    private long pressDownTimeMs = 0;
    private int clickCount = 0;
    private String pendingPressType = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable multiClickTimeoutRunnable = null;
    private final Callback callback;
    private final int buttonId;

    public interface Callback {
        void onButtonPress(int buttonId, String pressType);
    }

    public ButtonPressDetector(int buttonId, Callback callback) {
        this.buttonId = buttonId;
        this.callback = callback;
    }

    /**
     * Call this when the button is pressed (ACTION_DOWN)
     */
    public void onPressDown() {
        pressDownTimeMs = System.currentTimeMillis();
        clickCount++;
    }

    /**
     * Call this when the button is released (ACTION_UP)
     */
    public void onPressUp() {
        if (pressDownTimeMs == 0) return;

        long pressDurationMs = System.currentTimeMillis() - pressDownTimeMs;
        String pressType;

        if (pressDurationMs >= LONG_PRESS_MIN_MS) {
            pressType = Constants.BUTTON_PRESS_TYPE_LONG;
            fireCallback(pressType);
        } else if (pressDurationMs <= SHORT_PRESS_MAX_MS) {
            // Short press - could be part of double/triple click, so wait
            pendingPressType = Constants.BUTTON_PRESS_TYPE_SHORT;
            scheduleMultiClickTimeout();
        }

        pressDownTimeMs = 0;
    }

    private void scheduleMultiClickTimeout() {
        // Cancel any pending timeout
        if (multiClickTimeoutRunnable != null) {
            handler.removeCallbacks(multiClickTimeoutRunnable);
        }

        multiClickTimeoutRunnable = () -> {
            String finalPressType;
            if (clickCount >= 3) {
                finalPressType = Constants.BUTTON_PRESS_TYPE_TRIPLE;
            } else if (clickCount >= 2) {
                finalPressType = Constants.BUTTON_PRESS_TYPE_DOUBLE;
            } else {
                finalPressType = Constants.BUTTON_PRESS_TYPE_SHORT;
            }
            fireCallback(finalPressType);
        };

        handler.postDelayed(multiClickTimeoutRunnable, MULTI_CLICK_TIMEOUT_MS);
    }

    private void fireCallback(String pressType) {
        clickCount = 0;
        pendingPressType = null;
        if (multiClickTimeoutRunnable != null) {
            handler.removeCallbacks(multiClickTimeoutRunnable);
            multiClickTimeoutRunnable = null;
        }
        if (callback != null) {
            callback.onButtonPress(buttonId, pressType);
        }
    }
}
