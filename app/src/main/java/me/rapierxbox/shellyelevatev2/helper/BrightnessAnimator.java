package me.rapierxbox.shellyelevatev2.helper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.SystemClock;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;

// Thread-safe brightness animator. Coalesces rapid calls and throttles sysfs writes.
public class BrightnessAnimator {

	private final ReentrantLock lock = new ReentrantLock();
	private ValueAnimator animator;
	private int currentBrightness = -1;
	private boolean running = false;
	private long lastFrameAtMs = 0L;
	// Cap sysfs writes to ~20 fps; the LCD backlight node is slow and doesn't
	// benefit from finer-grained updates.
	private static final long FRAME_THROTTLE_MS = 50L;
	private static final int MIN_ANIMATION_STEP = 2;

	public void animateTo(int target, IntConsumer onUpdate) {
		lock.lock();
		try {
			if (target == currentBrightness) return;

			int start = currentBrightness;
			animate(start, target, onUpdate);
		} finally {
			lock.unlock();
		}
	}

	public void animate(int from, int to, IntConsumer onUpdate) {
		if (from == to) return;

		if (Math.abs(to - from) < MIN_ANIMATION_STEP) {
			currentBrightness = to;
			onUpdate.accept(to);
			return;
		}

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
			public void onAnimationStart(Animator animation) {
				lock.lock();
				try {
					running = true;
				} finally {
					lock.unlock();
				}
			}

			@Override
			public void onAnimationEnd(Animator animation) {
				lock.lock();
				try {
					running = false;
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
					running = false;
					animator = null;
				} finally {
					lock.unlock();
				}
			}
		});

		animator.start();
	}

	public void cancel() {
		lock.lock();
		try {
			if (animator != null) {
				animator.cancel();
				animator = null;
				running = false;
			}
		} finally {
			lock.unlock();
		}
	}

	public boolean isRunning() {
		lock.lock();
		try {
			return running;
		} finally {
			lock.unlock();
		}
	}

	public int getCurrentBrightness() {
		lock.lock();
		try {
			return currentBrightness;
		} finally {
			lock.unlock();
		}
	}
}