package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mSharedPreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.IOException;

import me.rapierxbox.shellyelevatev2.Constants;

public class MediaHelper {
    private MediaPlayer mediaPlayerEffects;
    private MediaPlayer mediaPlayerMusic;
    private final AudioManager audioManager;
    private boolean enabled = true;
    // remember if music was playing when an effect started so we only resume then
    private volatile boolean resumeMusicAfterEffect = false;

    public MediaHelper() {
        this.enabled = mSharedPreferences.getBoolean(Constants.SP_MEDIA_ENABLED, false);

        audioManager = (AudioManager) mApplicationContext.getSystemService(Context.AUDIO_SERVICE);

        if (enabled) {
            initPlayers();
        } else {
            Log.i("MediaHelper", "MediaHelper disabled");
        }
    }

    // synchronized to avoid double init from concurrent callers
    private synchronized void initPlayers() {
        if (mediaPlayerEffects != null || mediaPlayerMusic != null) return;

        Log.i("MediaHelper", "MediaHelper enabled: starting...");
        mediaPlayerEffects = new MediaPlayer();
        mediaPlayerMusic = new MediaPlayer();

        mediaPlayerEffects.setLooping(false);
        mediaPlayerMusic.setLooping(true);

        mediaPlayerEffects.setOnPreparedListener(mp -> {
            resumeMusicAfterEffect = isMusicPlaying();
            mp.start();
            pauseMusic();
        });
        mediaPlayerMusic.setOnPreparedListener(MediaPlayer::start);

        mediaPlayerEffects.setOnCompletionListener(mp -> resumeMusicIfNeeded());

        mediaPlayerMusic.setOnErrorListener((mp, what, extra) -> {
            Log.e("MediaHelper", "Music error: " + what + " / " + extra);
            return true;
        });
        mediaPlayerEffects.setOnErrorListener((mp, what, extra) -> {
            Log.e("MediaHelper", "Effect error: " + what + " / " + extra);
            // onCompletion is suppressed when we return true so resume here
            resumeMusicIfNeeded();
            return true;
        });
    }

    public void playMusic(Uri uri) throws IOException {
        if (!enabled) return;
        if (mediaPlayerMusic == null) initPlayers();
        mediaPlayerMusic.reset();
        mediaPlayerMusic.setDataSource(mApplicationContext, uri);
        mediaPlayerMusic.prepareAsync();
    }

    public void playEffect(Uri uri) throws IOException {
        if (!enabled) return;
        if (mediaPlayerEffects == null) initPlayers();
        mediaPlayerEffects.reset();
        mediaPlayerEffects.setDataSource(mApplicationContext, uri);
        mediaPlayerEffects.prepareAsync();
    }

    public void pauseMusic() {
        if (!enabled || mediaPlayerMusic == null) return;
        try { if (mediaPlayerMusic.isPlaying()) mediaPlayerMusic.pause(); } catch (IllegalStateException ignored) {}
    }

    public void resumeMusic() {
        if (!enabled || mediaPlayerMusic == null) return;
        try { mediaPlayerMusic.start(); } catch (IllegalStateException ignored) {}
    }

    private boolean isMusicPlaying() {
        if (mediaPlayerMusic == null) return false;
        try { return mediaPlayerMusic.isPlaying(); } catch (IllegalStateException e) { return false; }
    }

    private void resumeMusicIfNeeded() {
        if (!resumeMusicAfterEffect) return;
        resumeMusicAfterEffect = false;
        resumeMusic();
    }

    public void resumeOrPauseMusic() {
        if (!enabled || mediaPlayerMusic == null) return;
        try {
            if (mediaPlayerMusic.isPlaying()) {
                mediaPlayerMusic.pause();
                Log.i("MediaHelper", "Music paused");
            } else {
                mediaPlayerMusic.start();
                Log.i("MediaHelper", "Music resumed");
            }
        } catch (IllegalStateException e) {
            Log.e("MediaHelper", "resumeOrPauseMusic failed", e);
        }
    }

    public void stopAll() {
        if (!enabled) return;
        try { if (mediaPlayerEffects != null) mediaPlayerEffects.stop(); } catch (IllegalStateException ignored) {}
        try { if (mediaPlayerMusic != null) mediaPlayerMusic.stop(); } catch (IllegalStateException ignored) {}
    }

    public void setVolume(double volume) {
        if (!enabled) return;
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int newVol = (int) (max * volume);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
        } catch (Exception e) {
            Log.e("MediaHelper", "Failed to set volume", e);
        }
    }

    public double getVolume() {
        if (!enabled) return 0.0;
        try {
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return (double) current / (double) max;
        } catch (Exception e) {
            Log.e("MediaHelper", "Failed to get volume", e);
            return 0.0;
        }
    }

    public void onDestroy() {
        if (!enabled) return;
        if (mediaPlayerEffects != null) mediaPlayerEffects.release();
        if (mediaPlayerMusic != null) mediaPlayerMusic.release();
    }
}
