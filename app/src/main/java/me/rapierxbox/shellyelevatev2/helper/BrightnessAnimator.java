package me.rapierxbox.shellyelevatev2.helper;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class BrightnessAnimator {
    private static final String TAG = "BrightnessAnimator";
    private static final long FRAME_DELAY_MS = 17; // ~58fps (display is 58.67)

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable animationRunnable;

    public interface BrightnessApplier {
        void apply(int value);
    }

    public void animate(int from, int to, long duration, BrightnessApplier applier) {
        if (from == to) {
            Log.d(TAG, "Skipping animation: from and to are equal (" + from + ")");
            applier.apply(to);
            return;
        }

        final long startTime = System.currentTimeMillis();
        animationRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - startTime;
                float fraction = Math.min(1f, (float) elapsed / duration);
                int interpolated = (int) (from + (to - from) * fraction);
                Log.d(TAG, "Animating brightness from " + from + " to " + to + ", current: " + interpolated);
                applier.apply(interpolated);

                if (fraction < 1f) {
                    handler.postDelayed(this, FRAME_DELAY_MS);
                }
            }
        };

        handler.post(animationRunnable);
    }

    public void cancel() {
        if (animationRunnable != null) {
            handler.removeCallbacks(animationRunnable);
            animationRunnable = null;
        }
    }
} // End of BrightnessAnimator.java