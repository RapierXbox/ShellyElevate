package me.rapierxbox.shellyelevatev2.voice;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import me.rapierxbox.shellyelevatev2.R;

// assist timers from the controller shown in the voice bubble
// the soonest running timer counts down while no voice session uses the bubble
public class VoiceTimers {
    private static final long TICK_MS = 1_000L;
    private static final long DONE_SHOW_MS = 5_000L;
    // a finished timer rings until the bubble is tapped, a voice session starts or this runs out
    private static final long RING_MS = 120_000L;
    private static final long RING_EVERY_MS = 5_000L;

    private static final class Timer {
        String name;
        // remaining seconds at baseAt
        int remaining;
        long baseAt;
        boolean active;

        int secondsLeft(long now) {
            if (!active) return remaining;
            return Math.max(0, remaining - (int) ((now - baseAt) / 1000L));
        }
    }

    private final VoiceEngine engine;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // touched on the main thread only
    private final Map<String, Timer> timers = new LinkedHashMap<>();
    private long holdUntil = 0L;
    private boolean showing = false;
    private long ringUntil = 0L;
    private final Runnable tick = this::tick;
    private final Runnable ring = this::ring;

    VoiceTimers(VoiceEngine engine) {
        this.engine = engine;
    }

    // event is started updated cancelled or finished as sent by the controller
    public void update(String event, String id, String name, int remaining, boolean active) {
        handler.post(() -> apply(event, id, name, remaining, active));
    }

    private void apply(String event, String id, String name, int remaining, boolean active) {
        if ("cancelled".equals(event)) {
            timers.remove(id);
        } else if ("finished".equals(event)) {
            Timer done = timers.remove(id);
            String label = name.isEmpty() && done != null ? done.name : name;
            if (label.isEmpty()) label = mApplicationContext.getString(R.string.voice_timer_default_name);
            engine.playAlarmTone();
            long now = SystemClock.elapsedRealtime();
            // the controller announces the timer meanwhile, ring() skips the tone while that plays
            ringUntil = now + RING_MS;
            holdUntil = ringUntil;
            show(mApplicationContext.getString(R.string.voice_timer_done, label));
            handler.removeCallbacks(ring);
            handler.postDelayed(ring, RING_EVERY_MS);
        } else {
            Timer timer = timers.get(id);
            if (timer == null) {
                timer = new Timer();
                timers.put(id, timer);
            }
            timer.name = name;
            timer.remaining = Math.max(0, remaining);
            timer.baseAt = SystemClock.elapsedRealtime();
            timer.active = active;
        }
        handler.removeCallbacks(tick);
        tick();
    }

    private void tick() {
        long now = SystemClock.elapsedRealtime();
        if (now < holdUntil) {
            handler.postDelayed(tick, holdUntil - now);
            return;
        }
        Timer next = null;
        for (Timer timer : timers.values()) {
            if (next == null || timer.secondsLeft(now) < next.secondsLeft(now)) next = timer;
        }
        if (next == null) {
            if (showing) show("");
            showing = false;
            return;
        }
        // a voice session owns the bubble meanwhile
        if (engine.getState() == VoiceEngine.State.IDLE || engine.getState() == VoiceEngine.State.DISABLED) {
            String label = next.name.isEmpty()
                    ? mApplicationContext.getString(R.string.voice_timer_default_name) : next.name;
            show(label + " " + format(next.secondsLeft(now)));
        }
        handler.postDelayed(tick, TICK_MS);
    }

    private void ring() {
        if (ringUntil == 0L) return;
        VoiceEngine.State state = engine.getState();
        if (SystemClock.elapsedRealtime() >= ringUntil
                || state == VoiceEngine.State.LISTENING || state == VoiceEngine.State.PROCESSING) {
            // a wake word ("stop") ends it too. the session owns the bubble then, so leave its text
            stopRinging(state == VoiceEngine.State.IDLE || state == VoiceEngine.State.DISABLED);
            return;
        }
        // speaking is the announcement or an answer: wait for it
        if (state == VoiceEngine.State.IDLE) engine.playAlarmTone();
        handler.postDelayed(ring, RING_EVERY_MS);
    }

    private void stopRinging(boolean clearBubble) {
        ringUntil = 0L;
        holdUntil = 0L;
        handler.removeCallbacks(ring);
        if (!clearBubble) showing = false;
        handler.removeCallbacks(tick);
        tick();
    }

    // a tap on the voice bubble
    public void dismiss() {
        handler.post(() -> {
            if (ringUntil != 0L) stopRinging(true);
        });
    }

    private void show(String text) {
        showing = !text.isEmpty();
        engine.showText(text);
    }

    private static String format(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    void onDestroy() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(ring);
        handler.post(timers::clear);
    }
}
