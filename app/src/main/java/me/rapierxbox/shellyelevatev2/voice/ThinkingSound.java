package me.rapierxbox.shellyelevatev2.voice;

import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_THINKING_SOUND;
import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_THINKING_SOUND_FADE_IN;
import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_THINKING_SOUND_FADE_OUT;
import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_THINKING_SOUND_FILE;
import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_THINKING_SOUND_LOOP;
import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_THINKING_SOUND_PITCH;
import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_THINKING_SOUND_VOLUME;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.Random;

import me.rapierxbox.shellyelevatev2.helper.AudioOutput;
import me.rapierxbox.shellyelevatev2.settings.SettingsRegistry;

// the uploaded sound that plays while home assistant works on the answer
// the player only exists from the start of listening to the answer so it costs nothing in between
final class ThinkingSound {
    private static final String TAG = "ThinkingSound";
    private static final long FADE_STEP_MS = 30;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    // main thread only
    private MediaPlayer player;
    // the fade factor of the active player from 0 silent to 1 the set volume, and its fade in while that runs
    private float level = 1f;
    private Runnable fadeIn;

    // listening started so the sound can start the moment speech ends
    void prepare() {
        main.post(this::load);
    }

    void start() {
        main.post(() -> {
            load();
            if (player != null) play(true);
        });
    }

    // fades out beside the answer. the faded player is released by its own fade so a new session can load a fresh one
    void stop() {
        main.post(this::fadeOut);
    }

    private void load() {
        if (player != null || !mSharedPreferences.getBoolean(SP_VOICE_THINKING_SOUND, false)) return;
        File file = SettingsRegistry.uploadFile(mApplicationContext, SP_VOICE_THINKING_SOUND_FILE);
        if (!file.isFile()) return;
        MediaPlayer p = new MediaPlayer();
        try {
            p.setAudioStreamType(AudioManager.STREAM_MUSIC);
            p.setDataSource(file.getPath());
            AudioOutput.applyVoice(p);
            p.prepare();
        } catch (Exception e) {
            Log.w(TAG, "could not load the thinking sound: " + e.getMessage());
            p.release();
            return;
        }
        p.setOnCompletionListener(mp -> {
            if (!mSharedPreferences.getBoolean(SP_VOICE_THINKING_SOUND_LOOP, true)) { release(); return; }
            // the Rockchip player decodes nothing when a finished player is started again, so every round gets a fresh one
            player = null;
            mp.release();
            load();
            if (player != null) play(false);
        });
        player = p;
    }

    // every play gets its own pitch within the configured range. only the first play fades in
    private void play(boolean first) {
        float range = mSharedPreferences.getInt(SP_VOICE_THINKING_SOUND_PITCH, 10) / 100f;
        int fadeInMs = first ? mSharedPreferences.getInt(SP_VOICE_THINKING_SOUND_FADE_IN, 0) : 0;
        try {
            if (first) level = fadeInMs > 0 ? 0f : 1f;
            volume(player, level);
            player.start();
            if (fadeInMs > 0) fadeIn = ramp(player, 0f, 1f, fadeInMs, true, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "thinking sound failed: " + e.getMessage());
            release();
            return;
        }
        // after start: the Rockchip player never starts when setPlaybackParams comes before start(),
        // and a failed pitch must not stop the sound
        if (range > 0f) setPitch(1f + (random.nextFloat() * 2f - 1f) * range);
    }

    private void setPitch(float pitch) {
        try {
            player.setPlaybackParams(player.getPlaybackParams().setPitch(pitch));
        } catch (RuntimeException e) {
            Log.w(TAG, "thinking sound pitch not applied: " + e.getMessage());
        }
    }

    private void fadeOut() {
        MediaPlayer p = player;
        if (p == null) return;
        player = null;
        if (fadeIn != null) main.removeCallbacks(fadeIn);
        fadeIn = null;
        // the loop must not restart a player that fades away
        p.setOnCompletionListener(null);
        int ms = mSharedPreferences.getInt(SP_VOICE_THINKING_SOUND_FADE_OUT, 0);
        if (ms <= 0) { p.release(); return; }
        ramp(p, level, 0f, ms, false, p::release);
    }

    // steps the volume of one player from one fade factor to another. done runs once it got there
    private Runnable ramp(MediaPlayer p, float from, float to, int ms, boolean active, Runnable done) {
        long t0 = SystemClock.elapsedRealtime();
        Runnable step = new Runnable() {
            @Override public void run() {
                float k = Math.min(1f, (SystemClock.elapsedRealtime() - t0) / (float) ms);
                float f = from + (to - from) * k;
                try { volume(p, f); }
                catch (RuntimeException e) { return; }
                if (active && p == player) level = f;
                if (k < 1f) main.postDelayed(this, FADE_STEP_MS);
                else if (done != null) done.run();
            }
        };
        step.run();
        return step;
    }

    // the fade factor on top of the thinking sound volume and the voice reply volume
    private void volume(MediaPlayer p, float factor) {
        float v = factor * mSharedPreferences.getInt(SP_VOICE_THINKING_SOUND_VOLUME, 100) / 100f * AudioOutput.replyGain();
        p.setVolume(v, v);
    }

    private void release() {
        if (player == null) return;
        if (fadeIn != null) main.removeCallbacks(fadeIn);
        fadeIn = null;
        player.release();
        player = null;
    }
}
