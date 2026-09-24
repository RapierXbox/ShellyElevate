package me.rapierxbox.shellyelevatev2.voice;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

// one streaming microwakeword style tflite model fed a mel frame at a time
// shared by the wake word detector and both vad paths
// input shape [1 n 40] or [1 n 40 1] as f32 or i8 and output [1 k] as f32 or i8
// not thread safe so callers serialize access
final class StreamingModel {
    private static final String TAG = "StreamingModel";
    private static final String MODEL_DIR = "wakewords";
    private static final int N_MELS = NativeMelExtractor.N_MELS;

    private final File file;
    private Interpreter interpreter;
    // set once a session ran the interpreter so its variable state is dirty
    private boolean interpreterUsed;

    final int nFrames;
    final int outputCols;
    private final boolean hasChannelDim;
    private final boolean inputIs8bit;
    private final boolean inputIsUnsigned;
    private final float inputScale;
    private final int inputZeroPoint;
    private final boolean outputIs8bit;
    private final boolean outputIsUnsigned;
    private final float outputScale;
    private final int outputZeroPoint;
    private final String description;

    // only the byte buffers or the float arrays are allocated depending on dtype
    private final ByteBuffer inputBytes;
    private final ByteBuffer outputBytes;
    private final float[][][] input3d;
    private final float[][][][] input4d;
    private final float[][] outputFloats;

    private final float[][] frameRing;
    // long so ring index math never overflows on long uptimes
    private long frameRingPos;
    private long framesCollected;
    private int newFramesSinceInfer;
    private int lastRawByte = -1;

    static File modelDir(Context context) {
        return new File(context.getFilesDir(), MODEL_DIR);
    }

    // builds the model or throws with the interpreter already closed
    static StreamingModel load(File file, boolean fallbackMissingInputQuant) throws IOException {
        Interpreter interp = buildInterpreter(file);
        try {
            return new StreamingModel(file, interp, fallbackMissingInputQuant);
        } catch (RuntimeException e) {
            interp.close();
            throw e;
        }
    }

    private StreamingModel(File file, Interpreter interp, boolean fallbackMissingInputQuant) {
        this.file = file;
        this.interpreter = interp;

        Tensor in = interp.getInputTensor(0);
        Tensor out = interp.getOutputTensor(0);

        int[] shape = in.shape();
        if (shape.length != 3 && shape.length != 4)
            throw new IllegalArgumentException("unsupported input rank: " + shape.length);
        nFrames = shape[1];
        hasChannelDim = shape.length == 4;
        if (nFrames <= 0) throw new IllegalArgumentException("invalid nFrames=" + nFrames);

        DataType inType = in.dataType();
        DataType outType = out.dataType();
        inputIs8bit = is8bit(inType);
        outputIs8bit = is8bit(outType);
        inputIsUnsigned = inType == DataType.UINT8;
        outputIsUnsigned = outType == DataType.UINT8;

        float inScale = inputIs8bit ? in.quantizationParams().getScale() : 1f;
        int inZeroPoint = inputIs8bit ? in.quantizationParams().getZeroPoint() : 0;
        // some models ship without input quant params so val / scale would be inf
        // and every bin rounds to 127 so use the mww v2 mapping instead
        if (fallbackMissingInputQuant && inputIs8bit && inScale == 0f) {
            inScale = NativeMelExtractor.OUT_MAX / 255f;
            inZeroPoint = -128;
            Log.w(TAG, "input quant params missing for " + file.getName() + ", using fallback");
        }
        inputScale = inScale;
        inputZeroPoint = inZeroPoint;
        outputScale = outputIs8bit ? out.quantizationParams().getScale() : 1f;
        outputZeroPoint = outputIs8bit ? out.quantizationParams().getZeroPoint() : 0;

        int[] outShape = out.shape();
        outputCols = outShape[outShape.length - 1];

        if (inputIs8bit) {
            inputBytes = ByteBuffer.allocateDirect(nFrames * N_MELS).order(ByteOrder.nativeOrder());
            input3d = null;
            input4d = null;
        } else {
            inputBytes = null;
            input3d = hasChannelDim ? null : new float[1][nFrames][N_MELS];
            input4d = hasChannelDim ? new float[1][nFrames][N_MELS][1] : null;
        }
        if (outputIs8bit) {
            outputBytes = ByteBuffer.allocateDirect(outputCols).order(ByteOrder.nativeOrder());
            outputFloats = null;
        } else {
            outputBytes = null;
            outputFloats = new float[1][outputCols];
        }
        frameRing = new float[nFrames][N_MELS];

        description = "input=" + Arrays.toString(shape)
                + " inType=" + inType + " outType=" + outType
                + " inScale=" + inputScale + " inZP=" + inputZeroPoint
                + " outScale=" + outputScale + " outZP=" + outputZeroPoint;
    }

    private static boolean is8bit(DataType type) {
        return type == DataType.INT8 || type == DataType.UINT8;
    }

    boolean hasInterpreter() { return interpreter != null; }

    String describe() { return description; }

    // resetting variable tensors is unreliable for converted v2 streaming graphs
    // so some models carry lstm state across sessions and drift upward on silence
    // until they false fire. a rebuild from the file guarantees fresh state while
    // an interpreter that never ran is reused as is
    void ensureFreshInterpreter() throws IOException {
        if (interpreter == null || interpreterUsed) {
            if (interpreter != null) {
                interpreter.close();
                interpreter = null;
            }
            interpreter = buildInterpreter(file);
        }
        interpreterUsed = true;
    }

    void resetStream() {
        frameRingPos = 0;
        framesCollected = 0;
        newFramesSinceInfer = 0;
    }

    // copies the frame since the frontend reuses its buffer and returns true once
    // a full fresh window is ready. esphome fills a whole stride before invoking
    // and overlapping windows would corrupt the lstm state for any n above one
    boolean pushFrame(float[] mel) {
        System.arraycopy(mel, 0, frameRing[(int) (frameRingPos % nFrames)], 0, N_MELS);
        frameRingPos++;
        framesCollected++;
        newFramesSinceInfer++;
        if (framesCollected < nFrames || newFramesSinceInfer < nFrames) return false;
        newFramesSinceInfer = 0;
        return true;
    }

    float[] latestFrame() {
        return frameRing[(int) ((frameRingPos - 1 + nFrames) % nFrames)];
    }

    void run() {
        // covers a model swapped in while a session is already running
        interpreterUsed = true;
        int base = (int) (frameRingPos % nFrames);
        Object out = outputIs8bit ? rewound(outputBytes) : outputFloats;
        if (inputIs8bit) {
            inputBytes.rewind();
            for (int t = 0; t < nFrames; t++) {
                float[] row = frameRing[(base + t) % nFrames];
                for (int f = 0; f < N_MELS; f++)
                    inputBytes.put(quantizeMel(row[f], inputZeroPoint, inputIsUnsigned));
            }
            inputBytes.rewind();
            interpreter.run(inputBytes, out);
        } else if (hasChannelDim) {
            for (int t = 0; t < nFrames; t++) {
                float[] row = frameRing[(base + t) % nFrames];
                for (int f = 0; f < N_MELS; f++) input4d[0][t][f][0] = row[f];
            }
            interpreter.run(input4d, out);
        } else {
            for (int t = 0; t < nFrames; t++)
                System.arraycopy(frameRing[(base + t) % nFrames], 0, input3d[0][t], 0, N_MELS);
            interpreter.run(input3d, out);
        }
    }

    // score of the given output class from the last run clamped to zero
    float readScore(int positiveIdx) {
        float raw;
        if (outputIs8bit) {
            byte b = outputBytes.get(Math.min(positiveIdx, outputCols - 1));
            float scale = outputScale;
            int zeroPoint = outputZeroPoint;
            int rawByte;
            if (scale == 0f) {
                // no quant params so treat the byte as an unsigned probability
                scale = 1f / 255f;
                rawByte = b & 0xFF;
                zeroPoint = 0;
            } else {
                rawByte = outputIsUnsigned ? (b & 0xFF) : (int) b;
            }
            lastRawByte = rawByte;
            raw = (rawByte - zeroPoint) * scale;
        } else {
            lastRawByte = -1;
            raw = outputFloats[0][Math.min(positiveIdx, outputFloats[0].length - 1)];
        }
        return Math.max(0f, raw);
    }

    // quantized output byte behind the last score or -1 for float models
    int lastRawByte() { return lastRawByte; }

    void close() {
        if (interpreter != null) {
            try { interpreter.close(); } catch (Exception ignored) {}
            interpreter = null;
        }
    }

    private static ByteBuffer rewound(ByteBuffer b) {
        b.rewind();
        return b;
    }

    // maps the [0 out_max] mel float onto the full int8 range using only the zero
    // point and ignores the declared input scale. training pipelines disagree on it
    //   okay_nabu      uses float [0 26]   features (scale ~= 0.102)
    //   tatertotterson uses uint16 [0 666] features (scale ~= 2.61)
    // both are the same signal up to a 25.6x linear rescale so this yields
    // identical quantized values whichever scale the model expects
    static byte quantizeMel(float val, int zeroPoint, boolean unsigned) {
        float range = unsigned ? 255f : (127f - zeroPoint);
        int q = Math.round(val * range / NativeMelExtractor.OUT_MAX) + zeroPoint;
        return unsigned ? (byte) Math.max(0, Math.min(255, q))
                        : (byte) Math.max(-128, Math.min(127, q));
    }

    static Interpreter buildInterpreter(File file) throws IOException {
        Interpreter.Options opts = new Interpreter.Options().setNumThreads(1);
        // plain cpu kernels only (#105)
        // xnnpack aarch32 qs8 gemm segfaults on the 32 bit wall displays
        // (x2 pegasus and gen1 stargate) as soon as a model runs on live audio
        // nnapi on these socs leaks a thread and memory mappings per inference
        // until pthread_create fails (~3 min on the x2) and gains nothing here
        opts.setUseXNNPACK(false);
        opts.setUseNNAPI(false);
        return new Interpreter(mapFile(file), opts);
    }

    private static MappedByteBuffer mapFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file); FileChannel ch = fis.getChannel()) {
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
    }

    // sliding mean over the last n scores as in the mww reference implementation
    static final class ScoreWindow {
        private final float[] window;
        private int pos;
        private float sum;

        ScoreWindow(int size) {
            window = new float[Math.max(1, size)];
        }

        float add(float score) {
            sum -= window[pos];
            window[pos] = score;
            sum += score;
            pos = (pos + 1) % window.length;
            return sum / window.length;
        }

        void reset() {
            Arrays.fill(window, 0f);
            pos = 0;
            sum = 0f;
        }
    }

    // tuning from the companion json with mww v2 defaults for missing keys
    static final class Config {
        int windowSize;
        float cutoff;
        boolean cutoffFromJson;
        int positiveIdx;

        private Config(int defaultWindow, float defaultCutoff) {
            windowSize = defaultWindow;
            cutoff = defaultCutoff;
        }

        // a parse error keeps whatever was read before it
        static Config read(File jsonFile, int outCols, int defaultWindow, float defaultCutoff) {
            Config cfg = new Config(defaultWindow, defaultCutoff);
            if (!jsonFile.exists()) {
                Log.d(TAG, "no " + jsonFile.getName() + " found, using defaults");
                return cfg;
            }
            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new FileReader(jsonFile))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                JSONObject json = new JSONObject(sb.toString());
                // tatertotterson models nest the config under micro while esphome ones are flat
                JSONObject micro = json.has("micro") ? json.getJSONObject("micro") : json;

                if (micro.has("sliding_window_size"))
                    cfg.windowSize = Math.max(1, micro.getInt("sliding_window_size"));
                else if (micro.has("sliding_window_average_size"))
                    cfg.windowSize = Math.max(1, micro.getInt("sliding_window_average_size"));

                if (micro.has("probability_cutoff")) {
                    cfg.cutoff = (float) micro.getDouble("probability_cutoff");
                    cfg.cutoffFromJson = true;
                }

                // tatertotterson models lack the mapping and effectively use index 0
                if (json.has("class_mapping") && json.has("positive_output_class")) {
                    String posClass = json.getString("positive_output_class");
                    JSONObject mapping = json.getJSONObject("class_mapping");
                    for (int idx = 0; idx < outCols; idx++) {
                        String key = String.valueOf(idx);
                        if (mapping.has(key) && posClass.equals(mapping.getString(key))) {
                            cfg.positiveIdx = idx;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "could not parse " + jsonFile.getName() + ": " + e.getMessage());
            }
            return cfg;
        }
    }
}
