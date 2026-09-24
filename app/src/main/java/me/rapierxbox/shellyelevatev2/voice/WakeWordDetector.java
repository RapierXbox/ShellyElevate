package me.rapierxbox.shellyelevatev2.voice;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.Constants;

// generic microwakeword runner for any model with the mww tensor layout
// though only okay nabu is exercised in ci
// models live in files/wakewords/<name>.tflite plus an optional <name>.json
// all model and stream state is guarded by this so a reload or destroy can never
// close an interpreter while the listen loop is inside an inference
public class WakeWordDetector {
    private static final String TAG = "WakeWordDetector";

    private static final int SAMPLE_RATE = NativeMelExtractor.SAMPLE_RATE;
    private static final int CHANNEL_CFG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FMT = AudioFormat.ENCODING_PCM_16BIT;
    // 100 ms which is 10 mel hops
    private static final int CHUNK_BYTES = NativeMelExtractor.HOP_SAMPLES * 2 * 10;
    private static final long SHUTDOWN_TIMEOUT_MS = 1_000L;
    private static final long SCORE_BROADCAST_INTERVAL_MS = 50L;
    private static final int MIN_SLICES_BEFORE_DETECTION = 100;

    // mww v2 defaults used when the companion json leaves them out
    private static final int DEFAULT_WAKE_WINDOW = 10;
    private static final int DEFAULT_VAD_WINDOW = 5;
    private static final float DEFAULT_CUTOFF = 0.5f;

    public static final String VAD_MODEL_NAME = "vad";

    public enum ModelStatus { NOT_LOADED, LOADED, FILE_NOT_FOUND, LOAD_ERROR }

    public interface Callback {
        void onWakeDetected();
    }

    private final Context context;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<AudioRecord> activeRecorder = new AtomicReference<>();
    // bumped on every start so a stale loop cannot clobber the next session
    private final AtomicLong sessionId = new AtomicLong();
    private volatile CountDownLatch shutdownLatch;

    private volatile ModelStatus modelStatus = ModelStatus.NOT_LOADED;
    private volatile float scoreThreshold = DEFAULT_CUTOFF;
    private volatile float baseThreshold = DEFAULT_CUTOFF;
    private volatile long cooldownMs = 5_000L;
    private volatile boolean scoreBroadcastEnabled = false;
    private volatile boolean lowPowerMode = false;

    // wake model state
    private StreamingModel wakeModel;
    private StreamingModel.ScoreWindow scoreWindow;
    private int positiveOutputIdx = 0;
    private int wakeIgnoreWindows = -MIN_SLICES_BEFORE_DETECTION;
    private float lastRawScore = 0f;
    private boolean skipNextInference = false;
    private long lastTriggerAt = 0L;
    // set during a chunk so the callback runs after the lock is released
    private boolean wakePending = false;

    // optional vad model that gates wake detections on voice activity
    private StreamingModel vadModel;
    private StreamingModel.ScoreWindow vadScoreWindow;
    private int vadPositiveOutputIdx = 0;
    private float vadThreshold = DEFAULT_CUTOFF;
    private boolean vadDetected = false;

    private int debugInferCount = 0;
    private float debugMaxScore = 0f;
    private long lastScoreBroadcastMs = 0;

    public WakeWordDetector(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    public synchronized ModelStatus loadModel(String modelName) {
        closeModel();

        if (modelName == null || modelName.trim().isEmpty()) {
            modelStatus = ModelStatus.NOT_LOADED;
            return modelStatus;
        }
        String name = modelName.trim();

        File dir = StreamingModel.modelDir(context);
        File file = new File(dir, name + ".tflite");
        if (!file.exists()) {
            Log.w(TAG, "model file not found: " + file.getAbsolutePath());
            modelStatus = ModelStatus.FILE_NOT_FOUND;
            return modelStatus;
        }

        try {
            wakeModel = StreamingModel.load(file, true);
            StreamingModel.Config cfg = StreamingModel.Config.read(
                    new File(dir, name + ".json"), wakeModel.outputCols, DEFAULT_WAKE_WINDOW, DEFAULT_CUTOFF);
            baseThreshold = cfg.cutoff;
            if (cfg.cutoffFromJson) scoreThreshold = baseThreshold;
            positiveOutputIdx = cfg.positiveIdx;
            scoreWindow = new StreamingModel.ScoreWindow(cfg.windowSize);

            Log.i(TAG, "loaded " + modelName + " " + wakeModel.describe()
                    + " window=" + cfg.windowSize
                    + " cutoff=" + baseThreshold
                    + " posIdx=" + positiveOutputIdx);
            modelStatus = ModelStatus.LOADED;

            loadVadModel(dir);
        } catch (Exception e) {
            Log.e(TAG, "failed to load " + modelName, e);
            closeModel();
            modelStatus = ModelStatus.LOAD_ERROR;
        }
        return modelStatus;
    }

    private void loadVadModel(File dir) {
        closeVadModel();
        File file = new File(dir, VAD_MODEL_NAME + ".tflite");
        if (!file.exists()) {
            Log.w(TAG, "VAD model not present, wake detection will not be gated on voice activity");
            return;
        }
        try {
            vadModel = StreamingModel.load(file, false);
            StreamingModel.Config cfg = StreamingModel.Config.read(
                    new File(dir, VAD_MODEL_NAME + ".json"), vadModel.outputCols, DEFAULT_VAD_WINDOW, DEFAULT_CUTOFF);
            vadThreshold = cfg.cutoff;
            vadPositiveOutputIdx = cfg.positiveIdx;
            vadScoreWindow = new StreamingModel.ScoreWindow(cfg.windowSize);

            Log.i(TAG, "VAD loaded " + vadModel.describe()
                    + " window=" + cfg.windowSize
                    + " cutoff=" + vadThreshold
                    + " posIdx=" + vadPositiveOutputIdx);
        } catch (Exception e) {
            Log.e(TAG, "failed to load VAD model", e);
            closeVadModel();
        }
    }

    // callers hold the lock
    private void closeModel() {
        if (wakeModel != null) {
            wakeModel.close();
            wakeModel = null;
        }
        scoreWindow = null;
        baseThreshold = DEFAULT_CUTOFF;
        positiveOutputIdx = 0;
        wakeIgnoreWindows = -MIN_SLICES_BEFORE_DETECTION;
        lastRawScore = 0f;
        closeVadModel();
        modelStatus = ModelStatus.NOT_LOADED;
    }

    private void closeVadModel() {
        if (vadModel != null) {
            vadModel.close();
            vadModel = null;
        }
        vadScoreWindow = null;
        vadThreshold = DEFAULT_CUTOFF;
        vadPositiveOutputIdx = 0;
        vadDetected = false;
    }

    public ModelStatus getModelStatus() { return modelStatus; }

    public String getModelDirectory() { return StreamingModel.modelDir(context).getAbsolutePath(); }

    // maps the 0..100 slider around the published cutoff where 50 is the cutoff
    // itself and 100 or 0 move it 0.4 towards more or less sensitive
    public void setSensitivity(int sensitivity) {
        float delta = (50 - sensitivity) / 100f * 0.8f;
        scoreThreshold = Math.max(0.01f, Math.min(0.99f, baseThreshold + delta));
    }

    public void setCooldown(int seconds) {
        cooldownMs = seconds * 1000L;
    }

    public void setScoreBroadcastEnabled(boolean enabled) {
        scoreBroadcastEnabled = enabled;
    }

    // skips every other inference for roughly half the cpu at twice the latency
    public void setLowPowerMode(boolean low) {
        lowPowerMode = low;
    }

    public void start() {
        if (modelStatus != ModelStatus.LOADED) {
            Log.w(TAG, "can't start: model not loaded (" + modelStatus + ")");
            return;
        }
        if (running.getAndSet(true)) return;
        // the loop gets its own token and latch so a queued stale loop can
        // never adopt the state of a newer session
        final long session = sessionId.incrementAndGet();
        final CountDownLatch latch = new CountDownLatch(1);
        shutdownLatch = latch;
        try {
            executor.execute(() -> listenLoop(session, latch));
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "can't start: detector already destroyed");
            running.set(false);
            latch.countDown();
            return;
        }
        Log.i(TAG, "detector started");
    }

    // blocks until the mic is released or the shutdown timeout elapses
    public boolean stopAndWait() {
        if (!running.getAndSet(false)) {
            // a loop stopped via stop() may still be unwinding so wait for it
            return awaitShutdown();
        }
        Log.i(TAG, "detector stopping (waiting for mic release)");
        stopActiveRecorder();
        return awaitShutdown();
    }

    // non blocking variant where the loop unwinds on the executor thread
    public void stop() {
        if (!running.getAndSet(false)) return;
        Log.i(TAG, "detector stopping (non blocking)");
        stopActiveRecorder();
    }

    public boolean isRunning() { return running.get(); }

    public void onDestroy() {
        stopAndWait();
        synchronized (this) {
            closeModel();
        }
        executor.shutdownNow();
    }

    // stopping the recorder is what unblocks a pending read on the loop
    private void stopActiveRecorder() {
        AudioRecord r = activeRecorder.get();
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
        }
    }

    private boolean awaitShutdown() {
        CountDownLatch latch = shutdownLatch;
        if (latch == null) return true;
        try {
            boolean ok = latch.await(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) {
                Log.w(TAG, "shutdown timeout, force releasing");
                forceReleaseRecorder();
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void forceReleaseRecorder() {
        AudioRecord r = activeRecorder.getAndSet(null);
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
            try { r.release(); } catch (Exception ignored) {}
            Log.d(TAG, "force-released recorder");
        }
    }

    private boolean isCurrentSession(long session) {
        return running.get() && session == sessionId.get();
    }

    private void listenLoop(long session, CountDownLatch latch) {
        AudioRecord recorder = null;
        try {
            if (!isCurrentSession(session)) return;
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "RECORD_AUDIO permission not granted");
                return;
            }

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FMT);
            if (minBuf == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "audio input not supported by hardware (rate=" + SAMPLE_RATE + ")");
                return;
            }
            int bufSize = Math.max(minBuf > 0 ? minBuf : CHUNK_BYTES, CHUNK_BYTES * 4);

            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CFG, AUDIO_FMT, bufSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed (state=" + recorder.getState() + ")");
                return;
            }

            activeRecorder.set(recorder);
            recorder.startRecording();
            if (!prepareSession()) return;

            FeatureFrontend frontend = new NativeFeatureFrontend();
            byte[] buf = new byte[CHUNK_BYTES];
            try {
                while (isCurrentSession(session)) {
                    int read;
                    try {
                        read = recorder.read(buf, 0, CHUNK_BYTES);
                    } catch (IllegalStateException e) {
                        break;
                    }
                    if (read < 0) {
                        Log.e(TAG, "read error: " + read);
                        break;
                    }
                    if (read == 0) continue;

                    boolean wake;
                    synchronized (this) {
                        // recheck under the lock since a destroy may have closed the model
                        if (!isCurrentSession(session)) break;
                        wakePending = false;
                        frontend.feed(buf, read, this::processFrame);
                        wake = wakePending;
                    }
                    if (wake) callback.onWakeDetected();
                }
            } finally {
                frontend.close();
            }

            try { recorder.stop(); } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "listen loop error", e);
        } finally {
            activeRecorder.compareAndSet(recorder, null);
            if (recorder != null) {
                try { recorder.release(); } catch (Exception ignored) {}
            }
            // only clear the flag if no newer session took over
            if (session == sessionId.get()) running.compareAndSet(true, false);
            latch.countDown();
        }
    }

    // resets all stream state and hands out fresh interpreters for a new session
    private synchronized boolean prepareSession() {
        if (wakeModel == null) {
            Log.w(TAG, "model closed before the session started");
            return false;
        }
        try {
            wakeModel.ensureFreshInterpreter();
        } catch (Exception e) {
            Log.e(TAG, "failed to rebuild interpreter on start", e);
            return false;
        }
        if (vadModel != null) {
            try {
                vadModel.ensureFreshInterpreter();
            } catch (Exception e) {
                Log.e(TAG, "failed to rebuild VAD interpreter on start", e);
                closeVadModel();
            }
        }

        wakeModel.resetStream();
        scoreWindow.reset();
        if (vadModel != null) {
            vadModel.resetStream();
            vadScoreWindow.reset();
        }
        vadDetected = false;
        debugMaxScore = 0f;
        debugInferCount = 0;
        wakeIgnoreWindows = -MIN_SLICES_BEFORE_DETECTION;
        lastRawScore = 0f;
        lastTriggerAt = 0L;
        return true;
    }

    // runs on the loop thread with the lock held
    private void processFrame(float[] melFrame) {
        if (wakeModel == null || !wakeModel.hasInterpreter()) return;

        processVadFrame(melFrame);

        if (lastRawScore < scoreThreshold) {
            wakeIgnoreWindows = Math.min(wakeIgnoreWindows + 1, 0);
        }

        if (!wakeModel.pushFrame(melFrame)) return;

        if (lowPowerMode) {
            skipNextInference = !skipNextInference;
            if (skipNextInference) return;
        }

        try {
            wakeModel.run();
        } catch (Exception e) {
            Log.e(TAG, "inference error", e);
            return;
        }

        float rawScore = wakeModel.readScore(positiveOutputIdx);
        lastRawScore = rawScore;
        float avgScore = scoreWindow.add(rawScore);

        if (BuildConfig.DEBUG) logScore(avgScore, rawScore);
        maybeBroadcastScore(avgScore);

        if (avgScore < scoreThreshold || wakeIgnoreWindows < 0) return;
        if (vadModel != null && vadModel.hasInterpreter() && !vadDetected) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "wake candidate blocked by VAD (score=" + String.format("%.3f", avgScore) + ")");
            }
            return;
        }

        long now = System.currentTimeMillis();
        if ((now - lastTriggerAt) < cooldownMs) return;
        lastTriggerAt = now;
        Log.i(TAG, "wake word detected (score=" + String.format("%.3f", avgScore) + ")");

        resetProbabilities();
        wakePending = true;
    }

    private void logScore(float avgScore, float rawScore) {
        debugInferCount++;
        if (avgScore > debugMaxScore) debugMaxScore = avgScore;
        boolean firstFew = debugInferCount <= 5;
        boolean periodic = debugInferCount % 100 == 0;
        boolean notable = avgScore > 0.05f;
        if (!firstFew && !periodic && !notable) return;

        float melMin = Float.MAX_VALUE;
        float melMax = -Float.MAX_VALUE;
        for (float v : wakeModel.latestFrame()) {
            if (v < melMin) melMin = v;
            if (v > melMax) melMax = v;
        }
        String msg = (notable ? "!!! " : "    ")
                + "avg=" + String.format("%.4f", avgScore)
                + " raw=" + String.format("%.4f", rawScore)
                + " rawByte=" + wakeModel.lastRawByte()
                + " thr=" + String.format("%.2f", scoreThreshold)
                + " maxEver=" + String.format("%.4f", debugMaxScore)
                + " mel=[" + String.format("%.1f", melMin) + ".." + String.format("%.1f", melMax) + "]";
        if (firstFew) Log.i(TAG, "infer#" + debugInferCount + " " + msg);
        else Log.d(TAG, msg);
    }

    private void maybeBroadcastScore(float avgScore) {
        if (!scoreBroadcastEnabled) return;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastScoreBroadcastMs < SCORE_BROADCAST_INTERVAL_MS) return;
        lastScoreBroadcastMs = nowMs;
        final float score = avgScore;
        final float threshold = scoreThreshold;
        mainHandler.post(() -> LocalBroadcastManager.getInstance(context).sendBroadcast(
                new Intent(Constants.INTENT_VOICE_SCORE)
                        .putExtra(Constants.INTENT_VOICE_SCORE_KEY, score)
                        .putExtra(Constants.INTENT_VOICE_THRESHOLD_KEY, threshold)));
    }

    private void resetProbabilities() {
        scoreWindow.reset();
        wakeIgnoreWindows = -MIN_SLICES_BEFORE_DETECTION;
        lastRawScore = 0f;
    }

    private void processVadFrame(float[] melFrame) {
        if (vadModel == null || !vadModel.hasInterpreter()) return;
        if (!vadModel.pushFrame(melFrame)) return;
        try {
            vadModel.run();
        } catch (Exception e) {
            Log.e(TAG, "VAD inference error", e);
            return;
        }
        vadDetected = vadScoreWindow.add(vadModel.readScore(vadPositiveOutputIdx)) >= vadThreshold;
    }

    static float calculateRms(byte[] buf, int length) {
        long sum = 0;
        int samples = length / 2;
        for (int i = 0; i < length - 1; i += 2) {
            short s = (short) (((buf[i + 1] & 0xFF) << 8) | (buf[i] & 0xFF));
            sum += (long) s * s;
        }
        return samples == 0 ? 0f : (float) (Math.sqrt((double) sum / samples) / 32768.0);
    }
}
