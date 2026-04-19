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
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// should be compatible with all microwakeword models... only tested with ok nabo
// model goes winto filedir/wakewords/name.tflite including json
// input shape should be [1, N, 40] op [1, N, 40, 1] f32 or i8
// output is [1, 1] or [1, N-classes] scores in [0, 1] f32 or i8
public class WakeWordDetector {
    private static final String TAG = "WakeWordDetector";

    private static final int SAMPLE_RATE = MelSpectrogramExtractor.SAMPLE_RATE;
    private static final int CHANNEL_CFG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FMT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHUNK_BYTES = MelSpectrogramExtractor.HOP_SAMPLES * 2 * 10; // 100ms = 10 mel hops
    private volatile float scoreThreshold = 0.5f;
    private volatile long cooldownMs = 5_000L;
    private static final long SHUTDOWN_TIMEOUT_MS = 1_000L;

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
    private int nFrames;
    private boolean hasChannelDim;
    private float[][][] input3d;
    private float[][][][] input4d;
    // i8 or ui8 arrays + quant params
    private byte[][][] input3dByte;
    private byte[][][][] input4dByte;
    private boolean inputIs8bit;
    private boolean inputIsUnsigned;
    private float inputScale;
    private int inputZeroPoint;
    private float[][] outputBuf;
    private byte[][] outputBufByte;
    private boolean outputIs8bit;
    private boolean outputIsUnsigned;
    private float outputScale;
    private int outputZeroPoint;

    private float[][] frameRing;
    private int frameRingPos = 0;
    private int framesCollected = 0;
    private int newFramesSinceInfer = 0; // new frames since last infer call
    private volatile long lastTriggerAt = 0L;


    // v2 json conf
    private float baseThreshold = 0.5f;
    private int slidingWindowSize = 10;
    private int positiveOutputIdx = 0;

    // sliding window
    private float[] scoreWindow;
    private int scoreWindowPos = 0;
    private float scoreWindowSum = 0f;
    private int effectiveWinSize = 10;

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
            tflite = new Interpreter(loadMappedFile(file), new Interpreter.Options().setNumThreads(1));

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

            frameRing    = new float[nFrames][MelSpectrogramExtractor.N_MELS];
            frameRingPos = 0; framesCollected = 0;

            // detect tensor data types
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

            int outCols = tflite.getOutputTensor(0).shape()[tflite.getOutputTensor(0).shape().length - 1];
            if (hasChannelDim) {
                if (inputIs8bit) { input4dByte = new byte[1][nFrames][MelSpectrogramExtractor.N_MELS][1]; input4d = null; }
                else { input4d = new float[1][nFrames][MelSpectrogramExtractor.N_MELS][1]; input4dByte = null; }
                input3d = null; input3dByte = null;
            } else {
                if (inputIs8bit) { input3dByte = new byte[1][nFrames][MelSpectrogramExtractor.N_MELS]; input3d = null; }
                else { input3d = new float[1][nFrames][MelSpectrogramExtractor.N_MELS]; input3dByte = null; }
                input4d = null; input4dByte = null;
            }
            if (outputIs8bit) { outputBufByte = new byte[1][outCols]; outputBuf = null; }
            else { outputBuf = new float[1][outCols]; outputBufByte = null; }

            // load v2 json
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

        } catch (Exception e) {
            Log.e(TAG, "failed to load " + modelName, e);
            closeModel(); modelStatus = ModelStatus.LOAD_ERROR;
        }
        return modelStatus;
    }

    private void closeModel() {
        if (tflite != null) { try { tflite.close(); } catch (Exception ignored) {} tflite = null; }
        frameRing = null;
        input3d = null; input4d = null; input3dByte = null; input4dByte = null;
        outputBuf = null; outputBufByte = null;
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
        modelStatus = ModelStatus.NOT_LOADED;
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

            if (json.has("sliding_window_average"))
                slidingWindowSize = Math.max(1, json.getInt("sliding_window_average"));

            if (json.has("probability_cutoff")) {
                baseThreshold = (float) json.getDouble("probability_cutoff");
                scoreThreshold = baseThreshold; // will be overridden by setSensitivity if called
            }

            // resolve idx for positive class
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
    // 50 = default; 100 = defalt - 0.4 and vice versa
    public void setSensitivity(int sensitivity) {
        float delta = (50 - sensitivity) / 100f * 0.8f;
        scoreThreshold = Math.max(0.01f, Math.min(0.99f, baseThreshold + delta));
    }

    public void setCooldown(int seconds) {
        cooldownMs = seconds * 1000L;
    }

    public void start() {
        if (modelStatus != ModelStatus.LOADED) {
            Log.w(TAG, "cant start: model not loaded (" + modelStatus + ")"); return;
        }
        if (running.getAndSet(true)) return;
        shutdownLatch = new CountDownLatch(1);
        executor.execute(this::listenLoop);
        Log.i(TAG, "detector started");
    }

    public boolean stopAndWait() {
        if (!running.getAndSet(false)) return true;
        Log.i(TAG, "detector stopping (waiting for mic release)");
        // unblock in progress recorder.read()
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

    // nonblocking
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
            if (tflite != null) { try { tflite.resetVariableTensors(); } catch (Exception ignored) {} } // reset tensor state so stale pos stoate from prior detection does not refire (doesnt really work)
            if (scoreWindow != null) {
                java.util.Arrays.fill(scoreWindow, 0f);
                scoreWindowPos = 0; scoreWindowSum = 0f;
            }
            debugMaxEver = 0f; debugFrameCount = 0;
            framesCollected = 0; frameRingPos = 0; newFramesSinceInfer = 0;
            if (lastTriggerAt != 0L) lastTriggerAt = System.currentTimeMillis();

            MelSpectrogramExtractor extractor = new MelSpectrogramExtractor();
            byte[] buf = new byte[CHUNK_BYTES];

            while (running.get()) {
                int read;
                try { read = recorder.read(buf, 0, CHUNK_BYTES); }
                catch (IllegalStateException e) { break; }
                if (read <= 0) { if (read < 0) Log.e(TAG, "read error: " + read); continue; }
                extractor.feed(buf, read, this::processFrame);
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

        // copy into ring (mel is a reused buffer)
        System.arraycopy(melFrame, 0, frameRing[frameRingPos % nFrames], 0, MelSpectrogramExtractor.N_MELS);
        frameRingPos++;
        framesCollected++;
        newFramesSinceInfer++;

        // esphome fills full stride size input sensor then invoces once
        // running every frame with overlapping windows corrupts ltsm state for nframes > 1
        if (framesCollected < nFrames || newFramesSinceInfer < nFrames) return;
        newFramesSinceInfer = 0;

        int base = frameRingPos % nFrames;
        Object outArray = outputIs8bit ? outputBufByte : outputBuf;
        if (hasChannelDim) {
            if (inputIs8bit) {
                for (int t = 0; t < nFrames; t++) {
                    float[] row = frameRing[(base + t) % nFrames];
                    for (int f = 0; f < MelSpectrogramExtractor.N_MELS; f++)
                        input4dByte[0][t][f][0] = quantize(row[f], inputScale, inputZeroPoint, inputIsUnsigned);
                }
                try { tflite.run(input4dByte, outArray); }
                catch (Exception e) { Log.e(TAG, "inference error", e); return; }
            } else {
                for (int t = 0; t < nFrames; t++) {
                    float[] row = frameRing[(base + t) % nFrames];
                    for (int f = 0; f < MelSpectrogramExtractor.N_MELS; f++) input4d[0][t][f][0] = row[f];
                }
                try { tflite.run(input4d, outArray); }
                catch (Exception e) { Log.e(TAG, "inference error", e); return; }
            }
        } else {
            if (inputIs8bit) {
                for (int t = 0; t < nFrames; t++) {
                    float[] row = frameRing[(base + t) % nFrames];
                    for (int f = 0; f < MelSpectrogramExtractor.N_MELS; f++)
                        input3dByte[0][t][f] = quantize(row[f], inputScale, inputZeroPoint, inputIsUnsigned);
                }
                try { tflite.run(input3dByte, outArray); }
                catch (Exception e) { Log.e(TAG, "inference error", e); return; }
            } else {
                for (int t = 0; t < nFrames; t++)
                    System.arraycopy(frameRing[(base + t) % nFrames], 0, input3d[0][t], 0, MelSpectrogramExtractor.N_MELS);
                try { tflite.run(input3d, outArray); }
                catch (Exception e) { Log.e(TAG, "inference error", e); return; }
            }
        }

        scoreAndMaybeDetect();
    }

    private void scoreAndMaybeDetect() {
        float rawScore;
        int rawByte = -1; // for diag
        if (outputIs8bit) {
            int idx = Math.min(positiveOutputIdx, outputBufByte[0].length - 1);
            byte b = outputBufByte[0][idx];
            rawByte = outputIsUnsigned ? (b & 0xFF) : (int) b;
            float scale = outputScale;
            if (scale == 0f) scale = outputIsUnsigned ? 1f / 255f : 1f / 127f; // if flatbuffer has no quant params scale comes back 0
            rawScore = (rawByte - outputZeroPoint) * scale;
        } else {
            int idx = Math.min(positiveOutputIdx, outputBuf[0].length - 1);
            rawScore = outputBuf[0][idx];
        }
        rawScore = Math.max(0f, rawScore);

        if (scoreWindow != null) {
            scoreWindowSum -= scoreWindow[scoreWindowPos];
            scoreWindow[scoreWindowPos] = rawScore;
            scoreWindowSum += rawScore;
            scoreWindowPos = (scoreWindowPos + 1) % effectiveWinSize;
        }
        float avgScore = (scoreWindow != null) ? scoreWindowSum / effectiveWinSize : rawScore;

        debugFrameCount++;
        if (avgScore > debugMaxEver) debugMaxEver = avgScore;
        boolean periodic = (debugFrameCount % 100 == 0);
        boolean notable  = (avgScore > 0.05f);
        if ((periodic || notable) && BuildConfig.DEBUG) {
            float melMin = Float.MAX_VALUE, melMax = -Float.MAX_VALUE;
            float[] recent = frameRing[(frameRingPos - 1 + nFrames) % nFrames];
            for (float v : recent) { if (v < melMin) melMin = v; if (v > melMax) melMax = v; }
            Log.d(TAG, (notable ? "!!! " : "    ")
                + "avg=" + String.format("%.4f", avgScore)
                + " raw=" + String.format("%.4f", rawScore)
                + " rawByte=" + rawByte
                + " thr=" + String.format("%.2f", scoreThreshold)
                + " maxEver=" + String.format("%.4f", debugMaxEver)
                + " mel=[" + String.format("%.1f", melMin) + ".." + String.format("%.1f", melMax) + "]"
                + " outScale=" + outputScale);
        }

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastScoreBroadcastMs >= 50) {
            lastScoreBroadcastMs = nowMs;
            final float bs = avgScore, bt = scoreThreshold;
            mainHandler.post(() -> LocalBroadcastManager.getInstance(context).sendBroadcast(
                    new Intent(Constants.INTENT_VOICE_SCORE)
                            .putExtra(Constants.INTENT_VOICE_SCORE_KEY, bs)
                            .putExtra(Constants.INTENT_VOICE_THRESHOLD_KEY, bt)));
        }

        if (avgScore >= scoreThreshold) {
            long now = System.currentTimeMillis();
            if ((now - lastTriggerAt) >= cooldownMs) {
                lastTriggerAt = now;
                Log.i(TAG, "wake word detected (score=" + String.format("%.3f", avgScore) + ")");
                callback.onWakeDetected();
            }
        }
    }

    private static byte quantize(float val, float scale, int zeroPoint, boolean unsigned) {
        int q = Math.round(val / scale) + zeroPoint;
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

    public void onDestroy() {
        stopAndWait();
        closeModel();
        executor.shutdownNow();
    }
}
