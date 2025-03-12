package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;

import java.io.IOException;

public class MediaHelper {
    private final MediaPlayer mediaPlayerEffects;
    private final MediaPlayer mediaPlayerMusic;
    private final AudioManager audioManager;

    public MediaHelper() {
        mediaPlayerEffects = new MediaPlayer();
        mediaPlayerMusic = new MediaPlayer();
        audioManager = (AudioManager) mApplicationContext.getSystemService(Context.AUDIO_SERVICE);

        mediaPlayerEffects.setAudioStreamType(AudioManager.USE_DEFAULT_STREAM_TYPE);
        mediaPlayerMusic.setAudioStreamType(AudioManager.STREAM_MUSIC);

        mediaPlayerEffects.setLooping(false);
        mediaPlayerMusic.setLooping(true);

        mediaPlayerEffects.setOnPreparedListener(mp -> {
            mp.start();
            pauseMusic();
        });
        mediaPlayerMusic.setOnPreparedListener(MediaPlayer::start);

        mediaPlayerEffects.setOnCompletionListener(mp -> resumeMusic());
    }

    public void playMusic(Uri uri) throws IOException {
        mediaPlayerMusic.reset();
        mediaPlayerMusic.setDataSource(mApplicationContext, uri);
        mediaPlayerMusic.prepareAsync();
    }
    public void playEffect(Uri uri) throws IOException {
        mediaPlayerEffects.reset();
        mediaPlayerEffects.setDataSource(mApplicationContext, uri);
        mediaPlayerEffects.prepareAsync();
    }
    public void pauseMusic() {
        mediaPlayerMusic.pause();
    }
    public void resumeMusic() {
        mediaPlayerMusic.start();
    }

    public void stopAll() {
        mediaPlayerEffects.stop();
        mediaPlayerMusic.stop();
    }
    public void setVolume(double volume) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (int) (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * volume), 0);
    }

    public double getVolume() {
        return (double) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / (double) audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    public void onDestroy() {
        mediaPlayerEffects.release();
        mediaPlayerMusic.release();
    }
}
