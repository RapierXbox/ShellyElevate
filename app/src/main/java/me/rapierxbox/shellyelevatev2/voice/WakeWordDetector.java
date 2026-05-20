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

import androidx.annotation.RequiresPermission;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;

import me.rapierxbox.shellyelevatev2.BuildConfig;
import me.rapierxbox.shellyelevatev2.Constants;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// Generic microWakeWord runner. Should accept any model that follows the mWW
// tensor layout, though only "okay nabu" is exercised in CI.
//
// Models live in <filesDir>/wakewords/<name>.tflite plus an optional <name>.json.
// Expected input  shape: [1, N, 40] or [1, N, 40, 1], dtype f32 or i8.
// Expected output shape: [1, 1] or [1, num_classes], scores in [0, 1], dtype f32 or i8.
public class WakeWordDetector {
    private static final String TAG = "WakeWordDetector";

    private static final int SAMPLE_RATE = NativeMelExtractor.SAMPLE_RATE;
    private static final int CHANNEL_CFG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FMT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHUNK_BYTES = NativeMelExtractor.HOP_SAMPLES * 2 * 10; // 100 ms = 10 mel hops
    private volatile float scoreThreshold = 0.5f;
    private volatile long cooldownMs = 5_000L;
    private volatile boolean scoreBroadcastEnabled = false;
    private static final long SHUTDOWN_TIMEOUT_MS = 1_000L;

    private static final int MIN_SLICES_BEFORE_DETECTION = 100;

    public static final String VAD_MODEL_NAME = "vad";

    public enum ModelStatus { NOT_LOADED, LOADED, FILE_NOT_FOUND, LOAD_ERROR }

    public interface Callback {
        void onWakeDetected();
    }

    private final Context   context;
    private final Callback  callback;
    private final ExecutorService          executor       = Executors.newSingleThreadExecutor();
    private final AtomicBoolean            running        = new AtomicBoolean(false);
    private final AtomicReference<AudioRecord> activeRecorder = new AtomicReference<>();
    private volatile CountDownLatch shutdownLatch;

    private volatile ModelStatus modelStatus = ModelStatus.NOT_LOADED;
    private Interpreter tflite;
    // Remembered so we can rebuild the interpreter on start(); see listenLoop().
    private File modelFile;
    private int nFrames;
    private boolean hasChannelDim;
    private float[][][] input3d;
    private float[][][][] input4d;
    private ByteBuffer inputBufferByte;
    private ByteBuffer outputBufferByte;
    private boolean inputIs8bit;
    private boolean inputIsUnsigned;
    private float inputScale;
    private int inputZeroPoint;
    private float[][] outputBuf;
    private boolean outputIs8bit;
    private boolean outputIsUnsigned;
    private float outputScale;
    private int outputZeroPoint;
    private int outputCols;

    private float[][] frameRing;
    private int frameRingPos = 0;
    private int framesCollected = 0;
    private int newFramesSinceInfer = 0;
    private volatile long lastTriggerAt = 0L;


    // Loaded from the companion JSON if present; otherwise mWW v2 defaults.
    private float baseThreshold = 0.5f;
    private int slidingWindowSize = 10;
    private int positiveOutputIdx = 0;

    // Score-smoothing window matching the mWW reference implementation.
    private float[] scoreWindow;
    private int scoreWindowPos = 0;
    private float scoreWindowSum = 0f;
    private int effectiveWinSize = 10;

    private int wakeIgnoreWindows = -MIN_SLICES_BEFORE_DETECTION;
    private float lastRawScore = 0f;

    private volatile boolean lowPowerMode = false;
    private boolean skipNextInference = false;

    private Interpreter vadTflite;
    private File vadModelFile;
    private int vadNFrames;
    private boolean vadHasChannelDim;
    private ByteBuffer vadInputBufferByte;
    private ByteBuffer vadOutputBufferByte;
    private float[][][] vadInput3dFloat;
    private float[][][][] vadInput4dFloat;
    private float[][] vadOutputBufFloat;
    private boolean vadInputIs8bit;
    private boolean vadInputIsUnsigned;
    private int vadInputZeroPoint;
    private boolean vadOutputIs8bit;
    private boolean vadOutputIsUnsigned;
    private float vadOutputScale;
    private int vadOutputZeroPoint;
    private int vadOutputCols;
    private int vadPositiveOutputIdx = 0;
    private float vadThreshold = 0.5f;
    private float[] vadScoreWindow;
    private int vadScoreWindowPos = 0;
    private float vadScoreWindowSum = 0f;
    private int vadSlidingWindowSize = 5;
    private int vadNewFramesSinceInfer = 0;
    private float[][] vadFrameRing;
    private int vadFrameRingPos = 0;
    private int vadFramesCollected = 0;
    private volatile boolean vadDetected = false;

    private int debugFrameCount = 0;
    private float debugMaxEver = 0f;
    private long lastScoreBroadcastMs = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public WakeWordDetector(Context context, Callback callback) {
        this.context  = context;
        this.callback = callback;
    }

    public synchronized ModelStatus loadModel(String modelName) {
        closeModel();

        if (modelName == null || modelName.trim().isEmpty()) {
            modelStatus = ModelStatus.NOT_LOADED;
            return modelStatus;
        }

        File file = new File(new File(context.getFilesDir(), "wakewords"), modelName.trim() + ".tflite");
        if (!file.exists()) {
            Log.w(TAG, "model file not found: " + file.getAbsolutePath());
            modelStatus = ModelStatus.FILE_NOT_FOUND;
            return modelStatus;
        }

        try {
            tflite = buildInterpreter(loadMappedFile(file));
            modelFile = file;

            int[] shape = tflite.getInputTensor(0).shape();
            if (shape.length == 3) {
                nFrames = shape[1]; hasChannelDim = false;
            } else if (shape.length == 4) {
                nFrames = shape[1]; hasChannelDim = true;
            } else {
                Log.e(TAG, "unsupported input rank: " + shape.length);
                closeModel(); modelStatus = ModelStatus.LOAD_ERROR; return modelStatus;
            }

            if (nFrames <= 0) {
                Log.e(TAG, "invalid nFrames=" + nFrames);
                closeModel(); modelStatus = ModelStatus.LOAD_ERROR; return modelStatus;
            }

            frameRing    = new float[nFrames][NativeMelExtractor.N_MELS];
            frameRingPos = 0; framesCollected = 0;

            org.tensorflow.lite.DataType inType = tflite.getInputTensor(0).dataType();
            org.tensorflow.lite.DataType outType = tflite.getOutputTensor(0).dataType();
            inputIs8bit = inType == org.tensorflow.lite.DataType.INT8 || inType == org.tensorflow.lite.DataType.UINT8;
            outputIs8bit = outType == org.tensorflow.lite.DataType.INT8 || outType == org.tensorflow.lite.DataType.UINT8;
            inputIsUnsigned = inType == org.tensorflow.lite.DataType.UINT8;
            outputIsUnsigned = outType == org.tensorflow.lite.DataType.UINT8;
            inputScale = inputIs8bit ? tflite.getInputTensor(0).quantizationParams().getScale() : 1f;
            inputZeroPoint = inputIs8bit ? tflite.getInputTensor(0).quantizationParams().getZeroPoint() : 0;
            outputScale = outputIs8bit ? tflite.getOutputTensor(0).quantizationParams().getScale(): 1f;
            outputZeroPoint = outputIs8bit ? tflite.getOutputTensor(0).quantizationParams().getZeroPoint() : 0;
            // Some models ship without input quantisation params (scale=0). Without
            // a fallback, val/scale is inf and the result rounds to 127 for every
            // bin. Use the mWW v2 mapping (zp=-128, scale = OUT_MAX / 255).
            if (inputIs8bit && inputScale == 0f) {
                inputScale = NativeMelExtractor.OUT_MAX / 255f;
                inputZeroPoint = -128;
                Log.w(TAG, "input quant params missing for " + modelName + ", using fallback");
            }

            int outCols = tflite.getOutputTensor(0).shape()[tflite.getOutputTensor(0).shape().length - 1];
            this.outputCols = outCols;
            if (inputIs8bit) {
                inputBufferByte = ByteBuffer.allocateDirect(nFrames * NativeMelExtractor.N_MELS).order(ByteOrder.nativeOrder());
                input3d = null; input4d = null;
            } else {
                if (hasChannelDim) input4d = new float[1][nFrames][NativeMelExtractor.N_MELS][1];
                else               input3d = new float[1][nFrames][NativeMelExtractor.N_MELS];
                inputBufferByte = null;
            }
            if (outputIs8bit) {
                outputBufferByte = ByteBuffer.allocateDirect(outCols).order(ByteOrder.nativeOrder());
                outputBuf = null;
            } else {
                outputBuf = new float[1][outCols];
                outputBufferByte = null;
            }

            loadJsonConfig(modelName.trim(), outCols);

            effectiveWinSize = slidingWindowSize;
            scoreWindow = new float[effectiveWinSize];
            scoreWindowPos = 0;
            scoreWindowSum = 0f;

            Log.i(TAG, "loaded " + modelName
                    + " input=" + java.util.Arrays.toString(shape)
                    + " inType=" + tflite.getInputTensor(0).dataType()
                    + " outType=" + tflite.getOutputTensor(0).dataType()
                    + " inScale=" + inputScale + " inZP=" + inputZeroPoint
                    + " outScale=" + outputScale + " outZP=" + outputZeroPoint
                    + " window=" + slidingWindowSize
                    + " cutoff=" + baseThreshold
                    + " posIdx=" + positiveOutputIdx);
            modelStatus = ModelStatus.LOADED;

            loadVadModel();
        } catch (Exception e) {
            Log.e(TAG, "failed to load " + modelName, e);
            closeModel(); modelStatus = ModelStatus.LOAD_ERROR;
        }
        return modelStatus;
    }

    private synchronized void loadVadModel() {
        closeVadModel();
        File file = new File(new File(context.getFilesDir(), "wakewords"), VAD_MODEL_NAME + ".tflite");
        if (!file.exists()) {
            Log.w(TAG, "VAD model not present, wake detection will not be gated on voice activity");
            return;
        }
        try {
            vadTflite = buildInterpreter(loadMappedFile(file));
            vadModelFile = file;

            int[] shape = vadTflite.getInputTensor(0).shape();
            if (shape.length == 3) { vadNFrames = shape[1]; vadHasChannelDim = false; }
            else if (shape.length == 4) { vadNFrames = shape[1]; vadHasChannelDim = true; }
            else { Log.e(TAG, "VAD unsupported input rank: " + shape.length); closeVadModel(); return; }
            if (vadNFrames <= 0) { Log.e(TAG, "VAD invalid nFrames"); closeVadModel(); return; }

            vadFrameRing = new float[vadNFrames][NativeMelExtractor.N_MELS];
            vadFrameRingPos = 0; vadFramesCollected = 0; vadNewFramesSinceInfer = 0;

            org.tensorflow.lite.DataType inType = vadTflite.getInputTensor(0).dataType();
            org.tensorflow.lite.DataType outType = vadTflite.getOutputTensor(0).dataType();
            vadInputIs8bit = inType == org.tensorflow.lite.DataType.INT8 || inType == org.tensorflow.lite.DataType.UINT8;
            vadOutputIs8bit = outType == org.tensorflow.lite.DataType.INT8 || outType == org.tensorflow.lite.DataType.UINT8;
            vadInputIsUnsigned = inType == org.tensorflow.lite.DataType.UINT8;
            vadOutputIsUnsigned = outType == org.tensorflow.lite.DataType.UINT8;
            vadInputZeroPoint = vadInputIs8bit ? vadTflite.getInputTensor(0).quantizationParams().getZeroPoint() : 0;
            vadOutputScale = vadOutputIs8bit ? vadTflite.getOutputTensor(0).quantizationParams().getScale() : 1f;
            vadOutputZeroPoint = vadOutputIs8bit ? vadTflite.getOutputTensor(0).quantizationParams().getZeroPoint() : 0;

            int outCols = vadTflite.getOutputTensor(0).shape()[vadTflite.getOutputTensor(0).shape().length - 1];
            this.vadOutputCols = outCols;
            if (vadInputIs8bit) {
                vadInputBufferByte = ByteBuffer.allocateDirect(vadNFrames * NativeMelExtractor.N_MELS).order(ByteOrder.nativeOrder());
                vadInput3dFloat = null; vadInput4dFloat = null;
            } else {
                if (vadHasChannelDim) vadInput4dFloat = new float[1][vadNFrames][NativeMelExtractor.N_MELS][1];
                else                  vadInput3dFloat = new float[1][vadNFrames][NativeMelExtractor.N_MELS];
                vadInputBufferByte = null;
            }
            if (vadOutputIs8bit) {
                vadOutputBufferByte = ByteBuffer.allocateDirect(outCols).order(ByteOrder.nativeOrder());
                vadOutputBufFloat = null;
            } else {
                vadOutputBufFloat = new float[1][outCols];
                vadOutputBufferByte = null;
            }

            loadVadJsonConfig(outCols);
            vadScoreWindow = new float[Math.max(1, vadSlidingWindowSize)];
            vadScoreWindowPos = 0; vadScoreWindowSum = 0f;

            Log.i(TAG, "VAD loaded input=" + java.util.Arrays.toString(shape)
                    + " window=" + vadSlidingWindowSize
                    + " cutoff=" + vadThreshold
                    + " posIdx=" + vadPositiveOutputIdx);
        } catch (Exception e) {
            Log.e(TAG, "failed to load VAD model", e);
            closeVadModel();
        }
    }

    private void loadVadJsonConfig(int outCols) {
        File jsonFile = new File(new File(context.getFilesDir(), "wakewords"), VAD_MODEL_NAME + ".json");
        if (!jsonFile.exists()) { Log.d(TAG, "no VAD json, using defaults"); return; }
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(jsonFile))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            JSONObject cfg = json.has("micro") ? json.getJSONObject("micro") : json;

            if (cfg.has("sliding_window_size"))
                vadSlidingWindowSize = Math.max(1, cfg.getInt("sliding_window_size"));
            else if (cfg.has("sliding_window_average_size"))
                vadSlidingWindowSize = Math.max(1, cfg.getInt("sliding_window_average_size"));
            if (cfg.has("probability_cutoff"))
                vadThreshold = (float) cfg.getDouble("probability_cutoff");

            if (json.has("class_mapping") && json.has("positive_output_class")) {
                String posClass = json.getString("positive_output_class");
                JSONObject mapping = json.getJSONObject("class_mapping");
                for (int idx = 0; idx < outCols; idx++) {
                    String key = String.valueOf(idx);
                    if (mapping.has(key) && posClass.equals(mapping.getString(key))) {
                        vadPositiveOutputIdx = idx; break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "could not parse VAD JSON: " + e.getMessage());
        }
    }

    private void closeModel() {
        if (tflite != null) { try { tflite.close(); } catch (Exception ignored) {} tflite = null; }
        modelFile = null;
        frameRing = null;
        input3d = null; input4d = null;
        inputBufferByte = null; outputBufferByte = null;
        outputBuf = null;
        hasChannelDim = false;
        inputIs8bit = false; inputIsUnsigned = false;
        outputIs8bit = false; outputIsUnsigned = false;
        scoreWindow = null;
        scoreWindowPos = 0;
        scoreWindowSum = 0f;
        slidingWindowSize = 10;
        effectiveWinSize = 10;
        baseThreshold = 0.5f;
        positiveOutputIdx = 0;
        wakeIgnoreWindows = -MIN_SLICES_BEFORE_DETECTION;
        lastRawScore = 0f;
        closeVadModel();
        modelStatus = ModelStatus.NOT_LOADED;
    }

    private void closeVadModel() {
        if (vadTflite != null) { try { vadTflite.close(); } catch (Exception ignored) {} vadTflite = null; }
        vadModelFile = null;
        vadFrameRing = null;
        vadInput3dFloat = null; vadInput4dFloat = null;
        vadInputBufferByte = null; vadOutputBufferByte = null;
        vadOutputBufFloat = null;
        vadScoreWindow = null;
        vadScoreWindowPos = 0;
        vadScoreWindowSum = 0f;
        vadFrameRingPos = 0; vadFramesCollected = 0; vadNewFramesSinceInfer = 0;
        vadDetected = false;
    }

    private void loadJsonConfig(String modelName, int outCols) {
        File jsonFile = new File(new File(context.getFilesDir(), "wakewords"), modelName + ".json");
        if (!jsonFile.exists()) {
            Log.d(TAG, "no JSON config found for " + modelName + ", using defaults");
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(jsonFile))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());

            // TaterTotterson models nest config under "micro"; ESPHome models are flat.
            JSONObject cfg = json.has("micro") ? json.getJSONObject("micro") : json;

            if (cfg.has("sliding_window_size"))
                slidingWindowSize = Math.max(1, cfg.getInt("sliding_window_size"));
            else if (cfg.has("sliding_window_average_size"))
                slidingWindowSize = Math.max(1, cfg.getInt("sliding_window_average_size"));

            if (cfg.has("probability_cutoff")) {
                baseThreshold = (float) cfg.getDouble("probability_cutoff");
                scoreThreshold = baseThreshold;
            }

            // Resolve the positive-class index from class_mapping. TaterTotterson
            // models lack the mapping and effectively use index 0.
            if (json.has("class_mapping") && json.has("positive_output_class")) {
                String posClass = json.getString("positive_output_class");
                JSONObject mapping = json.getJSONObject("class_mapping");
                for (int idx = 0; idx < outCols; idx++) {
                    String key = String.valueOf(idx);
                    if (mapping.has(key) && posClass.equals(mapping.getString(key))) {
                        positiveOutputIdx = idx;
                        break;
                    }
                }
            }

            Log.i(TAG, "JSON config: window=" + slidingWindowSize
                    + " cutoff=" + baseThreshold + " posIdx=" + positiveOutputIdx);
        } catch (Exception e) {
            Log.w(TAG, "could not parse JSON config for " + modelName + ": " + e.getMessage());
        }
    }

    public ModelStatus getModelStatus() { return modelStatus; }
    public String getModelDirectory()   { return new File(context.getFilesDir(), "wakewords").getAbsolutePath(); }

    /**
     * Map a 0..100 user-facing slider onto the score threshold around the
     * model's published cutoff: 50 = baseThreshold, 100 = baseThreshold - 0.4
     * (more sensitive), 0 = baseThreshold + 0.4 (less sensitive).
     */
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

    public void setLowPowerMode(boolean low) {
        lowPowerMode = low;
    }

    public void start() {
        if (modelStatus != ModelStatus.LOADED) {
            Log.w(TAG, "can't start: model not loaded (" + modelStatus + ")"); return;
        }
        if (running.getAndSet(true)) return;
        shutdownLatch = new CountDownLatch(1);
        executor.execute(this::listenLoop);
        Log.i(TAG, "detector started");
    }

    /** Blocks until the mic is released or SHUTDOWN_TIMEOUT_MS elapses. */
    public boolean stopAndWait() {
        if (!running.getAndSet(false)) return true;
        Log.i(TAG, "detector stopping (waiting for mic release)");
        // recorder.stop() is required to unblock a pending read() on the loop.
        AudioRecord r = activeRecorder.get();
        if (r != null) { try { r.stop(); } catch (Exception ignored) {} }
        try {
            if (shutdownLatch != null) {
                boolean ok = shutdownLatch.await(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!ok) { Log.w(TAG, "shutdown timeout, force releasing"); forceReleaseRecorder(); }
                return ok;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return false;
    }

    /** Non-blocking variant; the listen loop unwinds on the executor thread. */
    public void stop() {
        if (!running.getAndSet(false)) return;
        Log.i(TAG, "detector stopping (non blocking)");
        AudioRecord r = activeRecorder.get();
        if (r != null) { try { r.stop(); } catch (Exception ignored) {} }
    }

    public boolean isRunning() { return running.get(); }

    private void forceReleaseRecorder() {
        AudioRecord r = activeRecorder.getAndSet(null);
        if (r != null) {
            try { r.stop();    } catch (Exception ignored) {}
            try { r.release(); } catch (Exception ignored) {}
            Log.d(TAG, "force-released recorder");
        }
    }

    private void listenLoop() {
        AudioRecord recorder = null;
        try {
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
            // resetVariableTensors() is unreliable for converted v2 streaming graphs:
            // some models accumulate LSTM state across stop/start and gradually drift
            // the score upward on pure silence until they false-fire. Rebuilding the
            // interpreter from the file guarantees fresh variable state.
            if (modelFile != null) {
                try {
                    if (tflite != null) { tflite.close(); tflite = null; }
                    tflite = buildInterpreter(loadMappedFile(modelFile));
                } catch (Exception e) {
                    Log.e(TAG, "failed to rebuild interpreter on start", e);
                    return;
                }
            }
            if (vadModelFile != null) {
                try {
                    if (vadTflite != null) { vadTflite.close(); vadTflite = null; }
                    vadTflite = buildInterpreter(loadMappedFile(vadModelFile));
                } catch (Exception e) {
                    Log.e(TAG, "failed to rebuild VAD interpreter on start", e);
                    vadModelFile = null;
                }
            }
            if (scoreWindow != null) {
                java.util.Arrays.fill(scoreWindow, 0f);
                scoreWindowPos = 0; scoreWindowSum = 0f;
            }
            if (vadScoreWindow != null) {
                java.util.Arrays.fill(vadScoreWindow, 0f);
                vadScoreWindowPos = 0; vadScoreWindowSum = 0f;
            }
            debugMaxEver = 0f; debugFrameCount = 0;
            framesCollected = 0; frameRingPos = 0; newFramesSinceInfer = 0;
            vadFramesCollected = 0; vadFrameRingPos = 0; vadNewFramesSinceInfer = 0;
            vadDetected = false;
            wakeIgnoreWindows = -MIN_SLICES_BEFORE_DETECTION;
            lastRawScore = 0f;
            lastTriggerAt = 0L;

            FeatureFrontend extractor = new NativeFeatureFrontend();
            byte[] buf = new byte[CHUNK_BYTES];

            try {
                while (running.get()) {
                    int read;
                    try { read = recorder.read(buf, 0, CHUNK_BYTES); }
                    catch (IllegalStateException e) { break; }
                    if (read <= 0) { if (read < 0) Log.e(TAG, "read error: " + read); continue; }
                    extractor.feed(buf, read, this::processFrame);
                }
            } finally {
                extractor.close();
            }

            try { recorder.stop(); } catch (Exception ignored) {}

        } catch (Exception e) {
            Log.e(TAG, "listen loop error", e);
        } finally {
            activeRecorder.set(null);
            if (recorder != null) { try { recorder.release(); } catch (Exception ignored) {} }
            running.set(false);
            CountDownLatch latch = shutdownLatch;
            if (latch != null) latch.countDown();
        }
    }

    private void processFrame(float[] melFrame) {
        if (tflite == null || frameRing == null) return;

        processVadFrame(melFrame);

        if (lastRawScore < scoreThreshold) {
            wakeIgnoreWindows = Math.min(wakeIgnoreWindows + 1, 0);
        }

        // melFrame is reused by the extractor, so copy into our ring buffer.
        System.arraycopy(melFrame, 0, frameRing[frameRingPos % nFrames], 0, NativeMelExtractor.N_MELS);
        frameRingPos++;
        framesCollected++;
        newFramesSinceInfer++;

        // ESPHome fills a full stride-sized input then invokes once. Running on
        // every incoming frame with overlapping windows corrupts the LSTM state
        // for any model with nFrames > 1, so we mirror that batching here.
        if (framesCollected < nFrames || newFramesSinceInfer < nFrames) return;
        newFramesSinceInfer = 0;

        // Low-power: skip every other inference. ~2x detection latency, ~50% CPU.
        if (lowPowerMode) {
            skipNextInference = !skipNextInference;
            if (skipNextInference) return;
        }

        int base = frameRingPos % nFrames;
        try {
            if (inputIs8bit) {
                inputBufferByte.rewind();
                for (int t = 0; t < nFrames; t++) {
                    float[] row = frameRing[(base + t) % nFrames];
                    for (int f = 0; f < NativeMelExtractor.N_MELS; f++)
                        inputBufferByte.put(quantizeMel(row[f], inputZeroPoint, inputIsUnsigned));
                }
                inputBufferByte.rewind();
                Object out = outputIs8bit ? prepOutputBuf(outputBufferByte) : outputBuf;
                tflite.run(inputBufferByte, out);
            } else {
                if (hasChannelDim) {
                    for (int t = 0; t < nFrames; t++) {
                        float[] row = frameRing[(base + t) % nFrames];
                        for (int f = 0; f < NativeMelExtractor.N_MELS; f++) input4d[0][t][f][0] = row[f];
                    }
                    Object out = outputIs8bit ? prepOutputBuf(outputBufferByte) : outputBuf;
                    tflite.run(input4d, out);
                } else {
                    for (int t = 0; t < nFrames; t++)
                        System.arraycopy(frameRing[(base + t) % nFrames], 0, input3d[0][t], 0, NativeMelExtractor.N_MELS);
                    Object out = outputIs8bit ? prepOutputBuf(outputBufferByte) : outputBuf;
                    tflite.run(input3d, out);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "inference error", e); return;
        }

        scoreAndMaybeDetect();
    }

    private static ByteBuffer prepOutputBuf(ByteBuffer b) { b.rewind(); return b; }

    private void scoreAndMaybeDetect() {
        float rawScore;
        int rawByte = -1;
        if (outputIs8bit) {
            int idx = Math.min(positiveOutputIdx, outputCols - 1);
            byte b = outputBufferByte.get(idx);
            float scale = outputScale;
            int zp = outputZeroPoint;
            if (scale == 0f) {
                // No quant params: assume the byte is an unsigned probability
                // ([0, 255] -> [0, 1]).
                scale = 1f / 255f;
                rawByte = b & 0xFF;
                zp = 0;
            } else {
                rawByte = outputIsUnsigned ? (b & 0xFF) : (int) b;
            }
            rawScore = (rawByte - zp) * scale;
        } else {
            int idx = Math.min(positiveOutputIdx, outputBuf[0].length - 1);
            rawScore = outputBuf[0][idx];
        }
        rawScore = Math.max(0f, rawScore);
        lastRawScore = rawScore;

        if (scoreWindow != null) {
            scoreWindowSum -= scoreWindow[scoreWindowPos];
            scoreWindow[scoreWindowPos] = rawScore;
            scoreWindowSum += rawScore;
            scoreWindowPos = (scoreWindowPos + 1) % effectiveWinSize;
        }
        float avgScore = (scoreWindow != null) ? scoreWindowSum / effectiveWinSize : rawScore;

        debugFrameCount++;
        if (avgScore > debugMaxEver) debugMaxEver = avgScore;
        if (BuildConfig.DEBUG) {
            boolean firstFew = (debugFrameCount <= 5);
            boolean periodic = (debugFrameCount % 100 == 0);
            boolean notable  = (avgScore > 0.05f);
            if (firstFew || periodic || notable) {
                float melMin = Float.MAX_VALUE, melMax = -Float.MAX_VALUE;
                float[] recent = frameRing[(frameRingPos - 1 + nFrames) % nFrames];
                for (float v : recent) { if (v < melMin) melMin = v; if (v > melMax) melMax = v; }
                String msg = (notable ? "!!! " : "    ")
                    + "avg=" + String.format("%.4f", avgScore)
                    + " raw=" + String.format("%.4f", rawScore)
                    + " rawByte=" + rawByte
                    + " thr=" + String.format("%.2f", scoreThreshold)
                    + " maxEver=" + String.format("%.4f", debugMaxEver)
                    + " mel=[" + String.format("%.1f", melMin) + ".." + String.format("%.1f", melMax) + "]";
                if (firstFew) Log.i(TAG, "infer#" + debugFrameCount + " " + msg);
                else          Log.d(TAG, msg);
            }
        }

        if (scoreBroadcastEnabled) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastScoreBroadcastMs >= 50) {
                lastScoreBroadcastMs = nowMs;
                final float bs = avgScore, bt = scoreThreshold;
                mainHandler.post(() -> LocalBroadcastManager.getInstance(context).sendBroadcast(
                        new Intent(Constants.INTENT_VOICE_SCORE)
                                .putExtra(Constants.INTENT_VOICE_SCORE_KEY, bs)
                                .putExtra(Constants.INTENT_VOICE_THRESHOLD_KEY, bt)));
            }
        }

        if (avgScore >= scoreThreshold) {
            if (wakeIgnoreWindows < 0) return;

            if (vadTflite != null && !vadDetected) {
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
            callback.onWakeDetected();
        }
    }

    private void resetProbabilities() {
        if (scoreWindow != null) {
            java.util.Arrays.fill(scoreWindow, 0f);
            scoreWindowPos = 0; scoreWindowSum = 0f;
        }
        wakeIgnoreWindows = -MIN_SLICES_BEFORE_DETECTION;
        lastRawScore = 0f;
    }

    private void processVadFrame(float[] melFrame) {
        if (vadTflite == null || vadFrameRing == null) return;

        System.arraycopy(melFrame, 0, vadFrameRing[vadFrameRingPos % vadNFrames], 0, NativeMelExtractor.N_MELS);
        vadFrameRingPos++;
        vadFramesCollected++;
        vadNewFramesSinceInfer++;

        if (vadFramesCollected < vadNFrames || vadNewFramesSinceInfer < vadNFrames) return;
        vadNewFramesSinceInfer = 0;

        int base = vadFrameRingPos % vadNFrames;
        try {
            if (vadInputIs8bit) {
                vadInputBufferByte.rewind();
                for (int t = 0; t < vadNFrames; t++) {
                    float[] row = vadFrameRing[(base + t) % vadNFrames];
                    for (int f = 0; f < NativeMelExtractor.N_MELS; f++)
                        vadInputBufferByte.put(quantizeMel(row[f], vadInputZeroPoint, vadInputIsUnsigned));
                }
                vadInputBufferByte.rewind();
                Object out = vadOutputIs8bit ? prepOutputBuf(vadOutputBufferByte) : vadOutputBufFloat;
                vadTflite.run(vadInputBufferByte, out);
            } else if (vadHasChannelDim) {
                for (int t = 0; t < vadNFrames; t++) {
                    float[] row = vadFrameRing[(base + t) % vadNFrames];
                    for (int f = 0; f < NativeMelExtractor.N_MELS; f++) vadInput4dFloat[0][t][f][0] = row[f];
                }
                Object out = vadOutputIs8bit ? prepOutputBuf(vadOutputBufferByte) : vadOutputBufFloat;
                vadTflite.run(vadInput4dFloat, out);
            } else {
                for (int t = 0; t < vadNFrames; t++)
                    System.arraycopy(vadFrameRing[(base + t) % vadNFrames], 0, vadInput3dFloat[0][t], 0, NativeMelExtractor.N_MELS);
                Object out = vadOutputIs8bit ? prepOutputBuf(vadOutputBufferByte) : vadOutputBufFloat;
                vadTflite.run(vadInput3dFloat, out);
            }
        } catch (Exception e) {
            Log.e(TAG, "VAD inference error", e);
            return;
        }

        float rawScore;
        if (vadOutputIs8bit) {
            int idx = Math.min(vadPositiveOutputIdx, vadOutputCols - 1);
            byte b = vadOutputBufferByte.get(idx);
            float scale = vadOutputScale;
            int zp = vadOutputZeroPoint;
            int rawByte;
            if (scale == 0f) { scale = 1f / 255f; rawByte = b & 0xFF; zp = 0; }
            else { rawByte = vadOutputIsUnsigned ? (b & 0xFF) : (int) b; }
            rawScore = (rawByte - zp) * scale;
        } else {
            int idx = Math.min(vadPositiveOutputIdx, vadOutputBufFloat[0].length - 1);
            rawScore = vadOutputBufFloat[0][idx];
        }
        rawScore = Math.max(0f, rawScore);

        if (vadScoreWindow != null) {
            vadScoreWindowSum -= vadScoreWindow[vadScoreWindowPos];
            vadScoreWindow[vadScoreWindowPos] = rawScore;
            vadScoreWindowSum += rawScore;
            vadScoreWindowPos = (vadScoreWindowPos + 1) % vadScoreWindow.length;
        }
        float avg = (vadScoreWindow != null) ? vadScoreWindowSum / vadScoreWindow.length : rawScore;
        vadDetected = avg >= vadThreshold;
    }

    // Map our [0, OUT_MAX] mel float to INT8/UINT8 while ignoring the model's
    // declared inputScale. Training pipelines disagree on the scale:
    //   okay_nabu      uses float [0, 26]   features (scale ~= 0.102)
    //   TaterTotterson uses uint16 [0, 666] features (scale ~= 2.61)
    // Both represent the same signal up to a 25.6x linear rescale. Mapping
    // directly to the full INT8 range using only the zero-point produces
    // identical quantized values regardless of which scale the model expects.
    private static byte quantizeMel(float val, int zeroPoint, boolean unsigned) {
        float range = unsigned ? 255f : (127f - zeroPoint);
        int q = Math.round(val * range / NativeMelExtractor.OUT_MAX) + zeroPoint;
        return unsigned ? (byte) Math.max(0, Math.min(255, q))
                        : (byte) Math.max(-128, Math.min(127, q));
    }

    static float calculateRms(byte[] buf, int length) {
        long sum = 0; int samples = length / 2;
        for (int i = 0; i < length - 1; i += 2) {
            short s = (short) (((buf[i + 1] & 0xFF) << 8) | (buf[i] & 0xFF));
            sum += (long) s * s;
        }
        return samples == 0 ? 0f : (float) (Math.sqrt((double) sum / samples) / 32768.0);
    }

    private static MappedByteBuffer loadMappedFile(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file); FileChannel ch = fis.getChannel()) {
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
    }

    static Interpreter buildInterpreter(MappedByteBuffer model) {
        try {
            Interpreter.Options opts = new Interpreter.Options();
            opts.setUseNNAPI(true);
            opts.setNumThreads(1);
            return new Interpreter(model, opts);
        } catch (Throwable t) {
            Log.w(TAG, "NNAPI init failed, using CPU: " + t.getMessage());
            return new Interpreter(model, new Interpreter.Options().setNumThreads(1));
        }
    }

    public void onDestroy() {
        stopAndWait();
        closeModel();
        executor.shutdownNow();
    }
}
