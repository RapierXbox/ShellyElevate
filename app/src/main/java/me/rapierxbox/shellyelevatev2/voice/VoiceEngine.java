package me.rapierxbox.shellyelevatev2.voice;

import static me.rapierxbox.shellyelevatev2.Constants.*;
import static me.rapierxbox.shellyelevatev2.ShellyElevateApplication.*;

import me.rapierxbox.shellyelevatev2.R;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.api.ApiHub;
import me.rapierxbox.shellyelevatev2.api.ControllerVoiceTransport;
import me.rapierxbox.shellyelevatev2.helper.ForegroundActivities;
import me.rapierxbox.shellyelevatev2.helper.HttpDownloader;
import me.rapierxbox.shellyelevatev2.settings.DeviceCapabilities;

// mic wake word vad and playback for voice sessions. the transport decides where a session goes
// state machine: DISABLED -> IDLE -> LISTENING -> PROCESSING -> SPEAKING -> IDLE
public class VoiceEngine {
    private static final String TAG = "VoiceEngine";

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CFG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FMT = AudioFormat.ENCODING_PCM_16BIT;
    // 100 ms of 16 khz mono pcm16 matching the wake word frontend
    private static final int CHUNK_BYTES = NativeMelExtractor.HOP_SAMPLES * 2 * 10;

    // rms based vad constants used as a fallback when the StreamingVad model is not installed
    // end of speech is declared after VAD_STOP_FRAMES of rms below noiseFloor * VAD_SPEECH_RATIO
    // VAD_SPEECH_MIN is the absolute floor so a quiet mic does not get stuck open
    private static final int VAD_NOISE_FRAMES = 8;
    private static final float VAD_SPEECH_RATIO = 3.5f;
    private static final float VAD_SPEECH_MIN = 0.006f;
    private static final int VAD_MIN_SPEECH_FRAMES = 3;
    private static final int VAD_STOP_FRAMES = 10;
    private static final float VAD_NOISE_ALPHA = 0.15f;

    private static final long ML_VAD_END_SILENCE_MS = 1_000L;
    // a session that never gets an answer frees the wake word again
    private static final long PROCESSING_TIMEOUT_SEC = 60;
    private static final long ERROR_SHOW_MS = 3_000L;
    private static final int MIC_PERMISSION_REQUEST = 4712;
    // a failed default model download is tried again after this
    private static final long MODEL_DOWNLOAD_RETRY_SEC = 120;

    public enum State { DISABLED, IDLE, LISTENING, PROCESSING, SPEAKING }

    // which transport the settings select
    private enum Mode { OFF, CONTROLLER }

    private volatile State state = State.DISABLED;
    private volatile Mode mode = Mode.OFF;
    private volatile VoiceTransport transport;
    private volatile boolean muted = false;
    private volatile long errorUntil = 0L;
    // voice is on but the app may not record so nothing would ever be heard
    private volatile boolean micPermissionMissing = false;
    // main thread only. the dialog is shown once per switch on so a denial does not loop it
    private boolean micPermissionAsked = false;
    private final AtomicBoolean modelDownloadRunning = new AtomicBoolean(false);

    // volatile for the status getters; mutations go through wakeLock so the settings
    // executor and a mute toggle cant interleave and null it mid check-then-act
    private volatile WakeWordDetector wakeDetector;
    private final Object wakeLock = new Object();
    private volatile String loadedModelName = "";
    // kept so a detector created later starts in the same mode
    private volatile boolean lowPowerMode = false;
    // the detector and the model download wait for the first controller so a display
    // that only carried voice over from an old version never records on its own
    private volatile boolean controllerSeen = false;
    private static volatile Boolean microphonePresent;

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2);
    // single thread so settings broadcasts apply in order off the main thread
    private final ExecutorService settingsExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler   = new Handler(Looper.getMainLooper());
    private final TonePlayer tonePlayer = new TonePlayer(scheduler);
    private final VoiceTimers timers = new VoiceTimers(this);

    private final AtomicBoolean audioStreaming  = new AtomicBoolean(false);
    private final AtomicBoolean speechEnded     = new AtomicBoolean(false);
    private final AtomicLong sessionId = new AtomicLong();
    private ScheduledFuture<?> maxDurationFuture;
    // main thread only
    private MediaPlayer ttsPlayer;
    // set before the player exists on the main thread so other threads see playback right away
    private volatile boolean playbackPending;
    private boolean audioFocusHeld = false;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {};
    private final BroadcastReceiver settingsReceiver;

    public VoiceEngine() {
        settingsReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { checkAndApplySettings(); }
        };
        LocalBroadcastManager.getInstance(mApplicationContext)
                .registerReceiver(settingsReceiver, new IntentFilter(INTENT_SETTINGS_CHANGED));

        muted = mSharedPreferences.getBoolean(SP_VOICE_ASSISTANT_MUTED, false);
        ControllerVoiceTransport.install(this);
        ApiHub.addControllerListener(connected -> {
            if (!connected || controllerSeen) return;
            controllerSeen = true;
            checkAndApplySettings();
        });
        // the permission dialog pauses our activity so a resume is where a grant shows up
        ForegroundActivities.INSTANCE.addResumeListener(cls -> {
            onForegroundResumed();
            return kotlin.Unit.INSTANCE;
        });
        checkAndApplySettings();
    }

    // true when voice is switched on in the settings even if it is not running yet
    public static boolean isConfigured() {
        return desiredMode() != Mode.OFF;
    }

    public void checkAndApplySettings() {
        // stopAndWait and loadModel block so never run this on the main thread
        if (settingsExecutor.isShutdown()) return;
        settingsExecutor.execute(this::applySettingsNow);
    }

    // the switch is hidden without the integration api or a microphone so it counts as off then
    private static Mode desiredMode() {
        if (!mSharedPreferences.getBoolean(SP_HA_VOICE_ENABLED, false)) return Mode.OFF;
        if (!mSharedPreferences.getBoolean(SP_INTEGRATION_API_ENABLED, true)) return Mode.OFF;
        return hasMicrophone() ? Mode.CONTROLLER : Mode.OFF;
    }

    private static boolean hasMicrophone() {
        Boolean present = microphonePresent;
        if (present == null) {
            present = DeviceCapabilities.hasMicrophone(mApplicationContext);
            microphonePresent = present;
        }
        return present;
    }

    private boolean controllerKnown() {
        if (!controllerSeen && ApiHub.hasController()) controllerSeen = true;
        return controllerSeen;
    }

    private void applySettingsNow() {
        // the mute pref can also be written over the api or the legacy settings route
        boolean wantMuted = mSharedPreferences.getBoolean(SP_VOICE_ASSISTANT_MUTED, false);
        if (wantMuted != muted) setMuted(wantMuted);
        Mode want = desiredMode();
        if (want == mode) {
            if (want != Mode.OFF) applyWakeDetectorSettings();
            return;
        }
        if (mode != Mode.OFF) {
            Log.i(TAG, "disabling " + mode);
            shutdown();
        }
        mode = want;
        if (want == Mode.OFF) {
            micPermissionMissing = false;
            ApiHub.stateChanged();
            return;
        }
        Log.i(TAG, "enabling " + want);
        mainHandler.post(() -> micPermissionAsked = false);
        VoiceTransport t = new ControllerVoiceTransport();
        transport = t;
        t.open(new TransportCallbacks(t));
        applyWakeDetectorSettings();
    }

    public void invalidateLoadedModel() {
        loadedModelName = "";
        LocalBroadcastManager.getInstance(mApplicationContext)
                .sendBroadcast(new Intent(INTENT_SETTINGS_CHANGED));
    }

    private void applyWakeDetectorSettings() {
        // serialize with setMuted so a concurrent mute cant null wakeDetector
        // between the checks below (this runs on the settings executor and the transport thread)
        synchronized (wakeLock) {
            boolean wakeEnabled = mSharedPreferences.getBoolean(SP_VOICE_WAKE_ENABLED, true);
            // checked whenever voice is on since voice.start records as well
            boolean micAllowed = mode == Mode.OFF || checkMicPermission();

            if (!wakeEnabled || muted || mode == Mode.OFF || !controllerKnown()) {
                if (wakeDetector != null) {
                    wakeDetector.onDestroy();
                    wakeDetector = null; loadedModelName = "";
                }
                return;
            }

            String modelName = mSharedPreferences.getString(SP_VOICE_WAKE_MODEL_NAME, "").trim();
            if (modelName.isEmpty()) modelName = pickDefaultModel();
            if (!WakeWordModelManager.isVadPresent(WakeWordModelManager.getModelDirectory(mApplicationContext))) {
                downloadModelsLater(false);
            }

            if (wakeDetector == null) {
                wakeDetector = new WakeWordDetector(mApplicationContext, this::onWakeDetected);
                wakeDetector.setLowPowerMode(lowPowerMode);
                loadedModelName = "";
            }

            if (!modelName.equals(loadedModelName)) {
                if (wakeDetector.isRunning()) wakeDetector.stopAndWait();
                loadedModelName = modelName;
                Log.i(TAG, "loading wake model \"" + modelName + "\" -> " + wakeDetector.loadModel(modelName));
            }

            wakeDetector.setSensitivity(mSharedPreferences.getInt(SP_VOICE_WAKE_SENSITIVITY, 50));
            wakeDetector.setCooldown(mSharedPreferences.getInt(SP_VOICE_WAKE_COOLDOWN_SEC, 5));
            wakeDetector.setScoreBroadcastEnabled(mSharedPreferences.getBoolean(SP_VOICE_SCORE_BAR_ENABLED, false));

            if (micAllowed && state == State.IDLE && !wakeDetector.isRunning()
                    && wakeDetector.getModelStatus() == WakeWordDetector.ModelStatus.LOADED) {
                wakeDetector.start();
            }
        }
    }

    // ---------------------------------------------------------------- microphone permission

    private static boolean hasMicPermission() {
        return mApplicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // false while voice may not record. the state reports it and the foreground activity asks for it
    private boolean checkMicPermission() {
        boolean missing = !hasMicPermission();
        if (missing != micPermissionMissing) {
            micPermissionMissing = missing;
            if (missing) Log.w(TAG, "voice is on but RECORD_AUDIO is not granted");
            ApiHub.stateChanged();
        }
        if (missing) mainHandler.post(this::requestMicPermission);
        return !missing;
    }

    // main thread
    private void requestMicPermission() {
        if (micPermissionAsked || mode == Mode.OFF || hasMicPermission()) return;
        Activity activity = ForegroundActivities.resumedActivity();
        if (activity == null || activity.isFinishing()) return;
        micPermissionAsked = true;
        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST);
    }

    // main thread
    private void onForegroundResumed() {
        if (mode == Mode.OFF || !micPermissionMissing) return;
        // granted in the dialog or the system settings so the detector can start now
        if (hasMicPermission()) checkAndApplySettings();
        else requestMicPermission();
    }

    // ---------------------------------------------------------------- wake word model

    // an installed model or the default one once it is downloaded. empty until then
    private String pickDefaultModel() {
        List<String> installed = availableWakeWords();
        String name;
        if (installed.contains(WakeWordModelDownloader.DEFAULT_MODEL)) {
            name = WakeWordModelDownloader.DEFAULT_MODEL;
        } else if (!installed.isEmpty()) {
            name = installed.get(0);
        } else {
            downloadModelsLater(true);
            return "";
        }
        Log.i(TAG, "no wake model selected, using installed " + name);
        mSharedPreferences.edit().putString(SP_VOICE_WAKE_MODEL_NAME, name).apply();
        return name;
    }

    // fetches the default model or the vad in the background then applies the settings again
    private void downloadModelsLater(boolean withModel) {
        if (!modelDownloadRunning.compareAndSet(false, true)) return;
        new Thread(() -> {
            boolean apply = true;
            boolean retry = false;
            try {
                File dir = WakeWordModelManager.getModelDirectory(mApplicationContext);
                if (withModel) {
                    Log.i(TAG, "no wake model installed, downloading " + WakeWordModelDownloader.DEFAULT_MODEL);
                    WakeWordModelDownloader.downloadDefault(HttpDownloader.defaultClient(), dir);
                    // the user may have picked one meanwhile
                    if (mSharedPreferences.getString(SP_VOICE_WAKE_MODEL_NAME, "").trim().isEmpty()) {
                        mSharedPreferences.edit().putString(SP_VOICE_WAKE_MODEL_NAME, WakeWordModelDownloader.DEFAULT_MODEL).apply();
                    }
                } else if (WakeWordModelManager.ensureVadDownloaded(HttpDownloader.defaultClient(), dir)
                        == WakeWordModelManager.VadResult.DOWNLOADED) {
                    // reload so the detector picks up the fresh vad
                    loadedModelName = "";
                } else {
                    apply = false;
                }
            } catch (Exception e) {
                Log.w(TAG, "wake model download failed: " + e.getMessage());
                apply = false;
                retry = withModel;
            } finally {
                modelDownloadRunning.set(false);
            }
            try {
                if (retry) scheduler.schedule(this::checkAndApplySettings, MODEL_DOWNLOAD_RETRY_SEC, TimeUnit.SECONDS);
                else if (apply) checkAndApplySettings();
            } catch (RejectedExecutionException ignored) {
                // shutting down
            }
        }, "WakeModelDownload").start();
    }

    public WakeWordDetector.ModelStatus getWakeModelStatus() {
        WakeWordDetector det = wakeDetector;
        return det != null ? det.getModelStatus() : WakeWordDetector.ModelStatus.NOT_LOADED;
    }

    public String getLoadedModelName()    { return loadedModelName; }

    public String getWakeModelDirectory() {
        WakeWordDetector det = wakeDetector;
        if (det != null) return det.getModelDirectory();
        return StreamingModel.modelDir(mApplicationContext).getAbsolutePath();
    }

    private void onWakeDetected() {
        if (mode == Mode.OFF || state != State.IDLE) return;
        Log.i(TAG, "wake detected, starting session");
        WakeWordDetector det = wakeDetector;
        if (det != null) det.stop();
        mainHandler.post(() -> mScreenSaverManager.stopScreenSaver());
        if (mSharedPreferences.getBoolean(SP_VOICE_WAKE_SOUND_ENABLED, true)) tonePlayer.playWake();
        if (mode != Mode.OFF && state == State.IDLE) trigger();
    }

    // ---------------------------------------------------------------- transport callbacks

    // ignores callbacks of a transport that was already replaced
    private final class TransportCallbacks implements VoiceTransport.Listener {
        private final VoiceTransport owner;

        TransportCallbacks(VoiceTransport owner) { this.owner = owner; }

        private boolean current() { return transport == owner; }

        @Override public void onReady() {
            if (!current()) return;
            state = State.IDLE;
            broadcastState(state);
            applyWakeDetectorSettings();
        }

        @Override public void onDisconnected(boolean keepIdle) {
            if (!current()) return;
            stopAudioCapture();
            if (state == State.LISTENING || state == State.PROCESSING) onSessionEnded();
            else if (!keepIdle && state != State.SPEAKING) { state = State.DISABLED; ApiHub.stateChanged(); }
        }

        @Override public void onSpeechEnd() {
            if (!current()) return;
            speechEnded.set(true); stopAudioCapture();
        }

        @Override public void onTranscript(String text) {
            if (!current()) return;
            Log.i(TAG, "transcript: " + text);
            broadcastText(text);
        }

        @Override public void onResponse(String text) {
            if (!current()) return;
            Log.i(TAG, "response: " + text);
            broadcastText(text);
        }

        @Override public void onTts(String url, String preannounceUrl) {
            if (!current()) return;
            state = State.SPEAKING; broadcastState(state);
            playTts(url, preannounceUrl, owner::onTtsFinished);
        }

        @Override public void onSessionEnd() {
            if (!current()) return;
            if (state != State.SPEAKING) onSessionEnded();
        }

        @Override public void onError(String message) {
            if (!current()) return;
            Log.e(TAG, "pipeline error: " + message);
            mainHandler.post(() -> Toast.makeText(mApplicationContext,
                    mApplicationContext.getString(R.string.voice_error, message), Toast.LENGTH_SHORT).show());
            stopAudioCapture();
            if (state != State.DISABLED) onSessionEnded();
        }
    }

    // ---------------------------------------------------------------- sessions

    public void trigger() {
        startSession(false);
    }

    private boolean startSession(boolean remoteInitiated) {
        if (mode == Mode.OFF)                      { Log.d(TAG, "trigger ignored: disabled");       return false; }
        if (muted)                                 { Log.d(TAG, "trigger ignored: muted");          return false; }
        if (state != State.IDLE)                   { Log.d(TAG, "trigger ignored: state=" + state); return false; }
        VoiceTransport t = transport;
        if (t == null) return false;
        if (!t.isReady()) {
            String message = t.unavailableMessage();
            if (message != null) {
                broadcastText(message);
                mainHandler.postDelayed(() -> broadcastText(""), 3_000);
                if (mSharedPreferences.getBoolean(SP_VOICE_WAKE_SOUND_ENABLED, true)) tonePlayer.playEnd();
            }
            restartDetectorLater();
            return false;
        }

        long session = sessionId.incrementAndGet();
        state = State.LISTENING;
        broadcastState(State.LISTENING);
        broadcastText(mApplicationContext.getString(R.string.voice_listening));
        speechEnded.set(false);

        String model = loadedModelName;
        t.startSession(model, model.isEmpty() ? null : phraseOf(model), remoteInitiated);

        audioStreaming.set(true);
        scheduler.execute(() -> captureAndStream(session));

        int maxSec = mSharedPreferences.getInt(SP_VOICE_ASSISTANT_MAX_RECORD_SECONDS, 10);
        synchronized (this) {
            if (maxDurationFuture != null && !maxDurationFuture.isDone()) maxDurationFuture.cancel(false);
            maxDurationFuture = scheduler.schedule(() -> {
                if (state == State.LISTENING) { Log.i(TAG, "max duration reached"); stopAudioCapture(); }
            }, maxSec, TimeUnit.SECONDS);
        }
        return true;
    }

    private void onSessionEnded() {
        state = mode != Mode.OFF ? State.IDLE : State.DISABLED;
        broadcastState(state);
        mainHandler.postDelayed(() -> broadcastText(""), 3_000);
        restartDetectorLater();
    }

    private void restartDetectorLater() {
        final WakeWordDetector detSnapshot = wakeDetector;
        if (detSnapshot == null) return;
        // restart detector after a short delay so leftover spectrogram state from
        // this session does not immediately retrigger the wake word
        scheduler.schedule(() -> {
            if (mode != Mode.OFF && state == State.IDLE && !micPermissionMissing
                    && mSharedPreferences.getBoolean(SP_VOICE_WAKE_ENABLED, true)
                    && detSnapshot == wakeDetector
                    && detSnapshot.getModelStatus() == WakeWordDetector.ModelStatus.LOADED
                    && !detSnapshot.isRunning()) {
                detSnapshot.start();
            }
        }, 1_500, TimeUnit.MILLISECONDS);
    }

    private void scheduleProcessingTimeout(long session) {
        scheduler.schedule(() -> {
            if (state == State.PROCESSING && sessionId.get() == session) {
                Log.w(TAG, "no answer for the session, back to idle");
                onSessionEnded();
            }
        }, PROCESSING_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- controller commands

    // voice.start: listen without the wake word because the controller asked
    public void startFromController(boolean startConversation) throws ApiHub.CommandException {
        if (mode != Mode.CONTROLLER) throw ApiHub.CommandException.unsupported("voice through Home Assistant is off");
        if (muted) throw new ApiHub.CommandException("busy", "microphone muted");
        if (state != State.IDLE) throw new ApiHub.CommandException("busy", "voice session running");
        if (!startSession(true)) throw new ApiHub.CommandException("busy", "voice session not started");
    }

    // voice.stop_audio: the controller has enough audio
    public void stopAudioFromController() {
        VoiceTransport t = transport;
        if (t instanceof ControllerVoiceTransport) ((ControllerVoiceTransport) t).stopAudio();
        if (state != State.LISTENING) return;
        speechEnded.set(true);
        stopAudioCapture();
    }

    // voice.state: the controller drives the on screen voice ui
    public void applyControllerState(String remoteState, String message) {
        if (mode != Mode.CONTROLLER) return;
        switch (remoteState) {
            case "processing":
                if (state == State.LISTENING) { speechEnded.set(true); stopAudioCapture(); }
                break;
            case "responding":
                if (state == State.LISTENING || state == State.PROCESSING) {
                    speechEnded.set(true); stopAudioCapture();
                    state = State.SPEAKING; broadcastState(state);
                }
                break;
            case "idle":
                // playback ends the session itself once it finished
                if (isPlaying()) return;
                if (state == State.LISTENING || state == State.PROCESSING || state == State.SPEAKING) {
                    stopAudioCapture();
                    onSessionEnded();
                }
                break;
            case "error":
                stopAudioCapture();
                errorUntil = SystemClock.elapsedRealtime() + ERROR_SHOW_MS;
                String text = message == null || message.isEmpty()
                        ? mApplicationContext.getString(R.string.voice_error, "error")
                        : mApplicationContext.getString(R.string.voice_error, message);
                if (state == State.LISTENING || state == State.PROCESSING || state == State.SPEAKING) onSessionEnded();
                else ApiHub.stateChanged();
                broadcastText(text);
                // push the return to idle once the error is no longer shown
                mainHandler.postDelayed(ApiHub::stateChanged, ERROR_SHOW_MS + 100);
                break;
            default:
                // listening follows from voice.wake or voice.start
                break;
        }
    }

    // voice.play_tts: an answer of the running session or an announcement
    public void playFromController(String url, String preannounceUrl) {
        stopAudioCapture();
        WakeWordDetector det = wakeDetector;
        // do not let the announcement trigger the wake word
        if (det != null) det.stop();
        state = State.SPEAKING; broadcastState(state);
        playTts(url, preannounceUrl, () -> ApiHub.send("voice.tts_finished", null));
    }

    public VoiceTimers timers() { return timers; }

    // ---------------------------------------------------------------- wake word config

    public List<String> availableWakeWords() {
        List<String> ids = new ArrayList<>();
        for (WakeWordModel.Installed model : WakeWordModelManager.getInstalledModels(
                WakeWordModelManager.getModelDirectory(mApplicationContext))) {
            ids.add(model.getName());
        }
        return ids;
    }

    public boolean isWakeWordAvailable(String id) {
        return availableWakeWords().contains(id);
    }

    // the wake word in use or null when detection is off
    public String activeWakeWord() {
        if (!mSharedPreferences.getBoolean(SP_VOICE_WAKE_ENABLED, true)) return null;
        String model = mSharedPreferences.getString(SP_VOICE_WAKE_MODEL_NAME, "").trim();
        return model.isEmpty() ? null : model;
    }

    // null switches wake word detection off
    public void setActiveWakeWord(String id) {
        if (id == null) {
            mSharedPreferences.edit().putBoolean(SP_VOICE_WAKE_ENABLED, false).apply();
        } else {
            mSharedPreferences.edit()
                    .putBoolean(SP_VOICE_WAKE_ENABLED, true)
                    .putString(SP_VOICE_WAKE_MODEL_NAME, id)
                    .apply();
        }
        LocalBroadcastManager.getInstance(mApplicationContext)
                .sendBroadcast(new Intent(INTENT_SETTINGS_CHANGED));
    }

    // okay_nabu or hey_jarvis_v2 -> Okay Nabu or Hey Jarvis
    public static String phraseOf(String id) {
        StringBuilder phrase = new StringBuilder();
        for (String part : id.split("[_\\-\\s]+")) {
            if (part.isEmpty() || part.matches("(?i)v\\d+")) continue;
            if (phrase.length() > 0) phrase.append(' ');
            phrase.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return phrase.length() > 0 ? phrase.toString() : id;
    }

    // ---------------------------------------------------------------- status

    public State   getState()     { return state;   }
    public boolean isEnabled()    { return mode != Mode.OFF; }
    public boolean isMuted()      { return muted;    }

    public String getPublishedStatus() {
        if (muted) return VOICE_STATUS_MUTED;
        switch (state) {
            case LISTENING:  return VOICE_STATUS_LISTENING;
            case PROCESSING:
            case SPEAKING:   return VOICE_STATUS_ANSWERING;
            default:         return VOICE_STATUS_READY;
        }
    }

    // voice.state of protocol v1
    public String getProtocolState() {
        if (mode != Mode.OFF && micPermissionMissing) return "error";
        if (SystemClock.elapsedRealtime() < errorUntil) return "error";
        switch (state) {
            case IDLE:       return "idle";
            case LISTENING:  return "listening";
            case PROCESSING: return "processing";
            case SPEAKING:   return "responding";
            default:         return "disabled";
        }
    }

    // why voice.state is error on the display side or null
    public String getProtocolError() {
        if (mode != Mode.OFF && micPermissionMissing) return "microphone permission missing";
        return null;
    }

    public void setLowPowerMode(boolean low) {
        lowPowerMode = low;
        WakeWordDetector det = wakeDetector;
        if (det != null) det.setLowPowerMode(low);
    }

    public void setMuted(boolean mute) {
        if (muted == mute) return;
        muted = mute;
        mSharedPreferences.edit().putBoolean(SP_VOICE_ASSISTANT_MUTED, mute).apply();
        Log.i(TAG, "mute -> " + mute);
        if (mute) {
            stopAudioCapture();
            if (state == State.LISTENING || state == State.PROCESSING) onSessionEnded();
            synchronized (wakeLock) {
                if (wakeDetector != null) {
                    wakeDetector.onDestroy();
                    wakeDetector = null; loadedModelName = "";
                }
            }
        } else if (mode != Mode.OFF) {
            settingsExecutor.execute(this::applyWakeDetectorSettings);
        }
        broadcastState(state);
    }

    public void showText(String text) {
        broadcastText(text);
    }

    public void playAlertTone() {
        tonePlayer.playWake();
    }

    private void broadcastState(State s) {
        Intent intent = new Intent(INTENT_VOICE_STATE_CHANGED).putExtra(INTENT_VOICE_STATE_KEY, s.name());
        LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(intent);
        ApiHub.stateChanged();
    }

    private void broadcastText(String text) {
        Intent intent = new Intent(INTENT_VOICE_TEXT).putExtra(INTENT_VOICE_TEXT_KEY, text != null ? text : "");
        LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(intent);
    }

    // ---------------------------------------------------------------- capture

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private void captureAndStream(long session) {
        // the mic is exclusive so the detector has to let go first
        WakeWordDetector det = wakeDetector;
        if (det != null && det.isRunning()) det.stopAndWait();

        int minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FMT);
        int bufSize = Math.max(minBuf > 0 ? minBuf : CHUNK_BYTES, CHUNK_BYTES * 4);

        AudioRecord recorder = null;
        boolean micFailed = false;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CFG, AUDIO_FMT, bufSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed");
                micFailed = true;
                mainHandler.post(() -> Toast.makeText(mApplicationContext,
                        mApplicationContext.getString(R.string.voice_error, "Microphone unavailable"),
                        Toast.LENGTH_SHORT).show());
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

            try (StreamingVad mlVad = new StreamingVad(mApplicationContext)) {
                final boolean mlVadActive = mlVad.hasModel();
                while (audioStreaming.get() && state == State.LISTENING && !speechEnded.get()) {
                    int read;
                    try { read = recorder.read(buf, 0, CHUNK_BYTES); }
                    catch (IllegalStateException e) { Log.d(TAG, "read interrupted"); break; }

                    if (read > 0) {
                        VoiceTransport t = transport;
                        if (t != null) t.sendAudio(buf, read);

                        mlVad.feed(buf, read);

                        float rms = WakeWordDetector.calculateRms(buf, read);
                        if (noiseFrames < VAD_NOISE_FRAMES && rms < VAD_SPEECH_MIN * 3) {
                            noiseFloor = noiseFrames == 0 ? rms
                                    : noiseFloor * (1f - VAD_NOISE_ALPHA) + rms * VAD_NOISE_ALPHA;
                            noiseFrames++;
                        }
                        float speechThreshold = Math.max(VAD_SPEECH_MIN, noiseFloor * VAD_SPEECH_RATIO);

                        if (mlVadActive) {
                            if (mlVad.everActive()) speechStarted = true;
                            long silentMs = mlVad.silenceMsSinceSpeech();
                            if (speechStarted && silentMs >= ML_VAD_END_SILENCE_MS) {
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "ML VAD: silence after speech (" + silentMs + "ms), ending capture");
                                }
                                break;
                            }
                            // track rms frames as a sanity check signal even when the ml vad
                            // drives end of speech so the fallback counters stay meaningful
                            // if we ever flip back to rms only mode
                            if (rms < speechThreshold) speechFrames = 0;
                            else speechFrames++;
                        } else {
                            if (rms >= speechThreshold) {
                                silentFrames = 0;
                                if (++speechFrames >= VAD_MIN_SPEECH_FRAMES) speechStarted = true;
                            } else {
                                speechFrames = 0;
                                if (speechStarted && ++silentFrames >= VAD_STOP_FRAMES) {
                                    if (BuildConfig.DEBUG) {
                                        Log.d(TAG, "RMS VAD: silence after speech, ending capture"
                                                + " (floor=" + String.format("%.4f", noiseFloor)
                                                + " thr=" + String.format("%.4f", speechThreshold) + ")");
                                    }
                                    break;
                                }
                            }
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "read error: " + read); break;
                    }
                }
            }

            try { recorder.stop(); } catch (Exception ignored) {}
            if (speechStarted && soundEnabled) tonePlayer.playEnd();

        } catch (Exception e) {
            Log.e(TAG, "capture error", e);
        } finally {
            if (recorder != null) { try { recorder.release(); } catch (Exception ignored) {} }
            VoiceTransport t = transport;
            if (t != null && state != State.DISABLED && sessionId.get() == session) {
                t.endAudio();
                if (micFailed) {
                    stopAudioCapture();
                    onSessionEnded();
                } else if (state == State.LISTENING) {
                    state = State.PROCESSING; broadcastState(state);
                    scheduleProcessingTimeout(session);
                }
            }
        }
    }

    // synchronized so the future cancel and null swap is atomic across threads
    private synchronized void stopAudioCapture() {
        audioStreaming.set(false);
        if (maxDurationFuture != null) { maxDurationFuture.cancel(false); maxDurationFuture = null; }
    }

    // ---------------------------------------------------------------- playback

    // plays the optional chime then the answer and runs onDone after the last one
    private void playTts(String url, String preannounceUrl, Runnable onDone) {
        playbackPending = true;
        mainHandler.post(() -> {
            releaseTtsPlayer();
            requestAudioFocus();
            if (preannounceUrl != null) {
                playOne(preannounceUrl, () -> playOne(url, () -> finishPlayback(onDone)));
            } else {
                playOne(url, () -> finishPlayback(onDone));
            }
        });
    }

    // main thread. next runs after the url ended or failed
    private void playOne(String url, Runnable next) {
        releasePlayerOnly();
        MediaPlayer player = new MediaPlayer();
        ttsPlayer = player;
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        try {
            player.setDataSource(url);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> { if (ttsPlayer == mp) next.run(); });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "TTS error " + what + "/" + extra);
                if (ttsPlayer == mp) next.run();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "TTS start failed", e);
            next.run();
        }
    }

    private void finishPlayback(Runnable onDone) {
        playbackPending = false;
        releaseTtsPlayer();
        onSessionEnded();
        if (onDone != null) onDone.run();
    }

    private boolean isPlaying() {
        return playbackPending || ttsPlayer != null;
    }

    private void releasePlayerOnly() {
        if (ttsPlayer != null) { try { ttsPlayer.release(); } catch (Exception ignored) {} ttsPlayer = null; }
    }

    private void releaseTtsPlayer() {
        releasePlayerOnly();
        abandonAudioFocus();
    }

    // lets a music player duck or pause while the answer plays
    @SuppressWarnings("deprecation")
    private void requestAudioFocus() {
        if (audioFocusHeld) return;
        AudioManager am = (AudioManager) mApplicationContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        audioFocusHeld = am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private void abandonAudioFocus() {
        if (!audioFocusHeld) return;
        audioFocusHeld = false;
        AudioManager am = (AudioManager) mApplicationContext.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.abandonAudioFocus(focusListener);
    }

    // ---------------------------------------------------------------- lifecycle

    private void shutdown() {
        stopAudioCapture();
        state = State.DISABLED;
        playbackPending = false;
        mainHandler.post(this::releaseTtsPlayer);
        // go through wakeLock so this cant race with a concurrent settings or mute update
        synchronized (wakeLock) {
            if (wakeDetector != null) {
                wakeDetector.onDestroy();
                wakeDetector = null;
            }
            loadedModelName = "";
        }
        VoiceTransport t = transport;
        transport = null;
        if (t != null) t.close();
    }

    public void onDestroy() {
        mode = Mode.OFF;
        shutdown();
        timers.onDestroy();
        LocalBroadcastManager.getInstance(mApplicationContext).unregisterReceiver(settingsReceiver);
        settingsExecutor.shutdownNow();
        scheduler.shutdownNow();
    }
}
