package me.rapierxbox.shellyelevatev2.voice;

// holla die waldfee
// getting this + the model loading to work was really hard
// this is approximating the tensorflow lite microspeech frontend
// 16khz 480sample hann window zeropad to 512, 40 mel bins (125-7500hz), 10ms hops
// spectral subtraction + pcan normalisation
// reminder to self: NOT THREAD SAFE
public class MelSpectrogramExtractor {

    public static final int SAMPLE_RATE = 16_000;
    public static final int WINDOW_SIZE = 480;   // 30ms hann window
    public static final int FFT_SIZE = 512;   // zeropads 480 sample window
    public static final int HOP_SAMPLES = 160;   // 10ms at 16khz
    public static final int N_MELS = 40;

    private static final float FREQ_MIN = 125f;
    private static final float FREQ_MAX = 7_500f;  // tflt micro speach default

    // noise reduction
    // alpha alternatives per mel bin: even bins->LAPHA_EVEN odd bins->ALPHA_ODD
    // src: noise_reduction.c
    private static final float ALPHA_EVEN = 0.025f; // NOISE_REDUCTION_EVEN_SMOOTHING
    private static final float ALPHA_ODD = 0.06f;  // NOISE_REDUCTION_ODD_SMOOTHING
    private static final float MIN_SIGNAL = 0.05f;  // NOISE_REDUCTION_MIN_SIGNAL_REMAINING

    // pcan
    private static final float PCAN_STRENGTH = 0.95f; // PCAN_GAIN_CONTROL_STRENGTH
    // tflm uses PCAN_OFFSET=80 calibrated for fixed point sqrt_mel units
    // java float sqrt_mel is 6-10 larger than tflm int scale -> scale down
    private static final float PCAN_OFFSET = 30f;

    // tflm outputs uint16 in [0, ~670] then esphome converts: int8 = (uint16*256)/666 - 128
    // equivalent float: mel = uint16 / 25.6  range [0, 26]
    // java: mel = clamp(ln(pcan+1) × LOG_COEFF, 0, 26)
    // LOG_COEFF calibrated so speech at ~30x background noise saturates near 26
    private static final float LOG_COEFF = 7.5f;
    private static final float OUT_MAX = 26.0f;

    // precomputed and shared over all instances
    private static final float[] HANN;   // WINDOW_SIZE coefficients
    private static final float[] TWIDDLE_RE;
    private static final float[] TWIDDLE_IM;
    private static final int[][] MEL_BINS;    // [N_MELS][nonZeroCount] sparse bin indices
    private static final float[][] MEL_WEIGHTS; // [N_MELS][nonZeroCount] sparse weights

    static {
        // 480p hann window
        HANN = new float[WINDOW_SIZE];
        for (int i = 0; i < WINDOW_SIZE; i++)
            HANN[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (WINDOW_SIZE - 1))));

        // fft twiddle table: W_N^k = e^{-2(pi)ik/N} for k = 0 .. N/2-1
        TWIDDLE_RE = new float[FFT_SIZE / 2];
        TWIDDLE_IM = new float[FFT_SIZE / 2];
        for (int k = 0; k < FFT_SIZE / 2; k++) {
            double a = -2.0 * Math.PI * k / FFT_SIZE;
            TWIDDLE_RE[k] = (float) Math.cos(a);
            TWIDDLE_IM[k] = (float) Math.sin(a);
        }

        // sparse triangular mel filterbank (only store non zero)
        // dense ~10k mul per frame sparse 600
        int half = FFT_SIZE / 2 + 1;
        float melMin = hzToMel(FREQ_MIN), melMax = hzToMel(FREQ_MAX);
        int nPts = N_MELS + 2;
        int[] bin = new int[nPts];
        for (int i = 0; i < nPts; i++) {
            float hz = melToHz(melMin + i * (melMax - melMin) / (nPts - 1));
            bin[i] = Math.min((int) Math.floor((FFT_SIZE + 1) * hz / SAMPLE_RATE), half - 1);
        }
        MEL_BINS = new int[N_MELS][];
        MEL_WEIGHTS = new float[N_MELS][];
        for (int m = 0; m < N_MELS; m++) {
            int lo = bin[m], center = bin[m + 1], hi = bin[m + 2];
            int count = 0;
            for (int k = lo; k <= hi; k++) if (filterW(k, lo, center, hi) > 0f) count++;
            MEL_BINS[m] = new int[count];
            MEL_WEIGHTS[m] = new float[count];
            int idx = 0;
            for (int k = lo; k <= hi; k++) {
                float w = filterW(k, lo, center, hi);
                if (w > 0f) { MEL_BINS[m][idx] = k; MEL_WEIGHTS[m][idx] = w; idx++; }
            }
        }
    }

    private final float[] ring = new float[WINDOW_SIZE];
    private int writePos = 0;
    private int totalSamples = 0;
    private int samplesUntilNextHop = 0;

    // per bin noise est... lazy init
    private final float[] noiseFloor = new float[N_MELS];

    // prealloc work buffers
    private final float[] re = new float[FFT_SIZE];
    private final float[] im = new float[FFT_SIZE];
    private final float[] mel = new float[N_MELS];

    public interface FrameCallback {
        void onFrame(float[] mel); // <- shared buffer
    }

    public void feed(byte[] pcm, int length, FrameCallback cb) {
        int sampleCount = length / 2;
        for (int i = 0; i < sampleCount; i++) {
            int b = i * 2;
            ring[writePos] = (float)((short) (((pcm[b + 1] & 0xFF) << 8) | (pcm[b] & 0xFF)));
            writePos = (writePos + 1) % WINDOW_SIZE;
            totalSamples++;

            if (samplesUntilNextHop == 0) samplesUntilNextHop = HOP_SAMPLES;
            if (--samplesUntilNextHop == 0 && totalSamples >= WINDOW_SIZE) {
                computeFrame();
                cb.onFrame(mel);
            }
        }
    }

    private void computeFrame() {
        // apply hann window and zeropad
        for (int i = 0; i < WINDOW_SIZE; i++) {
            re[i] = ring[(writePos + i) % WINDOW_SIZE] * HANN[i];
            im[i] = 0f;
        }
        for (int i = WINDOW_SIZE; i < FFT_SIZE; i++) { re[i] = 0f; im[i] = 0f; }

        fft(re, im);

        for (int m = 0; m < N_MELS; m++) {
            // mel filterbank
            float energy = 0f;
            int[]   bins = MEL_BINS[m];
            float[] wts  = MEL_WEIGHTS[m];
            for (int j = 0, n = bins.length; j < n; j++) {
                int k = bins[j];
                energy += wts[j] * (re[k] * re[k] + im[k] * im[k]);
            }

            // power to amp domain
            float sqrtE = (float) Math.sqrt(energy);

            // noise estimate
            float alpha = (m % 2 == 0) ? ALPHA_EVEN : ALPHA_ODD;
            noiseFloor[m] = alpha * sqrtE + (1f - alpha) * noiseFloor[m];

            // spectral sub tflm clamps est locally before sub
            // prevents neg sub when noise_est exeeds sig
            float estClamped = Math.min(noiseFloor[m], sqrtE);
            float sub = Math.max(sqrtE - estClamped, MIN_SIGNAL * sqrtE);

            // pcan
            float pcan = sub * PCAN_STRENGTH / (noiseFloor[m] + PCAN_OFFSET);

            // log scale -> model input domain
            // tflm uint16 output / 25.6 = float; java: ln(pcan+1) × LOG_COEFF
            mel[m] = Math.min(OUT_MAX, (float) Math.log1p(pcan) * LOG_COEFF);
        }
    }

    // cooley-tukey radix-2 dit fft with precomputed twiddle table
    private static void fft(float[] re, float[] im) {
        int n = FFT_SIZE;
        // bit reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                      t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        // butterfly stages
        for (int len = 2; len <= n; len <<= 1) {
            int step = n / len;
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                for (int j = 0; j < half; j++) {
                    float wRe = TWIDDLE_RE[j * step], wIm = TWIDDLE_IM[j * step];
                    float uRe = re[i + j], uIm = im[i + j];
                    float vRe = re[i + j + half] * wRe - im[i + j + half] * wIm;
                    float vIm = re[i + j + half] * wIm + im[i + j + half] * wRe;
                    re[i + j] = uRe + vRe;  im[i + j] = uIm + vIm;
                    re[i + j + half] = uRe - vRe; im[i + j + half] = uIm - vIm;
                }
            }
        }
    }

    // replicates the two-pass triangular filter assignment from the original dense build
    // replicatis twopass triangular filter assigntment from dense build
    private static float filterW(int k, int lo, int center, int hi) {
        float w = 0f;
        if (k >= lo && k <= center && center > lo) w = (float) (k - lo)  / (center - lo);
        if (k >= center && k <= hi && hi > center) w = (float) (hi - k) / (hi - center);
        return w;
    }

    private static float hzToMel(float hz) { return 2595f * (float) Math.log10(1f + hz / 700f); }
    private static float melToHz(float mel) { return 700f * ((float) Math.pow(10.0, mel / 2595.0) - 1f); }
}
