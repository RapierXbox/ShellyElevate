package me.rapierxbox.shellyelevatev2.voice;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class StreamingVad {
    private static final String TAG = "StreamingVad";
    private static final String VAD_MODEL_NAME = "vad";

    private final Interpreter tflite;
    private final boolean hasModel;
    private final int nFrames;
    private final boolean hasChannelDim;
    private final boolean inputIs8bit;
    private final boolean inputIsUnsigned;
    private final int inputZeroPoint;
    private final boolean outputIs8bit;
    private final boolean outputIsUnsigned;
    private final float outputScale;
    private final int outputZeroPoint;
    private final int positiveOutputIdx;
    private final float threshold;

    private final ByteBuffer inputBufferByte; // int8 fast path
    private final ByteBuffer outputBufferByte; // int8 output
    private final float[][][] input3dFloat;
    private final float[][][][] input4dFloat;
    private final float[][] outputBufFloat;
    private final int outputCols;

    private final FeatureFrontend extractor;
    private final float[][] frameRing;
    private int frameRingPos = 0;
    private int framesCollected = 0;
    private int newFramesSinceInfer = 0;

    private final float[] scoreWindow;
    private int scoreWindowPos = 0;
    private float scoreWindowSum = 0f;

    private volatile boolean speechActive = false;
    private volatile long lastSpeechAtNs = 0L;
    private volatile boolean everActive = false;

    public static boolean isModelPresent(Context context) {
        return new File(new File(context.getFilesDir(), "wakewords"), VAD_MODEL_NAME + ".tflite").exists();
    }

    public StreamingVad(Context context) {
        File file = new File(new File(context.getFilesDir(), "wakewords"), VAD_MODEL_NAME + ".tflite");
        Interpreter interp = null;
        int nf = 0; boolean chDim = false;
        boolean in8 = false, inU = false; int inZp = 0;
        boolean out8 = false, outU = false; float outScl = 1f; int outZp = 0;
        int posIdx = 0;
        float thr = 0.5f;
        int win = 5;

        ByteBuffer inBuf = null; ByteBuffer outBuf = null;
        float[][][] i3f = null; float[][][][] i4f = null;
        float[][] of = null;
        int outColsVal = 0;

        if (file.exists()) {
            try {
                interp = WakeWordDetector.buildInterpreter(loadMappedFile(file));
                int[] shape = interp.getInputTensor(0).shape();
                if (shape.length == 3) { nf = shape[1]; chDim = false; }
                else if (shape.length == 4) { nf = shape[1]; chDim = true; }
                else throw new IllegalStateException("unsupported VAD input rank: " + shape.length);
                if (nf <= 0) throw new IllegalStateException("invalid VAD nFrames");

                org.tensorflow.lite.DataType inType = interp.getInputTensor(0).dataType();
                org.tensorflow.lite.DataType outType = interp.getOutputTensor(0).dataType();
                in8 = inType == org.tensorflow.lite.DataType.INT8 || inType == org.tensorflow.lite.DataType.UINT8;
                out8 = outType == org.tensorflow.lite.DataType.INT8 || outType == org.tensorflow.lite.DataType.UINT8;
                inU = inType == org.tensorflow.lite.DataType.UINT8;
                outU = outType == org.tensorflow.lite.DataType.UINT8;
                inZp = in8 ? interp.getInputTensor(0).quantizationParams().getZeroPoint() : 0;
                outScl = out8 ? interp.getOutputTensor(0).quantizationParams().getScale() : 1f;
                outZp = out8 ? interp.getOutputTensor(0).quantizationParams().getZeroPoint() : 0;

                int outCols = interp.getOutputTensor(0).shape()[interp.getOutputTensor(0).shape().length - 1];
                outColsVal = outCols;
                if (in8) {
                    inBuf = ByteBuffer.allocateDirect(nf * NativeMelExtractor.N_MELS).order(ByteOrder.nativeOrder());
                } else if (chDim) {
                    i4f = new float[1][nf][NativeMelExtractor.N_MELS][1];
                } else {
                    i3f = new float[1][nf][NativeMelExtractor.N_MELS];
                }
                if (out8) outBuf = ByteBuffer.allocateDirect(outCols).order(ByteOrder.nativeOrder());
                else      of = new float[1][outCols];

                // parse companion json for threshold + window; fallbacks are the v2 defaults
                File jsonFile = new File(new File(context.getFilesDir(), "wakewords"), VAD_MODEL_NAME + ".json");
                if (jsonFile.exists()) {
                    try {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(new FileReader(jsonFile))) {
                            String line; while ((line = br.readLine()) != null) sb.append(line);
                        }
                        JSONObject json = new JSONObject(sb.toString());
                        JSONObject cfg = json.has("micro") ? json.getJSONObject("micro") : json;
                        if (cfg.has("sliding_window_size")) win = Math.max(1, cfg.getInt("sliding_window_size"));
                        else if (cfg.has("sliding_window_average_size")) win = Math.max(1, cfg.getInt("sliding_window_average_size"));
                        if (cfg.has("probability_cutoff")) thr = (float) cfg.getDouble("probability_cutoff");
                        if (json.has("class_mapping") && json.has("positive_output_class")) {
                            String posClass = json.getString("positive_output_class");
                            JSONObject mapping = json.getJSONObject("class_mapping");
                            for (int idx = 0; idx < outCols; idx++) {
                                String key = String.valueOf(idx);
                                if (mapping.has(key) && posClass.equals(mapping.getString(key))) {
                                    posIdx = idx; break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "VAD json parse failed: " + e.getMessage());
                    }
                }
                Log.i(TAG, "VAD loaded: nFrames=" + nf + " window=" + win + " cutoff=" + thr);
            } catch (Exception e) {
                Log.e(TAG, "failed to load VAD model", e);
                if (interp != null) { try { interp.close(); } catch (Exception ignored) {} }
                interp = null;
            }
        } else {
            Log.w(TAG, "VAD model not present, end-of-speech will fall back to RMS VAD");
        }

        this.tflite = interp;
        this.hasModel = interp != null;
        this.nFrames = nf;
        this.hasChannelDim = chDim;
        this.inputIs8bit = in8; this.inputIsUnsigned = inU; this.inputZeroPoint = inZp;
        this.outputIs8bit = out8; this.outputIsUnsigned = outU; this.outputScale = outScl; this.outputZeroPoint = outZp;
        this.positiveOutputIdx = posIdx;
        this.threshold = thr;
        this.inputBufferByte = inBuf; this.outputBufferByte = outBuf;
        this.input3dFloat = i3f; this.input4dFloat = i4f;
        this.outputBufFloat = of;
        this.outputCols = outColsVal;

        this.extractor = new NativeFeatureFrontend();
        this.frameRing = hasModel ? new float[nFrames][NativeMelExtractor.N_MELS] : null;
        this.scoreWindow = hasModel ? new float[Math.max(1, win)] : null;
    }

    public boolean hasModel() { return hasModel; }

    // permissive when no model
    public boolean isSpeechActive() { return !hasModel || speechActive; }

    public boolean everActive() { return !hasModel || everActive; }

    public long silenceMsSinceSpeech() {
        if (!hasModel || !everActive) return 0L;
        if (speechActive) return 0L;
        if (lastSpeechAtNs == 0L) return 0L;
        return (System.nanoTime() - lastSpeechAtNs) / 1_000_000L;
    }

    public void feed(byte[] pcm, int length) {
        if (!hasModel) return;
        extractor.feed(pcm, length, this::onMelFrame);
    }

    private void onMelFrame(float[] mel) {
        System.arraycopy(mel, 0, frameRing[frameRingPos % nFrames], 0, NativeMelExtractor.N_MELS);
        frameRingPos++;
        framesCollected++;
        newFramesSinceInfer++;

        if (framesCollected < nFrames || newFramesSinceInfer < nFrames) return;
        newFramesSinceInfer = 0;

        int base = frameRingPos % nFrames;
        try {
            if (inputIs8bit) {
                inputBufferByte.rewind();
                for (int t = 0; t < nFrames; t++) {
                    float[] row = frameRing[(base + t) % nFrames];
                    for (int f = 0; f < NativeMelExtractor.N_MELS; f++)
                        inputBufferByte.put(quantize(row[f], inputZeroPoint, inputIsUnsigned));
                }
                inputBufferByte.rewind();
                Object out = outputIs8bit ? prepOut(outputBufferByte) : outputBufFloat;
                tflite.run(inputBufferByte, out);
            } else if (hasChannelDim) {
                for (int t = 0; t < nFrames; t++) {
                    float[] row = frameRing[(base + t) % nFrames];
                    for (int f = 0; f < NativeMelExtractor.N_MELS; f++) input4dFloat[0][t][f][0] = row[f];
                }
                Object out = outputIs8bit ? prepOut(outputBufferByte) : outputBufFloat;
                tflite.run(input4dFloat, out);
            } else {
                for (int t = 0; t < nFrames; t++)
                    System.arraycopy(frameRing[(base + t) % nFrames], 0, input3dFloat[0][t], 0, NativeMelExtractor.N_MELS);
                Object out = outputIs8bit ? prepOut(outputBufferByte) : outputBufFloat;
                tflite.run(input3dFloat, out);
            }
        } catch (Exception e) {
            Log.e(TAG, "VAD inference error", e);
            return;
        }

        float rawScore;
        if (outputIs8bit) {
            int idx = Math.min(positiveOutputIdx, outputCols - 1);
            byte b = outputBufferByte.get(idx);
            float scale = outputScale;
            int zp = outputZeroPoint;
            int rawByte;
            if (scale == 0f) { scale = 1f / 255f; rawByte = b & 0xFF; zp = 0; }
            else { rawByte = outputIsUnsigned ? (b & 0xFF) : (int) b; }
            rawScore = (rawByte - zp) * scale;
        } else {
            int idx = Math.min(positiveOutputIdx, outputBufFloat[0].length - 1);
            rawScore = outputBufFloat[0][idx];
        }
        rawScore = Math.max(0f, rawScore);

        scoreWindowSum -= scoreWindow[scoreWindowPos];
        scoreWindow[scoreWindowPos] = rawScore;
        scoreWindowSum += rawScore;
        scoreWindowPos = (scoreWindowPos + 1) % scoreWindow.length;

        boolean active = (scoreWindowSum / scoreWindow.length) >= threshold;
        if (active) {
            lastSpeechAtNs = System.nanoTime();
            everActive = true;
        }
        speechActive = active;
    }

    private static ByteBuffer prepOut(ByteBuffer b) { b.rewind(); return b; }

    private static byte quantize(float val, int zeroPoint, boolean unsigned) {
        float range = unsigned ? 255f : (127f - zeroPoint);
        int q = Math.round(val * range / NativeMelExtractor.OUT_MAX) + zeroPoint;
        return unsigned ? (byte) Math.max(0, Math.min(255, q))
                        : (byte) Math.max(-128, Math.min(127, q));
    }

    public void close() {
        if (tflite != null) { try { tflite.close(); } catch (Exception ignored) {} }
        if (extractor != null) extractor.close();
    }

    private static MappedByteBuffer loadMappedFile(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file); FileChannel ch = fis.getChannel()) {
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
    }
}
