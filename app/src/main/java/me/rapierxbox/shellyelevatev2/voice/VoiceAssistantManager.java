package me.rapierxbox.shellyelevatev2.voice;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.*;

import me.rapierxbox.shellyelevatev2.R;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.RequiresPermission;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import okhttp3.OkHttpClient;

// state machine: diabled -> idle -> listening -> processing -> speaking -> idle
public class VoiceAssistantManager {
    private static final String TAG = "VoiceAssistant";

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CFG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FMT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHUNK_BYTES = 3200; // 100ms at 16khz 16bit

    // vad parameters
    private static final int VAD_NOISE_FRAMES = 8;    // noisefloor gets measured for baseline
    private static final float VAD_SPEECH_RATIO = 3.5f; // speech threshold = noiseFloor × ratio
    private static final float VAD_SPEECH_MIN = 0.006f; // abs floor for speech threshold
    private static final int VAD_MIN_SPEECH_FRAMES = 3; // min speech frames before VAD activates
    private static final int VAD_STOP_FRAMES = 10;   // silent frames needed to stop
    private static final float VAD_NOISE_ALPHA = 0.15f; // ema weight for noise floor update

    public enum State { DISABLED, IDLE, LISTENING, PROCESSING, SPEAKING }

    private volatile State state = State.DISABLED;
    private volatile boolean enabled = false;
    private volatile boolean manuallyDisabled = false;
    private volatile int reconnectDelaySec = 5;

    private final OkHttpClient okHttpClient;
    private HAVoicePipeline pipeline;
    private WakeWordDetector wakeDetector;
    private volatile String loadedModelName = "";

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2);
    private final Handler mainHandler   = new Handler(Looper.getMainLooper());

    private static final int TONE_SAMPLE_RATE = 44100;
    private static final double WAKE_TONE_DURATION_SEC = 0.8;
    private static final double END_TONE_DURATION_SEC = 0.5;
    private final short[] wakeToneBuffer = renderTone(660.0, WAKE_TONE_DURATION_SEC, 5.0);
    private final short[] endToneBuffer  = renderTone(440.0, END_TONE_DURATION_SEC, 9.0);

    private final AtomicBoolean audioStreaming  = new AtomicBoolean(false);
    private final AtomicBoolean speechEnded     = new AtomicBoolean(false);
    private ScheduledFuture<?> maxDurationFuture;
    private MediaPlayer ttsPlayer;
    private BroadcastReceiver settingsReceiver;

    public VoiceAssistantManager() {
        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        settingsReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { checkAndApplySettings(); }
        };
        LocalBroadcastManager.getInstance(mApplicationContext)
                .registerReceiver(settingsReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));

        checkAndApplySettings();
    }

    public void checkAndApplySettings() {
        boolean wantEnabled = mSharedPreferences.getBoolean(SP_VOICE_ASSISTANT_ENABLED, false);
        String token = mSharedPreferences.getString(SP_VOICE_ASSISTANT_TOKEN, "");
        String haUrl = mSharedPreferences.getString(SP_WEBVIEW_URL, "");
        boolean canEnable = wantEnabled && !token.isEmpty() && !haUrl.isEmpty();

        if (canEnable && !enabled) {
            enabled = true; manuallyDisabled = false; reconnectDelaySec = 5;
            Log.i(TAG, "enabling");
            connect();
            applyWakeDetectorSettings();
        } else if (canEnable) {
            applyWakeDetectorSettings();
        } else if (enabled || state != State.DISABLED) {
            enabled = false; manuallyDisabled = true;
            Log.i(TAG, "disabling");
            shutdown();
        }
    }

    private void applyWakeDetectorSettings() {
        boolean wakeEnabled = mSharedPreferences.getBoolean(SP_VOICE_WAKE_ENABLED, true);

        if (!wakeEnabled) {
            if (wakeDetector != null) {
                wakeDetector.stop(); wakeDetector.onDestroy();
                wakeDetector = null; loadedModelName = "";
            }
            return;
        }

        String modelName = mSharedPreferences.getString(SP_VOICE_WAKE_MODEL_NAME, "").trim();

        if (wakeDetector == null) {
            wakeDetector = new WakeWordDetector(mApplicationContext, this::onWakeDetected);
            loadedModelName = "";
        }

        if (!modelName.equals(loadedModelName)) {
            loadedModelName = modelName;
            Log.i(TAG, "loading wake model \"" + modelName + "\" -> " + wakeDetector.loadModel(modelName));
        }

        wakeDetector.setSensitivity(mSharedPreferences.getInt(SP_VOICE_WAKE_SENSITIVITY, 50));
        wakeDetector.setCooldown(mSharedPreferences.getInt(SP_VOICE_WAKE_COOLDOWN_SEC, 5));
        wakeDetector.setScoreBroadcastEnabled(mSharedPreferences.getBoolean(SP_VOICE_SCORE_BAR_ENABLED, false));

        if (state == State.IDLE && !wakeDetector.isRunning()
                && wakeDetector.getModelStatus() == WakeWordDetector.ModelStatus.LOADED) {
            wakeDetector.start();
        }
    }

    public WakeWordDetector.ModelStatus getWakeModelStatus() {
        return wakeDetector != null ? wakeDetector.getModelStatus() : WakeWordDetector.ModelStatus.NOT_LOADED;
    }

    public String getLoadedModelName()    { return loadedModelName; }

    public String getWakeModelDirectory() {
        if (wakeDetector != null) return wakeDetector.getModelDirectory();
        return new java.io.File(mApplicationContext.getFilesDir(), "wakewords").getAbsolutePath();
    }

    private void onWakeDetected() {
        if (!enabled || state != State.IDLE) return;
        Log.i(TAG, "wake detected... starting session");
        if (wakeDetector != null) wakeDetector.stop();
        mainHandler.post(() -> {
            mScreenSaverManager.stopScreenSaver();
        });
        if (mSharedPreferences.getBoolean(SP_VOICE_WAKE_SOUND_ENABLED, true)) playWakeSound();
        if (enabled && state == State.IDLE) trigger();
    }

    private void playWakeSound() { playTone(wakeToneBuffer, WAKE_TONE_DURATION_SEC); }
    private void playEndSound()  { playTone(endToneBuffer,  END_TONE_DURATION_SEC); }

    private static short[] renderTone(double freq, double durationSec, double decayConstant) {
        int numSamples = (int) (TONE_SAMPLE_RATE * durationSec);
        int attackSamples = TONE_SAMPLE_RATE / 200;
        short[] buf = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            double attack = i < attackSamples ? (double) i / attackSamples : 1.0;
            double envelope = attack * Math.exp(-decayConstant * i / numSamples);
            buf[i] = (short) (Short.MAX_VALUE * 0.75 * envelope
                    * Math.sin(2.0 * Math.PI * freq * i / TONE_SAMPLE_RATE));
        }
        return buf;
    }

    private void playTone(short[] buf, double durationSec) {
        scheduler.execute(() -> {
            AudioTrack track = null;
            try {
                track = new AudioTrack(AudioManager.STREAM_MUSIC, TONE_SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        buf.length * 2, AudioTrack.MODE_STATIC);
                track.write(buf, 0, buf.length);
                track.play();
                Thread.sleep((long) (durationSec * 1000) + 200);
                track.stop();
            } catch (Exception e) {
                Log.w(TAG, "could not play tone", e);
            } finally {
                if (track != null) { try { track.release(); } catch (Exception ignored) {} }
            }
        });
    }

    private void connect() {
        if (!enabled || manuallyDisabled) return;
        String haUrl = mSharedPreferences.getString(SP_WEBVIEW_URL, "");
        String token = mSharedPreferences.getString(SP_VOICE_ASSISTANT_TOKEN, "");
        if (haUrl.isEmpty() || token.isEmpty()) { Log.w(TAG, "missing URL or token"); return; }

        pipeline = new HAVoicePipeline(okHttpClient, new HAVoicePipeline.Callback() {
            @Override public void onConnected() {}

            @Override public void onAuthOk() {
                Log.i(TAG, "authenticated... ready");
                reconnectDelaySec = 5;
                state = State.IDLE;
                applyWakeDetectorSettings();
            }

            @Override public void onListening() { Log.d(TAG, "streaming audio to HA"); }

            @Override public void onTranscript(String text) {
                Log.i(TAG, "transcript: " + text);
                broadcastText(text);
            }

            @Override public void onResponse(String text) {
                Log.i(TAG, "response: " + text);
                broadcastText(text);
            }

            @Override public void onSpeechEnd() {
                speechEnded.set(true); stopAudioCapture();
            }

            @Override public void onTtsUrl(String url) {
                state = State.SPEAKING; playTts(url);
            }

            @Override public void onPipelineEnd() {
                if (state != State.SPEAKING) onSessionEnded();
            }

            @Override public void onError(String message) {
                Log.e(TAG, "pipeline error: " + message);
                mainHandler.post(() -> Toast.makeText(mApplicationContext,
                        mApplicationContext.getString(R.string.voice_error, message), Toast.LENGTH_SHORT).show());
                stopAudioCapture();
                if (state != State.DISABLED) onSessionEnded();
            }

            @Override public void onDisconnected() {
                Log.w(TAG, "disconnected");
                stopAudioCapture();
                if (state == State.LISTENING || state == State.PROCESSING) onSessionEnded();
                else state = State.DISABLED;
                if (enabled && !manuallyDisabled) scheduleReconnect();
            }
        });

        pipeline.connect(haUrl, token);
    }

    private void scheduleReconnect() {
        if (!enabled || manuallyDisabled || scheduler.isShutdown()) return;
        int delay = reconnectDelaySec;
        reconnectDelaySec = Math.min(reconnectDelaySec * 2, 60);
        Log.i(TAG, "reconnecting in " + delay + "s");
        scheduler.schedule(() -> { if (enabled && !manuallyDisabled) connect(); }, delay, TimeUnit.SECONDS);
    }

    public void trigger() {
        if (!enabled)                              { Log.d(TAG, "trigger ignored: disabled");       return; }
        if (state != State.IDLE)                   { Log.d(TAG, "trigger ignored: state=" + state); return; }
        if (pipeline == null || !pipeline.isAuthenticated()) { Log.w(TAG, "trigger: not authenticated"); return; }

        state = State.LISTENING;
        broadcastState(State.LISTENING);
        broadcastText(mApplicationContext.getString(R.string.voice_listening));
        speechEnded.set(false);

        String pipelineId = mSharedPreferences.getString(SP_VOICE_ASSISTANT_PIPELINE_ID, "");
        pipeline.startPipeline(pipelineId.isEmpty() ? null : pipelineId);

        audioStreaming.set(true);
        scheduler.execute(this::captureAndStream);

        int maxSec = mSharedPreferences.getInt(SP_VOICE_ASSISTANT_MAX_RECORD_SECONDS, 10);
        if (maxDurationFuture != null && !maxDurationFuture.isDone()) maxDurationFuture.cancel(false);
        maxDurationFuture = scheduler.schedule(() -> {
            if (state == State.LISTENING) { Log.i(TAG, "max duration reached"); stopAudioCapture(); }
        }, maxSec, TimeUnit.SECONDS);
    }

    private void onSessionEnded() {
        state = State.IDLE;
        broadcastState(State.IDLE);
        mainHandler.postDelayed(() -> broadcastText(""), 3_000);
        if (wakeDetector != null) { // delay (could also be fixed by just sending clear spectogram into model instead of reusing old one)
            scheduler.schedule(() -> {
                if (enabled && state == State.IDLE
                        && mSharedPreferences.getBoolean(SP_VOICE_WAKE_ENABLED, true)
                        && wakeDetector.getModelStatus() == WakeWordDetector.ModelStatus.LOADED
                        && !wakeDetector.isRunning()) {
                    wakeDetector.start();
                }
            }, 1_500, TimeUnit.MILLISECONDS);
        }
    }

    public State   getState()     { return state;   }
    public boolean isEnabled()    { return enabled;  }

    private void broadcastState(State s) {
        Intent intent = new Intent(INTENT_VOICE_STATE_CHANGED).putExtra(INTENT_VOICE_STATE_KEY, s.name());
        LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(intent);
    }

    private void broadcastText(String text) {
        Intent intent = new Intent(INTENT_VOICE_TEXT).putExtra(INTENT_VOICE_TEXT_KEY, text != null ? text : "");
        LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(intent);
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private void captureAndStream() {
        int minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FMT);
        int bufSize = Math.max(minBuf > 0 ? minBuf : CHUNK_BYTES, CHUNK_BYTES * 4);

        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CFG, AUDIO_FMT, bufSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed");
                mainHandler.post(() -> {
                    Toast.makeText(mApplicationContext,
                            mApplicationContext.getString(R.string.voice_error, "Microphone unavailable"),
                            Toast.LENGTH_SHORT).show();
                    state = State.IDLE;
                });
                return;
            }

            recorder.startRecording();
            byte[] buf = new byte[CHUNK_BYTES];
            boolean soundEnabled = mSharedPreferences.getBoolean(SP_VOICE_WAKE_SOUND_ENABLED, true);

            float noiseFloor = VAD_SPEECH_MIN / VAD_SPEECH_RATIO;
            int noiseFrames = 0;
            int speechFrames = 0;
            int silentFrames = 0;
            boolean speechStarted = false;

            while (audioStreaming.get() && state == State.LISTENING && !speechEnded.get()) {
                int read;
                try { read = recorder.read(buf, 0, CHUNK_BYTES); }
                catch (IllegalStateException e) { Log.d(TAG, "read interrupted"); break; }

                if (read > 0) {
                    if (pipeline != null) pipeline.sendAudio(buf, read);

                    float rms = WakeWordDetector.calculateRms(buf, read);

                    // build noise floor est
                    if (noiseFrames < VAD_NOISE_FRAMES && rms < VAD_SPEECH_MIN * 3) {
                        noiseFloor = noiseFrames == 0 ? rms
                                : noiseFloor * (1f - VAD_NOISE_ALPHA) + rms * VAD_NOISE_ALPHA;
                        noiseFrames++;
                    }

                    float speechThreshold = Math.max(VAD_SPEECH_MIN, noiseFloor * VAD_SPEECH_RATIO);

                    if (rms >= speechThreshold) {
                        silentFrames = 0;
                        if (++speechFrames >= VAD_MIN_SPEECH_FRAMES) speechStarted = true;
                    } else {
                        speechFrames = 0;
                        if (speechStarted && ++silentFrames >= VAD_STOP_FRAMES) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "local VAD: silence after speech, ending capture"
                                        + " (floor=" + String.format("%.4f", noiseFloor)
                                        + " thr=" + String.format("%.4f", speechThreshold) + ")");
                            }
                            break;
                        }
                    }
                } else if (read < 0) {
                    Log.e(TAG, "read error: " + read); break;
                }
            }

            try { recorder.stop(); } catch (Exception ignored) {}
            if (speechStarted && soundEnabled) playEndSound();

        } catch (Exception e) {
            Log.e(TAG, "capture error", e);
        } finally {
            if (recorder != null) { try { recorder.release(); } catch (Exception ignored) {} }
            if (pipeline != null && state != State.DISABLED) {
                pipeline.endAudio();
                if (state == State.LISTENING) state = State.PROCESSING;
            }
        }
    }

    private void stopAudioCapture() {
        audioStreaming.set(false);
        if (maxDurationFuture != null) { maxDurationFuture.cancel(false); maxDurationFuture = null; }
    }

    private void playTts(String url) {
        mainHandler.post(() -> {
            releaseTtsPlayer();
            ttsPlayer = new MediaPlayer();
            ttsPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            try {
                ttsPlayer.setDataSource(url);
                ttsPlayer.setOnPreparedListener(MediaPlayer::start);
                ttsPlayer.setOnCompletionListener(mp -> { releaseTtsPlayer(); onSessionEnded(); });
                ttsPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "TTS error " + what + "/" + extra);
                    releaseTtsPlayer(); onSessionEnded(); return true;
                });
                ttsPlayer.prepareAsync();
            } catch (Exception e) {
                Log.e(TAG, "TTS start failed", e); releaseTtsPlayer(); onSessionEnded();
            }
        });
    }

    private void releaseTtsPlayer() {
        if (ttsPlayer != null) { try { ttsPlayer.release(); } catch (Exception ignored) {} ttsPlayer = null; }
    }

    private void shutdown() {
        stopAudioCapture();
        state = State.DISABLED;
        mainHandler.post(this::releaseTtsPlayer);
        if (wakeDetector != null) {
            wakeDetector.stopAndWait(); // should be safe here
            wakeDetector.onDestroy();
            wakeDetector = null;
        }
        if (pipeline != null) { pipeline.close(); pipeline = null; }
    }

    public void onDestroy() {
        manuallyDisabled = true; enabled = false;
        shutdown();
        LocalBroadcastManager.getInstance(mApplicationContext).unregisterReceiver(settingsReceiver);
        scheduler.shutdownNow();
        okHttpClient.dispatcher().executorService().shutdown();
        okHttpClient.connectionPool().evictAll();
    }
}
