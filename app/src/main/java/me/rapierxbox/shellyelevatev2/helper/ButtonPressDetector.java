package me.rapierxbox.shellyelevatev2.helper;

import android.os.Handler;
import android.os.Looper;

import me.rapierxbox.shellyelevatev2.Constants;

// detects short long double and triple presses
// the callback fires once per press sequence after MULTI_CLICK_TIMEOUT_MS has elapsed since the last release
public class ButtonPressDetector {
    private static final long LONG_PRESS_MIN_MS = 1000;
    private static final long MULTI_CLICK_TIMEOUT_MS = 400;

    private long pressDownTimeMs = 0;
    private int clickCount = 0;
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

    public void onPressDown() {
        // suspend any pending multi click decision while a new press is held
        if (multiClickTimeoutRunnable != null) {
            handler.removeCallbacks(multiClickTimeoutRunnable);
        }
        pressDownTimeMs = System.currentTimeMillis();
        clickCount++;
    }

    public void onPressUp() {
        if (pressDownTimeMs == 0) return;

        long pressDurationMs = System.currentTimeMillis() - pressDownTimeMs;
        pressDownTimeMs = 0;

        // long press fires immediately on release short presses defer to the
        // multi click timeout so they can still be upgraded to double/triple
        if (pressDurationMs >= LONG_PRESS_MIN_MS) {
            fireCallback(Constants.BUTTON_PRESS_TYPE_LONG);
        } else {
            scheduleMultiClickTimeout();
        }
    }

    private void scheduleMultiClickTimeout() {
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
        if (multiClickTimeoutRunnable != null) {
            handler.removeCallbacks(multiClickTimeoutRunnable);
            multiClickTimeoutRunnable = null;
        }
        if (callback != null) {
            callback.onButtonPress(buttonId, pressType);
        }
    }
}
