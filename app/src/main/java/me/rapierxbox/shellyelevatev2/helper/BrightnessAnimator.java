package me.rapierxbox.shellyelevatev2.helper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.SystemClock;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;

// thread-safe brightness animator that coalesces rapid calls and throttles sysfs writes
public class BrightnessAnimator {

    // cap sysfs writes to ~20 fps since the lcd backlight node is slow and does not
    // benefit from finer grained updates
    private static final long FRAME_THROTTLE_MS = 50L;
    private static final int MIN_ANIMATION_STEP = 2;

    private final ReentrantLock lock = new ReentrantLock();
    private ValueAnimator animator;
    private int currentBrightness = -1;
    private long lastFrameAtMs = 0L;

    // from and to are 0..255 backlight values
    // onUpdate is invoked with each intermediate value as the animation runs
    public void animate(int from, int to, IntConsumer onUpdate) {
        lock.lock();
        try {
            if (from == to) return;

            if (Math.abs(to - from) < MIN_ANIMATION_STEP) {
                currentBrightness = to;
                onUpdate.accept(to);
                return;
            }

            // cancel() re-enters this same lock which ReentrantLock allows on
            // the owning thread
            cancel();

            animator = ValueAnimator.ofInt(from, to);
            animator.setDuration(Math.max(0, ScreenManager.FADE_DURATION_MS));
            animator.addUpdateListener(animation -> {
                int value = (Integer) animation.getAnimatedValue();
                long now = SystemClock.uptimeMillis();
                if (now - lastFrameAtMs < FRAME_THROTTLE_MS) return;
                lock.lock();
                try {
                    if (value != currentBrightness) {
                        currentBrightness = value;
                        lastFrameAtMs = now;
                        onUpdate.accept(value);
                    }
                } finally {
                    lock.unlock();
                }
            });

            animator.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled = false;

                @Override
                public void onAnimationEnd(Animator animation) {
                    lock.lock();
                    try {
                        animator = null;
                        // frame throttle can drop the last frame so force the end value
                        if (!cancelled && currentBrightness != to) {
                            currentBrightness = to;
                            onUpdate.accept(to);
                        }
                    } finally {
                        lock.unlock();
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    lock.lock();
                    try {
                        cancelled = true;
                        animator = null;
                    } finally {
                        lock.unlock();
                    }
                }
            });

            animator.start();
        } finally {
            lock.unlock();
        }
    }

    public void cancel() {
        lock.lock();
        try {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        } finally {
            lock.unlock();
        }
    }
}
