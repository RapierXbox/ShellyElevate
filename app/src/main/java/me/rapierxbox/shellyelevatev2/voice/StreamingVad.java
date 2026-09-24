package me.rapierxbox.shellyelevatev2.voice;

import android.content.Context;
import android.util.Log;

import java.io.File;

// ml voice activity detector used to find the end of speech during capture
// without a usable model it reports speech as always active so callers fall
// back to their rms vad instead of cutting off legitimate audio
// not thread safe so one instance per capture thread
public class StreamingVad implements AutoCloseable {
    private static final String TAG = "StreamingVad";
    private static final int DEFAULT_WINDOW = 5;
    private static final float DEFAULT_CUTOFF = 0.5f;

    private final StreamingModel model;
    private final FeatureFrontend frontend;
    private final StreamingModel.ScoreWindow scoreWindow;
    private final int positiveOutputIdx;
    private final float threshold;

    private volatile boolean speechActive = false;
    private volatile long lastSpeechAtNs = 0L;
    private volatile boolean everActive = false;

    public static boolean isModelPresent(Context context) {
        return modelFile(context).exists();
    }

    private static File modelFile(Context context) {
        return new File(StreamingModel.modelDir(context), WakeWordDetector.VAD_MODEL_NAME + ".tflite");
    }

    public StreamingVad(Context context) {
        StreamingModel loaded = null;
        FeatureFrontend loadedFrontend = null;
        StreamingModel.Config cfg = null;

        File file = modelFile(context);
        if (file.exists()) {
            try {
                loaded = StreamingModel.load(file, false);
                cfg = StreamingModel.Config.read(
                        new File(StreamingModel.modelDir(context), WakeWordDetector.VAD_MODEL_NAME + ".json"),
                        loaded.outputCols, DEFAULT_WINDOW, DEFAULT_CUTOFF);
                loadedFrontend = new NativeFeatureFrontend();
                Log.i(TAG, "VAD loaded: nFrames=" + loaded.nFrames + " window=" + cfg.windowSize + " cutoff=" + cfg.cutoff);
            } catch (Exception | LinkageError e) {
                // a missing native frontend lands here too and must not break capture
                Log.e(TAG, "failed to load VAD model, falling back to RMS VAD", e);
                if (loaded != null) loaded.close();
                loaded = null;
                loadedFrontend = null;
            }
        } else {
            Log.w(TAG, "VAD model not present, end-of-speech will fall back to RMS VAD");
        }

        model = loaded;
        frontend = loadedFrontend;
        boolean ok = loaded != null;
        scoreWindow = ok ? new StreamingModel.ScoreWindow(cfg.windowSize) : null;
        positiveOutputIdx = ok ? cfg.positiveIdx : 0;
        threshold = ok ? cfg.cutoff : DEFAULT_CUTOFF;
    }

    public boolean hasModel() { return model != null; }

    public boolean isSpeechActive() { return !hasModel() || speechActive; }

    public boolean everActive() { return !hasModel() || everActive; }

    public long silenceMsSinceSpeech() {
        if (!hasModel() || !everActive || speechActive || lastSpeechAtNs == 0L) return 0L;
        return (System.nanoTime() - lastSpeechAtNs) / 1_000_000L;
    }

    public void feed(byte[] pcm, int length) {
        if (!hasModel()) return;
        frontend.feed(pcm, length, this::onMelFrame);
    }

    private void onMelFrame(float[] mel) {
        if (!model.pushFrame(mel)) return;
        try {
            model.run();
        } catch (Exception e) {
            Log.e(TAG, "VAD inference error", e);
            return;
        }

        boolean active = scoreWindow.add(model.readScore(positiveOutputIdx)) >= threshold;
        if (active) {
            lastSpeechAtNs = System.nanoTime();
            everActive = true;
        }
        speechActive = active;
    }

    @Override
    public void close() {
        if (model != null) model.close();
        if (frontend != null) frontend.close();
    }
}
