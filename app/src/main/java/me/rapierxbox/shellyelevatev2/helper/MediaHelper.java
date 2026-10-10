package me.rapierxbox.shellyelevatev2.helper;

import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.mApplicationContext;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import me.rapierxbox.shellyelevatev2.api.ApiEvents;
import me.rapierxbox.shellyelevatev2.api.ApiHub;

// music player with a queue plus an announce channel that pauses the music and resumes it afterwards
// every MediaPlayer call runs on one media thread so the public methods are safe from any thread
public class MediaHelper {
    private static final String TAG = "MediaHelper";

    private static final long STATUS_INTERVAL_MS = 10_000;
    private static final long STATUS_WAIT_MS = 500;
    private static final float DUCK_GAIN = 0.2f;

    private enum State { IDLE, BUFFERING, PLAYING, PAUSED }

    private final AudioManager audioManager;
    private final HandlerThread thread;
    private final Handler handler;
    private final MediaQueue queue = new MediaQueue();
    private final ApiHub.ControllerListener controllerListener;
    private final AudioManager.OnAudioFocusChangeListener focusListener;

    // media thread only
    private MediaPlayer musicPlayer;
    private MediaPlayer announcePlayer;
    private Runnable announceDone;
    private State state = State.IDLE;
    private boolean musicPrepared;
    // repeat one came from the legacy loop and not from a controller
    private boolean legacyRepeat;
    private boolean pausedForAnnounce;
    private boolean pausedForFocus;
    private boolean ducked;
    private boolean hasFocus;
    private int consecutiveErrors;

    private volatile boolean announcing;
    private volatile boolean muted;
    private volatile boolean destroyed;
    private volatile JSONObject lastStatus = new JSONObject();

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            if (state != State.PLAYING) return;
            pushStatus();
            handler.postDelayed(this, STATUS_INTERVAL_MS);
        }
    };

    public MediaHelper() {
        audioManager = (AudioManager) mApplicationContext.getSystemService(Context.AUDIO_SERVICE);
        thread = new HandlerThread("MediaHelper");
        thread.start();
        handler = new Handler(thread.getLooper());
        focusListener = change -> post(() -> onFocusChange(change));
        // a new controller gets the current status right away
        controllerListener = connected -> {
            if (connected) post(this::pushStatus);
        };
        ApiHub.addControllerListener(controllerListener);
        // a controller that is already connected gets the status as soon as media is enabled
        if (ApiHub.hasController()) post(this::pushStatus);
        Log.i(TAG, "MediaHelper enabled");
    }

    private void post(Runnable task) {
        if (destroyed) return;
        handler.post(() -> {
            if (destroyed) return;
            try {
                task.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "Media task failed", e);
            }
        });
    }

    // ---------------------------------------------------------------- public api

    // enqueue is replace add next or play
    public void play(MediaQueue.Track track, String enqueue) {
        post(() -> {
            // a new v1 play does not inherit the repeat one the legacy loop set
            if (legacyRepeat && (MediaQueue.ENQUEUE_REPLACE.equals(enqueue) || MediaQueue.ENQUEUE_PLAY.equals(enqueue))) {
                queue.setRepeat(MediaQueue.Repeat.OFF);
                legacyRepeat = false;
            }
        });
        playQueued(track, enqueue);
    }

    private void playQueued(MediaQueue.Track track, String enqueue) {
        post(() -> {
            MediaQueue.Track start = queue.enqueue(track, enqueue, state != State.IDLE);
            if (start != null) {
                consecutiveErrors = 0;
                startTrack(start);
            } else {
                pushStatus();
            }
        });
    }

    // plays a url over the music which pauses and resumes afterwards
    // onDone runs on the media thread once it finished failed or was replaced
    public void announce(String url, Runnable onDone) {
        post(() -> startAnnounce(url, onDone));
    }

    public void pause() {
        post(() -> {
            pausedForAnnounce = false;
            pausedForFocus = false;
            if (state == State.PLAYING) {
                try {
                    musicPlayer.pause();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "pause failed", e);
                }
                setState(State.PAUSED);
            } else if (state == State.BUFFERING) {
                // the prepared callback sees paused and stays quiet
                setState(State.PAUSED);
            }
        });
    }

    public void resume() {
        post(() -> {
            if (state == State.PAUSED && musicPrepared) {
                if (announcing) {
                    pausedForAnnounce = true;
                    return;
                }
                startMusicPlayer();
            } else if (state == State.PAUSED) {
                // paused while it was still buffering so prepare it again
                MediaQueue.Track current = queue.current();
                if (current != null) startTrack(current);
            } else if (state == State.IDLE) {
                MediaQueue.Track current = queue.current();
                if (current != null) startTrack(current);
            }
        });
    }

    // stops music and announcements and clears the queue
    public void stop() {
        post(() -> {
            stopAnnounceNow();
            stopMusicNow();
            queue.clear();
            setState(State.IDLE);
        });
    }

    public void next() {
        post(() -> {
            MediaQueue.Track next = queue.advance(true);
            if (next != null) {
                consecutiveErrors = 0;
                startTrack(next);
            } else {
                stopMusicNow();
                setState(State.IDLE);
            }
        });
    }

    public void seek(double seconds) {
        post(() -> {
            if (!musicPrepared) return;
            try {
                musicPlayer.seekTo((int) Math.max(0, Math.round(seconds * 1000)));
            } catch (IllegalStateException e) {
                Log.w(TAG, "seek failed", e);
            }
            pushStatus();
        });
    }

    public void setRepeat(MediaQueue.Repeat repeat) {
        post(() -> {
            legacyRepeat = false;
            queue.setRepeat(repeat);
            applyLooping();
            pushStatus();
        });
    }

    public void setMuted(boolean mute) {
        muted = mute;
        post(() -> {
            applyGain();
            pushStatus();
        });
    }

    public boolean isMuted() {
        return muted;
    }

    // ---------------------------------------------------------------- legacy api

    // the legacy api loops its music so it plays with repeat one
    public void playMusic(Uri uri) throws IOException {
        post(() -> {
            queue.setRepeat(MediaQueue.Repeat.ONE);
            legacyRepeat = true;
        });
        playQueued(MediaQueue.Track.of(uri.toString()), MediaQueue.ENQUEUE_REPLACE);
    }

    public void playEffect(Uri uri) throws IOException {
        announce(uri.toString(), null);
    }

    public void pauseMusic() {
        pause();
    }

    public void resumeMusic() {
        resume();
    }

    public void resumeOrPauseMusic() {
        post(() -> {
            if (state == State.PLAYING || state == State.BUFFERING) pause();
            else resume();
        });
    }

    public void stopAll() {
        stop();
    }

    // system music stream volume 0..1
    public void setVolume(double volume) {
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int newVol = (int) (max * Math.max(0, Math.min(1, volume)));
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set volume", e);
        }
        post(this::pushStatus);
    }

    public double getVolume() {
        try {
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return max > 0 ? (double) current / (double) max : 0.0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get volume", e);
            return 0.0;
        }
    }

    // protocol MediaStatus. built on the media thread with the last snapshot as fallback
    public JSONObject statusJson() {
        if (Looper.myLooper() == handler.getLooper()) return buildStatus();
        if (destroyed) return idleStatus();
        AtomicReference<JSONObject> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        handler.post(() -> {
            try {
                result.set(buildStatus());
            } finally {
                latch.countDown();
            }
        });
        try {
            if (latch.await(STATUS_WAIT_MS, TimeUnit.MILLISECONDS) && result.get() != null) return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return lastStatus;
    }

    public void onDestroy() {
        ApiHub.removeControllerListener(controllerListener);
        handler.post(() -> {
            stopAnnounceNow();
            releaseMusicPlayer();
            abandonFocus();
            destroyed = true;
            // the controller learns that playback ended with the media switch
            sendStatus(idleStatus());
            thread.quitSafely();
        });
    }

    // ---------------------------------------------------------------- music

    private void startTrack(MediaQueue.Track track) {
        handler.removeCallbacks(statusTicker);
        pausedForFocus = false;
        MediaPlayer player = musicPlayer();
        musicPrepared = false;
        try {
            player.reset();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            // repeat one loops in the player so a loop needs no new prepare or download
            player.setLooping(queue.getRepeat() == MediaQueue.Repeat.ONE);
            player.setDataSource(mApplicationContext, Uri.parse(MediaUrls.forPlayback(track.url)));
            applyGain();
            player.prepareAsync();
            setState(State.BUFFERING);
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.e(TAG, "Cannot play " + track.url, e);
            onMusicFailed(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO);
        }
    }

    private void applyLooping() {
        if (musicPlayer == null || !musicPrepared) return;
        try {
            musicPlayer.setLooping(queue.getRepeat() == MediaQueue.Repeat.ONE);
        } catch (IllegalStateException ignored) {
            // the player is between states and startTrack sets it again
        }
    }

    private MediaPlayer musicPlayer() {
        if (musicPlayer == null) {
            musicPlayer = new MediaPlayer();
            musicPlayer.setOnPreparedListener(this::onMusicPrepared);
            musicPlayer.setOnCompletionListener(mp -> onMusicCompleted());
            musicPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Music error: " + what + " / " + extra);
                musicPrepared = false;
                onMusicFailed(what, extra);
                // true so onCompletion does not fire as well
                return true;
            });
        }
        return musicPlayer;
    }

    private void onMusicPrepared(MediaPlayer mp) {
        musicPrepared = true;
        consecutiveErrors = 0;
        if (state == State.PAUSED) {
            // paused while buffering
            pushStatus();
            return;
        }
        if (announcing) {
            pausedForAnnounce = true;
            setState(State.PAUSED);
            return;
        }
        startMusicPlayer();
    }

    private void startMusicPlayer() {
        requestFocus();
        try {
            musicPlayer.start();
        } catch (IllegalStateException e) {
            Log.w(TAG, "start failed", e);
            return;
        }
        pausedForAnnounce = false;
        setState(State.PLAYING);
    }

    private void onMusicCompleted() {
        MediaQueue.Track next = queue.advance(false);
        if (next != null) {
            startTrack(next);
        } else {
            musicPrepared = false;
            setState(State.IDLE);
            abandonFocus();
        }
    }

    // a broken url skips ahead but a queue where every item fails stops
    private void onMusicFailed(int what, int extra) {
        MediaQueue.Track failed = queue.current();
        ApiEvents.mediaError(failed != null ? failed.url : null, what, extra);
        musicPrepared = false;
        consecutiveErrors++;
        MediaQueue.Track next = consecutiveErrors < queue.size() ? queue.advance(true) : null;
        if (next != null) {
            startTrack(next);
        } else {
            consecutiveErrors = 0;
            setState(State.IDLE);
            abandonFocus();
        }
    }

    private void stopMusicNow() {
        handler.removeCallbacks(statusTicker);
        pausedForAnnounce = false;
        pausedForFocus = false;
        musicPrepared = false;
        if (musicPlayer != null) {
            try {
                musicPlayer.reset();
            } catch (IllegalStateException ignored) {
            }
        }
        abandonFocus();
    }

    private void releaseMusicPlayer() {
        handler.removeCallbacks(statusTicker);
        if (musicPlayer != null) {
            try {
                musicPlayer.release();
            } catch (RuntimeException ignored) {
            }
            musicPlayer = null;
        }
        musicPrepared = false;
        state = State.IDLE;
    }

    // ---------------------------------------------------------------- announce

    private void startAnnounce(String url, Runnable onDone) {
        stopAnnounceNow();
        if (state == State.PLAYING) {
            try {
                musicPlayer.pause();
            } catch (IllegalStateException ignored) {
            }
            pausedForAnnounce = true;
            state = State.PAUSED;
            handler.removeCallbacks(statusTicker);
        }
        announceDone = onDone;
        announcing = true;
        MediaPlayer player = new MediaPlayer();
        announcePlayer = player;
        try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setLooping(false);
            player.setDataSource(mApplicationContext, Uri.parse(MediaUrls.forPlayback(url)));
            player.setOnPreparedListener(mp -> {
                if (mp != announcePlayer) return;
                float gain = muted ? 0f : 1f;
                mp.setVolume(gain, gain);
                mp.start();
            });
            player.setOnCompletionListener(mp -> {
                if (mp == announcePlayer) finishAnnounce();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Announce error: " + what + " / " + extra);
                if (mp == announcePlayer) finishAnnounce();
                return true;
            });
            player.prepareAsync();
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.e(TAG, "Cannot announce " + url, e);
            finishAnnounce();
            return;
        }
        pushStatus();
    }

    private void finishAnnounce() {
        releaseAnnouncePlayer();
        if (pausedForAnnounce && state == State.PAUSED && musicPrepared) {
            startMusicPlayer();
        } else {
            pausedForAnnounce = false;
            pushStatus();
        }
    }

    // ends a running announcement without resuming the music
    private void stopAnnounceNow() {
        if (announcePlayer == null) return;
        releaseAnnouncePlayer();
    }

    private void releaseAnnouncePlayer() {
        MediaPlayer player = announcePlayer;
        announcePlayer = null;
        announcing = false;
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
        }
        Runnable done = announceDone;
        announceDone = null;
        if (done != null) {
            try {
                done.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "Announce callback failed", e);
            }
        }
    }

    // ---------------------------------------------------------------- focus and gain

    @SuppressWarnings("deprecation")
    private void requestFocus() {
        if (hasFocus) return;
        hasFocus = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        if (!hasFocus) return;
        audioManager.abandonAudioFocus(focusListener);
        hasFocus = false;
        ducked = false;
    }

    // voice tts and other apps duck or pause the music through audio focus
    private void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                ducked = true;
                applyGain();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (state == State.PLAYING) {
                    try {
                        musicPlayer.pause();
                    } catch (IllegalStateException ignored) {
                    }
                    pausedForFocus = true;
                    setState(State.PAUSED);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                hasFocus = false;
                // the legacy loop never asked for focus before so a dashboard video does not stop it
                if (legacyRepeat) break;
                if (state == State.PLAYING) {
                    try {
                        musicPlayer.pause();
                    } catch (IllegalStateException ignored) {
                    }
                    setState(State.PAUSED);
                }
                pausedForFocus = false;
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                ducked = false;
                applyGain();
                if (pausedForFocus && state == State.PAUSED && musicPrepared && !announcing) {
                    pausedForFocus = false;
                    startMusicPlayer();
                }
                break;
            default:
                break;
        }
    }

    private void applyGain() {
        float gain = muted ? 0f : (ducked ? DUCK_GAIN : 1f);
        if (musicPlayer != null) {
            try {
                musicPlayer.setVolume(gain, gain);
            } catch (IllegalStateException ignored) {
            }
        }
        if (announcePlayer != null) {
            float announceGain = muted ? 0f : 1f;
            try {
                announcePlayer.setVolume(announceGain, announceGain);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- status

    private void setState(State newState) {
        state = newState;
        handler.removeCallbacks(statusTicker);
        if (newState == State.PLAYING) handler.postDelayed(statusTicker, STATUS_INTERVAL_MS);
        pushStatus();
    }

    private void pushStatus() {
        sendStatus(buildStatus());
    }

    private static void sendStatus(JSONObject status) {
        if (!ApiHub.hasController()) return;
        try {
            ApiHub.send("media_status", new JSONObject().put("status", status));
        } catch (JSONException e) {
            Log.w(TAG, "Could not build media status", e);
        }
    }

    private JSONObject buildStatus() {
        JSONObject status = new JSONObject();
        try {
            // an announcement over paused music still plays sound
            String wire = announcing ? "playing" : state.name().toLowerCase(java.util.Locale.ROOT);
            status.put("state", wire);
            MediaQueue.Track track = state != State.IDLE ? queue.current() : null;
            if (track != null) {
                putIfSet(status, "url", track.url);
                putIfSet(status, "title", track.title);
                putIfSet(status, "artist", track.artist);
                putIfSet(status, "album", track.album);
                putIfSet(status, "artwork", track.artwork);
            }
            if (musicPrepared && musicPlayer != null) {
                try {
                    status.put("position", musicPlayer.getCurrentPosition() / 1000.0);
                    int duration = musicPlayer.getDuration();
                    // live streams report no duration
                    if (duration > 0) status.put("duration", duration / 1000.0);
                } catch (IllegalStateException ignored) {
                }
            }
            status.put("volume", Math.round(getVolume() * 1000) / 1000.0);
            status.put("muted", muted);
            status.put("repeat", queue.getRepeat().wire);
            status.put("queue_size", queue.size());
            status.put("announcing", announcing);
        } catch (JSONException e) {
            Log.w(TAG, "Could not build media status", e);
        }
        lastStatus = status;
        return status;
    }

    private JSONObject idleStatus() {
        JSONObject status = new JSONObject();
        try {
            status.put("state", "idle");
            status.put("muted", false);
            status.put("repeat", MediaQueue.Repeat.OFF.wire);
            status.put("queue_size", 0);
            status.put("announcing", false);
        } catch (JSONException ignored) {
        }
        return status;
    }

    private static void putIfSet(JSONObject json, String key, String value) throws JSONException {
        if (value != null && !value.isEmpty()) json.put(key, value);
    }
}
