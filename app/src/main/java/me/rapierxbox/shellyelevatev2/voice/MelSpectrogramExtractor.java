package me.rapierxbox.shellyelevatev2.voice;

// java port of tensorflow lite micro microfrontend lib
// (tensorflow/lite/experimental/microfrontend/lib in the tflite-micro repo)
//
// pipeline matches frontend.c exactly: hann window -> fft -> |.|^2 ->
//  filterbank accumulate -> sqrt(>>scale_down) -> noise reduction ->
//  pcan gain control -> log scale -> uint16 -> float via /OUT_DIVIDE
//
// the post-fft stages (filterbank, noise_reduction, pcan_gain_control,
// log_scale) are ported in fixed-point integer/long math to match the
// reference c implementation. fft is computed in float for performance
// then rescaled to match kissfft int16 magnitudes so all downstream
// fixed-point constants (kNoiseReductionBits=14, kPcanSnrBits=12,
// pcan offset=80 strength=0.95 gain_bits=21, ...) operate in the
// trained value range and produce features compatible with any
// micro_wake_word v2 model.
//
// configuration matches esphome micro_wake_word component: 30ms hann
// window, 10ms hop, 40 mel bins, 125-7500 hz, pcan enabled
//
// NOT THREAD SAFE!!! per instance frontend state
public class MelSpectrogramExtractor {

    public static final int SAMPLE_RATE = 16_000;
    public static final int WINDOW_SIZE = 480; // 30ms hann window
    public static final int FFT_SIZE = 512; // zero pads 480 sample window
    public static final int HOP_SAMPLES = 160; // 10ms at 16khz
    public static final int N_MELS = 40;

    static final float OUT_MAX = 26.0f; // uint16 / 25.6 upper bound
    private static final float OUT_DIVIDE = 25.6f;

    // constants from tflm headers
    private static final int K_FRONTEND_WINDOW_BITS = 12; // window.h
    private static final int K_FILTERBANK_BITS = 12; // filterbank.h
    private static final int K_NOISE_REDUCTION_BITS = 14; // noise_reduction.h
    private static final int K_PCAN_SNR_BITS = 12; // pcan_gain_control.h
    private static final int K_PCAN_OUTPUT_BITS = 6; // pcan_gain_control.h
    private static final int K_LOG_SCALE_LOG2 = 16; // log_lut.h
    private static final int K_LOG_SCALE = 1 << K_LOG_SCALE_LOG2;
    private static final int K_LOG_COEFF = 45426; // log_lut.h (= ln(2)*65536)
    private static final int K_LOG_SEGMENTS_LOG2 = 7; // log_lut.h
    private static final int K_WIDE_DYN_FN_BITS = 32; // pcan_gain_control_util.h
    private static final int K_WIDE_DYN_FN_LUT_SIZE = 4 * K_WIDE_DYN_FN_BITS - 3; // 125

    // default config (matches frontend defaults + esphome mwww overrides)
    private static final float FB_LOWER_HZ = 125f;
    private static final float FB_UPPER_HZ = 7500f;

    private static final int NR_SMOOTHING_BITS = 10; // runtime smoothing_bits
    private static final int NR_EVEN_SMOOTH_Q14 = (int) Math.floor(0.025f * (1 << K_NOISE_REDUCTION_BITS) + 0.5f); // 410
    private static final int NR_ODD_SMOOTH_Q14 = (int) Math.floor(0.06f  * (1 << K_NOISE_REDUCTION_BITS) + 0.5f); // 983
    private static final int NR_MIN_SIGNAL_Q14 = (int) Math.floor(0.05f  * (1 << K_NOISE_REDUCTION_BITS) + 0.5f); // 819

    private static final int PCAN_GAIN_BITS = 21;
    private static final double PCAN_OFFSET = 80.0;
    private static final double PCAN_STRENGTH = 0.95; // negative exponent of (x+offset)

    private static final int LOG_SCALE_SHIFT = 6;

    // input_correction_bits = MostSignificantBit32(fft_size) - 1 - (kFilterbankBits / 2) = MSB(512) - 1 - 6 = 10 - 1 - 6 = 3
    private static final int INPUT_CORRECTION_BITS = msb32(FFT_SIZE) - 1 - (K_FILTERBANK_BITS / 2);
    // pcan input_bits = smoothing_bits - input_correction_bits  (used to build the lut)
    private static final int PCAN_INPUT_BITS = NR_SMOOTHING_BITS - INPUT_CORRECTION_BITS; // 7
    // pcan snr_shift = gain_bits - input_correction_bits - kPcanSnrBits
    private static final int PCAN_SNR_SHIFT = PCAN_GAIN_BITS - INPUT_CORRECTION_BITS - K_PCAN_SNR_BITS; // 6

    // float fft -> kissfft int16 magnitude rescale
    // kissfft fixed16 shifts down 1 bit per radix-2 stage so output magnitudes
    // are scaled down by N relative to the mathematical fft (our float fft).
    // squaring carries that to N*N for power values
    private static final float FFT_POWER_SCALE = 1f / ((float) FFT_SIZE * (float) FFT_SIZE);

    // precomputed shared tables
    private static final short[] HANN_Q12; // q12 hann coefficients (per window.c)
    private static final float[] TWIDDLE_RE;
    private static final float[] TWIDDLE_IM;

    // tflm filterbank layout (per filterbank_util.c):
    // num_channels_plus_1 entries; each channel has freq_start, width, weight_start
    // and contributes to TWO adjacent output bands via weights/unweights buffers
    // we keep the same layout so the integer math matches bit for bit
    private static final int FB_CHANNELS_PLUS_1 = N_MELS + 1;
    private static final int[] CHANNEL_FREQ_START = new int[FB_CHANNELS_PLUS_1];
    private static final int[] CHANNEL_WEIGHT_START = new int[FB_CHANNELS_PLUS_1];
    private static final int[] CHANNEL_WIDTH = new int[FB_CHANNELS_PLUS_1];
    private static final short[] FB_WEIGHTS;
    private static final short[] FB_UNWEIGHTS;
    private static final int FB_START_INDEX;
    private static final int FB_END_INDEX;

    // pcan wide-dynamic-function lut (built per pcan_gain_control_util.c)
    private static final short[] PCAN_GAIN_LUT = new short[K_WIDE_DYN_FN_LUT_SIZE];

    static {
        // q12 hann coefficients (window_util.c uses cos with i+0.5 phase offset)
        HANN_Q12 = new short[WINDOW_SIZE];
        double arg = Math.PI * 2.0 / (double) WINDOW_SIZE;
        for (int i = 0; i < WINDOW_SIZE; i++) {
            double v = 0.5 - 0.5 * Math.cos(arg * (i + 0.5));
            HANN_Q12[i] = (short) Math.floor(v * (1 << K_FRONTEND_WINDOW_BITS) + 0.5);
        }

        TWIDDLE_RE = new float[FFT_SIZE / 2];
        TWIDDLE_IM = new float[FFT_SIZE / 2];
        for (int k = 0; k < FFT_SIZE / 2; k++) {
            double a = -2.0 * Math.PI * k / FFT_SIZE;
            TWIDDLE_RE[k] = (float) Math.cos(a);
            TWIDDLE_IM[k] = (float) Math.sin(a);
        }

        // filterbank setup, mirroring filterbank_util.c FilterbankPopulateState
        // num_channels_plus_1 mel center frequencies (the +1 channels "tail" is discarded)
        int spectrumSize = FFT_SIZE / 2 + 1;
        float hzPerSbin = 0.5f * SAMPLE_RATE / (float) (spectrumSize - 1);
        float melLow = freqToMel(FB_LOWER_HZ);
        float[] centerMelFreqs = new float[FB_CHANNELS_PLUS_1];
        float melSpan = freqToMel(FB_UPPER_HZ) - melLow;
        float melSpacing = melSpan / (float) FB_CHANNELS_PLUS_1;
        for (int i = 0; i < FB_CHANNELS_PLUS_1; i++) centerMelFreqs[i] = melLow + melSpacing * (i + 1);

        // start_index: first fft bin >= lower band limit, +1.5 ceiling
        int startIndex = (int) (1.5f + FB_LOWER_HZ / hzPerSbin);
        FB_START_INDEX = startIndex;

        int[] actualStarts = new int[FB_CHANNELS_PLUS_1];
        int[] actualWidths = new int[FB_CHANNELS_PLUS_1];

        // first pass: layout channel widths and weight starts
        // simplified compared to c: we dont pad to alignment blocks... we dont need
        // simd-friendly alignment in the java port and keeping the natural layout
        // makes the integer math in the second pass match exactly
        int chanFreqIndexStart = startIndex;
        int weightIndexStart = 0;
        int endIndex = 0;
        for (int chan = 0; chan < FB_CHANNELS_PLUS_1; chan++) {
            int freqIndex = chanFreqIndexStart;
            while (freqToMel(freqIndex * hzPerSbin) <= centerMelFreqs[chan]) freqIndex++;
            int width = freqIndex - chanFreqIndexStart;

            actualStarts[chan] = chanFreqIndexStart;
            actualWidths[chan] = width;

            CHANNEL_FREQ_START[chan]   = chanFreqIndexStart;
            CHANNEL_WEIGHT_START[chan] = weightIndexStart;
            CHANNEL_WIDTH[chan]        = width;

            weightIndexStart += width;
            if (freqIndex > endIndex) endIndex = freqIndex;
            chanFreqIndexStart = freqIndex;
        }
        FB_END_INDEX = endIndex;

        FB_WEIGHTS   = new short[Math.max(1, weightIndexStart)];
        FB_UNWEIGHTS = new short[Math.max(1, weightIndexStart)];

        // second pass: per-bin Q12 weights matching QuantizeFilterbankWeights
        for (int chan = 0; chan < FB_CHANNELS_PLUS_1; chan++) {
            int frequency = actualStarts[chan];
            int numFreqs = actualWidths[chan];
            int weightStart = CHANNEL_WEIGHT_START[chan];
            float denomVal = (chan == 0) ? melLow : centerMelFreqs[chan - 1];
            float center = centerMelFreqs[chan];
            for (int j = 0; j < numFreqs; j++, frequency++) {
                float w = (center - freqToMel(frequency * hzPerSbin)) / (center - denomVal);
                int qw  = (int) Math.floor(w * (1 << K_FILTERBANK_BITS) + 0.5f);
                int quw = (int) Math.floor((1f - w) * (1 << K_FILTERBANK_BITS) + 0.5f);
                FB_WEIGHTS  [weightStart + j] = (short) qw;
                FB_UNWEIGHTS[weightStart + j] = (short) quw;
            }
        }

        // pcan wide-dynamic-function lut (per pcan_gain_control_util.c)
        // single-point lookups for x = 0 and x = 1
        PCAN_GAIN_LUT[0] = pcanGainLookup(PCAN_INPUT_BITS, 0);
        PCAN_GAIN_LUT[1] = pcanGainLookup(PCAN_INPUT_BITS, 1);
        // intervals 2..32: y0, a1, a2 quadratic interpolation coeffs
        for (int interval = 2; interval <= K_WIDE_DYN_FN_BITS; interval++) {
            long x0 = 1L << (interval - 1);
            long x1 = x0 + (x0 >> 1);
            long x2 = (interval == K_WIDE_DYN_FN_BITS) ? (x0 + (x0 - 1)) : (2 * x0);
            short y0 = pcanGainLookup(PCAN_INPUT_BITS, x0);
            short y1 = pcanGainLookup(PCAN_INPUT_BITS, x1);
            short y2 = pcanGainLookup(PCAN_INPUT_BITS, x2);
            int diff1 = (int) y1 - (int) y0;
            int diff2 = (int) y2 - (int) y0;
            int a1 = 4 * diff1 - diff2;
            int a2 = diff2 - a1;
            // c code does lut -= 6 then writes lut[4*interval + {0,1,2}]
            // net effect: writes at lut[4*interval - 6 + {0,1,2}] in the original buffer
            int base = 4 * interval - 6;
            PCAN_GAIN_LUT[base    ] = y0;
            PCAN_GAIN_LUT[base + 1] = (short) a1;
            PCAN_GAIN_LUT[base + 2] = (short) a2;
        }
    }

    // matches PcanGainLookupFunction in pcan_gain_control_util.c
    private static short pcanGainLookup(int inputBits, long x) {
        double xAsFloat = (double) x / (double) (1L << inputBits);
        double gain = (double) (1L << PCAN_GAIN_BITS) * Math.pow(xAsFloat + PCAN_OFFSET, -PCAN_STRENGTH);
        if (gain > 0x7FFF) return (short) 0x7FFF;
        return (short) (gain + 0.5);
    }

    // per-instance state
    private final short[] inputBuf = new short[WINDOW_SIZE]; // matches WindowState->input
    private int inputUsed = 0;
    private final short[] windowOut = new short[WINDOW_SIZE]; // matches WindowState->output (post-window int16)
    private final long[] fbWork = new long[FB_CHANNELS_PLUS_1]; // matches FilterbankState->work (uint64)
    private final long[] noiseEstimate = new long[N_MELS]; // matches NoiseReductionState->estimate (uint32)

    // float fft work buffers
    private final float[] fftRe = new float[FFT_SIZE];
    private final float[] fftIm = new float[FFT_SIZE];
    private final long[] energy = new long[FFT_SIZE / 2 + 1]; // |fft|^2 in kissfft scale
    private final long[] signal = new long[N_MELS]; // sqrt + noise reduction + pcan input/output
    private final float[] mel = new float[N_MELS]; // final float in [0, 26]

    public interface FrameCallback {
        void onFrame(float[] mel);
    }

    public void feed(byte[] pcm, int length, FrameCallback cb) {
        // matches WindowProcessSamples: append pcm to inputBuf, when full -> apply window, fire frame, slide by HOP
        int sampleCount = length / 2;
        int srcPos = 0;
        while (srcPos < sampleCount) {
            int toCopy = Math.min(WINDOW_SIZE - inputUsed, sampleCount - srcPos);
            for (int i = 0; i < toCopy; i++) {
                int b = (srcPos + i) * 2;
                inputBuf[inputUsed + i] = (short) (((pcm[b + 1] & 0xFF) << 8) | (pcm[b] & 0xFF));
            }
            inputUsed += toCopy;
            srcPos += toCopy;
            if (inputUsed < WINDOW_SIZE) return;

            computeFrame();
            cb.onFrame(mel);

            // slide input down by HOP_SAMPLES (matches memmove in window.c)
            System.arraycopy(inputBuf, HOP_SAMPLES, inputBuf, 0, WINDOW_SIZE - HOP_SAMPLES);
            inputUsed -= HOP_SAMPLES;
        }
    }

    private void computeFrame() {
        // window stage (matches WindowProcessSamples)
        // out = (sample * coeff) >> kFrontendWindowBits
        for (int i = 0; i < WINDOW_SIZE; i++) {
            int v = (((int) inputBuf[i]) * ((int) HANN_Q12[i])) >> K_FRONTEND_WINDOW_BITS;
            windowOut[i] = (short) v;
        }

        // fft stage
        // we use float fft for performance; kissfft int16 scales output down by N
        // (1 bit per radix-2 stage). we compensate with FFT_POWER_SCALE on the
        // squared magnitudes so the integer pipeline downstream sees values
        // in the same numeric range as the c reference
        for (int i = 0; i < WINDOW_SIZE; i++) { fftRe[i] = (float) windowOut[i]; fftIm[i] = 0f; }
        for (int i = WINDOW_SIZE; i < FFT_SIZE; i++) { fftRe[i] = 0f; fftIm[i] = 0f; }
        fft(fftRe, fftIm);

        // |fft|^2 -> energy (matches FilterbankConvertFftComplexToEnergy)
        int half = FFT_SIZE / 2 + 1;
        for (int k = 0; k < half; k++) {
            float r = fftRe[k], i = fftIm[k];
            float p = (r * r + i * i) * FFT_POWER_SCALE;
            energy[k] = (p < 0f) ? 0L : (long) p;
        }

        // filterbank accumulate (matches FilterbankAccumulateChannels)
        // each channels work = (this channels weighted sum) + (previous channels unweighted sum)
        // achieved by carrying unweight_accumulator forward into the next channels weight_accumulator
        long weightAccum = 0L;
        long unweightAccum = 0L;
        for (int chan = 0; chan < FB_CHANNELS_PLUS_1; chan++) {
            int freqStart = CHANNEL_FREQ_START[chan];
            int wStart    = CHANNEL_WEIGHT_START[chan];
            int width     = CHANNEL_WIDTH[chan];
            for (int j = 0; j < width; j++) {
                long mag = energy[freqStart + j];
                weightAccum   += ((long) FB_WEIGHTS  [wStart + j]) * mag;
                unweightAccum += ((long) FB_UNWEIGHTS[wStart + j]) * mag;
            }
            fbWork[chan] = weightAccum;
            weightAccum = unweightAccum;
            unweightAccum = 0L;
        }

        // sqrt + scale_down (matches FilterbankSqrt)
        // FilterbankSqrt output starts at work[1] (work[0] is the discarded sub-band)
        // scale_down_shift = input_shift, which we set to 0 (float fft has full precision)
        for (int m = 0; m < N_MELS; m++) {
            long w = fbWork[m + 1];
            signal[m] = isqrt(w) >>> 0; // explicit shift kept for clarity / future input_shift
        }

        // noise reduction (matches NoiseReductionApply)
        // estimate is stored in scaled-up domain (signal << smoothing_bits)
        // per channel: signal_scaled_up = signal << smoothing_bits
        //              estimate = (signal_scaled_up * smoothing + estimate * (1<<14 - smoothing)) >> 14
        //              estimate_clamped = min(estimate, signal_scaled_up)
        //              floor = (signal * min_signal_remaining) >> 14
        //              subtracted = (signal_scaled_up - estimate_clamped) >> smoothing_bits
        //              signal = max(subtracted, floor)
        for (int m = 0; m < N_MELS; m++) {
            int smooth      = ((m & 1) == 0) ? NR_EVEN_SMOOTH_Q14 : NR_ODD_SMOOTH_Q14;
            int oneMinus    = (1 << K_NOISE_REDUCTION_BITS) - smooth;
            long sig        = signal[m];
            long sigScaled  = sig << NR_SMOOTHING_BITS;
            long est        = (sigScaled * smooth + noiseEstimate[m] * oneMinus) >>> K_NOISE_REDUCTION_BITS;
            noiseEstimate[m] = est;
            long estClamped = (est > sigScaled) ? sigScaled : est;
            long floorVal   = (sig * NR_MIN_SIGNAL_Q14) >>> K_NOISE_REDUCTION_BITS;
            long subtracted = (sigScaled - estClamped) >>> NR_SMOOTHING_BITS;
            signal[m] = (subtracted > floorVal) ? subtracted : floorVal;
        }

        // pcan gain control (matches PcanGainControlApply)
        // gain = WideDynamicFunction(noise_estimate[m], gain_lut)
        // snr  = (signal * gain) >> snr_shift
        // signal = PcanShrink(snr)
        for (int m = 0; m < N_MELS; m++) {
            long gain = wideDynamicFunction(noiseEstimate[m]);
            long snr = (signal[m] * gain) >>> PCAN_SNR_SHIFT;
            signal[m] = pcanShrink(snr);
        }

        // log scale (matches LogScaleApply with correction_bits)
        // value <<= correction_bits   (correction_bits = 3 for fft_size=512)
        // if value > 1: value = Log(value, scale_shift)  (natural log scaled by 1<<scale_shift)
        // clamp to uint16 -> divide by OUT_DIVIDE -> float in [0, OUT_MAX]
        for (int m = 0; m < N_MELS; m++) {
            long v = signal[m] << INPUT_CORRECTION_BITS;
            int u16;
            if (v > 1) {
                long scaled = logFn(v);
                u16 = (scaled > 0xFFFFL) ? 0xFFFF : (int) scaled;
            } else {
                u16 = 0;
            }
            mel[m] = Math.min(OUT_MAX, u16 / OUT_DIVIDE);
        }
    }

    // pcan WideDynamicFunction (matches pcan_gain_control.c)
    // quadratic interpolation across decades using msb-based interval selection
    // returns the int16 result implicitly cast to uint32 (matches the C call site:
    // const uint32_t gain = WideDynamicFunction(...) - negative int16 becomes huge uint32)
    private static long wideDynamicFunction(long noiseEstimate) {
        if (noiseEstimate <= 2L) return ((long) PCAN_GAIN_LUT[(int) noiseEstimate]) & 0xFFFFFFFFL;
        int interval;
        if (noiseEstimate > 0xFFFFFFFFL) interval = 32;
        else interval = msb32((int) noiseEstimate);
        int base = 4 * interval - 6;
        int frac = (int) ((interval < 11) ? (noiseEstimate << (11 - interval))
                                          : (noiseEstimate >>> (interval - 11))) & 0x3FF;
        int result = ((int) PCAN_GAIN_LUT[base + 2] * frac) >> 5;
        result += ((int) PCAN_GAIN_LUT[base + 1]) << 5;
        result *= frac;
        result = (result + (1 << 14)) >> 15;
        result += PCAN_GAIN_LUT[base];
        // (int16_t)result truncates to int16, then implicit (uint32_t) zero-extends from int after sign-extension
        return ((long) (short) result) & 0xFFFFFFFFL;
    }

    // pcan PcanShrink (matches pcan_gain_control.c)
    private static long pcanShrink(long x) {
        if (x < (2L << K_PCAN_SNR_BITS)) {
            // (x * x) >> (2 + 2*kPcanSnrBits - kPcanOutputBits) = (x*x) >> 20
            return (x * x) >>> (2 + 2 * K_PCAN_SNR_BITS - K_PCAN_OUTPUT_BITS);
        } else {
            // (x >> (kPcanSnrBits - kPcanOutputBits)) - (1 << kPcanOutputBits) = (x >> 6) - 64
            return (x >>> (K_PCAN_SNR_BITS - K_PCAN_OUTPUT_BITS)) - (1L << K_PCAN_OUTPUT_BITS);
        }
    }

    // log_scale Log function (matches log_scale.c)
    // computes natural log of x scaled by (1 << LOG_SCALE_SHIFT), via integer math
    // approximation matching the reference (msb + fractional log2 + ln(2) coefficient)
    private static long logFn(long x) {
        int integer = msb32((int) (x & 0xFFFFFFFFL)) - 1;
        // for very large values msb32 takes uint32 - if x exceeds uint32 we'd need msb64;
        // values arriving here from pcan_shrink are bounded well within uint32
        long fraction = log2FractionPart(x, integer);
        long log2 = ((long) integer << K_LOG_SCALE_LOG2) + fraction;
        long round = K_LOG_SCALE / 2;
        long loge = ((long) K_LOG_COEFF * log2 + round) >>> K_LOG_SCALE_LOG2;
        long logeScaled = ((loge << LOG_SCALE_SHIFT) + round) >>> K_LOG_SCALE_LOG2;
        return logeScaled;
    }

    // matches Log2FractionPart in log_scale.c
    private static long log2FractionPart(long x, int log2x) {
        long frac = x - (1L << log2x);
        if (log2x < K_LOG_SCALE_LOG2) frac <<= K_LOG_SCALE_LOG2 - log2x;
        else                          frac >>>= log2x - K_LOG_SCALE_LOG2;
        long base = frac >>> (K_LOG_SCALE_LOG2 - K_LOG_SEGMENTS_LOG2);
        long segUnit = ((long) K_LOG_SCALE) >>> K_LOG_SEGMENTS_LOG2;
        long c0 = K_LOG_LUT[(int) base];
        long c1 = K_LOG_LUT[(int) base + 1];
        long segBase = segUnit * base;
        long relPos = ((c1 - c0) * (frac - segBase)) >> K_LOG_SCALE_LOG2;
        return frac + c0 + relPos;
    }

    // verbatim from log_lut.c (kLogLut[]). 130 entries (128 segments + 2 padding for safe c1 lookup)
    private static final int[] K_LOG_LUT = {
        0,    224,  442,  654,  861,  1063, 1259, 1450, 1636, 1817, 1992, 2163,
        2329, 2490, 2646, 2797, 2944, 3087, 3224, 3358, 3487, 3611, 3732, 3848,
        3960, 4068, 4172, 4272, 4368, 4460, 4549, 4633, 4714, 4791, 4864, 4934,
        5001, 5063, 5123, 5178, 5231, 5280, 5326, 5368, 5408, 5444, 5477, 5507,
        5533, 5557, 5578, 5595, 5610, 5622, 5631, 5637, 5640, 5641, 5638, 5633,
        5626, 5615, 5602, 5586, 5568, 5547, 5524, 5498, 5470, 5439, 5406, 5370,
        5332, 5291, 5249, 5203, 5156, 5106, 5054, 5000, 4944, 4885, 4825, 4762,
        4697, 4630, 4561, 4490, 4416, 4341, 4264, 4184, 4103, 4020, 3935, 3848,
        3759, 3668, 3575, 3481, 3384, 3286, 3186, 3084, 2981, 2875, 2768, 2659,
        2549, 2437, 2323, 2207, 2090, 1971, 1851, 1729, 1605, 1480, 1353, 1224,
        1094, 963,  830,  695,  559,  421,  282,  142,  0,    0
    };

    // integer square root for uint32 carried in long (matches Sqrt32 / Sqrt64 in filterbank.c)
    private static long isqrt(long x) {
        if (x <= 0L) return 0L;
        long r = (long) Math.sqrt((double) x);
        while (r > 0 && r * r > x) r--;
        while ((r + 1) * (r + 1) <= x) r++;
        return r;
    }

    // matches MostSignificantBit32 in bits.h: returns 1-based position of highest set bit
    private static int msb32(int n) {
        if (n == 0) return 0;
        return 32 - Integer.numberOfLeadingZeros(n);
    }

    // cooley-tukey radix-2 dit fft with precomputed twiddle table (float, performance)
    private static void fft(float[] re, float[] im) {
        int n = FFT_SIZE;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                      t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
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

    // FreqToMel from filterbank_util.c (uses log1p, mel = 1127 * ln(1 + hz/700))
    private static float freqToMel(float hz) {
        return 1127.0f * (float) Math.log1p(hz / 700.0);
    }
}
