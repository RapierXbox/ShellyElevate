package me.rapierxbox.shellyelevatev2.voice.speaker;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

// one redimnet2 model with the mel frontend inside the graph
// input [1 window] pcm as float in -1..1 and output [1 dim] l2 normalised
// not thread safe so the recognizer runs it on its own executor
final class SpeakerEmbedder implements AutoCloseable {
    static final int SAMPLE_RATE = 16000;
    // 2 s windows like the training crops
    static final int WINDOW = 2 * SAMPLE_RATE;

    private final Interpreter interpreter;
    private final float[][] input = new float[1][WINDOW];
    private final float[][] output;
    final int dim;

    SpeakerEmbedder(File model, int threads) throws IOException {
        // the float xnnpack path is fine here since #105 was its int8 gemm
        // the plain kernels were a third slower and crashed in benchmark_model on the dual a7 displays
        Interpreter.Options opts = new Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true);
        try (FileInputStream fis = new FileInputStream(model); FileChannel ch = fis.getChannel()) {
            interpreter = new Interpreter(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()), opts);
        }
        int[] in = interpreter.getInputTensor(0).shape();
        int[] out = interpreter.getOutputTensor(0).shape();
        if (in.length != 2 || in[1] != WINDOW || out.length != 2) {
            interpreter.close();
            throw new IOException("unexpected speaker model shape");
        }
        dim = out[1];
        output = new float[1][dim];
    }

    // pcm16 le mono. longer audio is covered by evenly spread windows whose embeddings are averaged
    // and shorter audio is repeated to fill one window
    float[] embed(byte[] pcm, int off, int len) {
        int samples = len / 2;
        if (samples == 0) return null;
        float[] sum = new float[dim];
        int windows = (samples + WINDOW - 1) / WINDOW;
        for (int w = 0; w < windows; w++) {
            int start = windows == 1 ? 0 : (int) ((long) w * (samples - WINDOW) / (windows - 1));
            for (int i = 0; i < WINDOW; i++) {
                int s = off + 2 * ((start + i) % samples);
                input[0][i] = (short) ((pcm[s] & 0xff) | (pcm[s + 1] << 8)) / 32768f;
            }
            interpreter.run(input, output);
            for (int i = 0; i < dim; i++) sum[i] += output[0][i];
        }
        return SpeakerMatcher.normalize(sum);
    }

    @Override
    public void close() {
        interpreter.close();
    }
}
