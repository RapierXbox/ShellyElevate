package me.rapierxbox.shellyelevatev2.voice;

import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_THINKING_SOUND;
import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_THINKING_SOUND_FILE;
import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_THINKING_SOUND_LOOP;
import static me.rapierxbox.shellyelevatev2.Constants.SP_VOICE_THINKING_SOUND_PITCH;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.Random;

import me.rapierxbox.shellyelevatev2.settings.SettingsRegistry;

// the uploaded sound that plays while home assistant works on the answer
// the player only exists from the start of listening to the answer so it costs nothing in between
final class ThinkingSound {
    private static final String TAG = "ThinkingSound";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    // main thread only
    private MediaPlayer player;

    // listening started so the sound can start the moment speech ends
    void prepare() {
        main.post(this::load);
    }

    void start() {
        main.post(() -> {
            load();
            if (player != null) play();
        });
    }

    void stop() {
        main.post(this::release);
    }

    private void load() {
        if (player != null || !mSharedPreferences.getBoolean(SP_VOICE_THINKING_SOUND, false)) return;
        File file = SettingsRegistry.uploadFile(mApplicationContext, SP_VOICE_THINKING_SOUND_FILE);
        if (!file.isFile()) return;
        MediaPlayer p = new MediaPlayer();
        try {
            p.setAudioStreamType(AudioManager.STREAM_MUSIC);
            p.setDataSource(file.getPath());
            p.prepare();
        } catch (Exception e) {
            Log.w(TAG, "could not load the thinking sound: " + e.getMessage());
            p.release();
            return;
        }
        p.setOnCompletionListener(mp -> {
            if (mSharedPreferences.getBoolean(SP_VOICE_THINKING_SOUND_LOOP, true)) play();
            else release();
        });
        player = p;
    }

    // every play gets its own pitch within the configured range
    private void play() {
        float range = mSharedPreferences.getInt(SP_VOICE_THINKING_SOUND_PITCH, 10) / 100f;
        float pitch = 1f + (random.nextFloat() * 2f - 1f) * range;
        try {
            player.seekTo(0);
            player.setPlaybackParams(player.getPlaybackParams().setPitch(pitch));
            player.start();
        } catch (RuntimeException e) {
            Log.w(TAG, "thinking sound failed: " + e.getMessage());
            release();
        }
    }

    private void release() {
        if (player == null) return;
        player.release();
        player = null;
    }
}
