// jni wrapper exposing the tflm microfrontend feature extractor to java
// matches the parameters used by esphome mww component so should be bit exact

// java side own a single long handle... native side keeps per instance frontendconf frontendstate pairs
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <vector>

extern "C" {
#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
#include "tensorflow/lite/experimental/microfrontend/lib/frontend_util.h"
}

#define TAG "MelJni"

namespace {

// should mirror esphome/components/micro_wake_word/preprocessor_settings.h
constexpr uint8_t PREPROCESSOR_FEATURE_SIZE = 40;
constexpr uint8_t FEATURE_DURATION_MS = 30;
constexpr uint8_t FEATURE_STEP_MS = 10;
constexpr float FILTERBANK_LOWER_BAND_LIMIT = 125.0f;
constexpr float FILTERBANK_UPPER_BAND_LIMIT = 7500.0f;
constexpr uint8_t NOISE_REDUCTION_SMOOTHING_BITS = 10;
constexpr float NOISE_REDUCTION_EVEN_SMOOTHING = 0.025f;
constexpr float NOISE_REDUCTION_ODD_SMOOTHING = 0.06f;
constexpr float NOISE_REDUCTION_MIN_SIGNAL_REMAINING = 0.05f;
constexpr bool PCAN_GAIN_CONTROL_ENABLE_PCAN = true;
constexpr float PCAN_GAIN_CONTROL_STRENGTH = 0.95f;
constexpr float PCAN_GAIN_CONTROL_OFFSET = 80.0f;
constexpr uint8_t PCAN_GAIN_CONTROL_GAIN_BITS = 21;
constexpr bool LOG_SCALE_ENABLE_LOG = true;
constexpr uint8_t LOG_SCALE_SCALE_SHIFT = 6;
constexpr int SAMPLE_RATE = 16000;

struct Instance {
    FrontendConfig config;
    FrontendState state;
    bool state_initialised = false;
};

void initConfig(FrontendConfig& cfg) {
    cfg.window.size_ms = FEATURE_DURATION_MS;
    cfg.window.step_size_ms = FEATURE_STEP_MS;
    cfg.filterbank.num_channels = PREPROCESSOR_FEATURE_SIZE;
    cfg.filterbank.lower_band_limit = FILTERBANK_LOWER_BAND_LIMIT;
    cfg.filterbank.upper_band_limit = FILTERBANK_UPPER_BAND_LIMIT;
    cfg.noise_reduction.smoothing_bits = NOISE_REDUCTION_SMOOTHING_BITS;
    cfg.noise_reduction.even_smoothing = NOISE_REDUCTION_EVEN_SMOOTHING;
    cfg.noise_reduction.odd_smoothing = NOISE_REDUCTION_ODD_SMOOTHING;
    cfg.noise_reduction.min_signal_remaining = NOISE_REDUCTION_MIN_SIGNAL_REMAINING;
    cfg.pcan_gain_control.enable_pcan = PCAN_GAIN_CONTROL_ENABLE_PCAN;
    cfg.pcan_gain_control.strength = PCAN_GAIN_CONTROL_STRENGTH;
    cfg.pcan_gain_control.offset = PCAN_GAIN_CONTROL_OFFSET;
    cfg.pcan_gain_control.gain_bits = PCAN_GAIN_CONTROL_GAIN_BITS;
    cfg.log_scale.enable_log = LOG_SCALE_ENABLE_LOG;
    cfg.log_scale.scale_shift = LOG_SCALE_SCALE_SHIFT;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_me_rapierxbox_shellyelevatev2_voice_NativeMelExtractor_nativeCreate(
        JNIEnv* env, jclass /*clazz*/) {
    auto* inst = new Instance();
    initConfig(inst->config);
    if (!FrontendPopulateState(&inst->config, &inst->state, SAMPLE_RATE)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "FrontendPopulateState failed");
        delete inst;
        return 0;
    }
    inst->state_initialised = true;
    return reinterpret_cast<jlong>(inst);
}

extern "C" JNIEXPORT void JNICALL
Java_me_rapierxbox_shellyelevatev2_voice_NativeMelExtractor_nativeDestroy(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* inst = reinterpret_cast<Instance*>(handle);
    if (!inst) return;
    if (inst->state_initialised) FrontendFreeStateContents(&inst->state);
    delete inst;
}

extern "C" JNIEXPORT void JNICALL
Java_me_rapierxbox_shellyelevatev2_voice_NativeMelExtractor_nativeReset(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* inst = reinterpret_cast<Instance*>(handle);
    if (!inst || !inst->state_initialised) return;
    FrontendReset(&inst->state);
}

// feeds pcm16 little endian bytes to frontend
// for each 10ms step of samples available, push one row of 40 int8 quant features into outInt9Buffer
// int8 = clamp((uint16_value * 256 + 333) / 666 - 128, -128, 127) (matches esphome)
extern "C" JNIEXPORT jint JNICALL
Java_me_rapierxbox_shellyelevatev2_voice_NativeMelExtractor_nativeFeedInt8(
        JNIEnv* env, jclass /*clazz*/, jlong handle,
        jbyteArray pcm, jint pcmByteLen,
        jbyteArray outInt8Buffer, jint outCapacityBytes) {
    auto* inst = reinterpret_cast<Instance*>(handle);
    if (!inst || !inst->state_initialised) return -1;

    if (pcmByteLen <= 0 || (pcmByteLen & 1) != 0) return 0;
    const jsize sample_count = pcmByteLen / 2;

    // copy pcm bytes into transient int16 buf. getbytearrayelements could avoid a copy for big arrays but getprimitivearraycritical with stack/heap int16 is simpler beacuse we need to reinterpret ayways
    std::vector<int16_t> samples(sample_count);
    env->GetByteArrayRegion(pcm, 0, pcmByteLen,
                            reinterpret_cast<jbyte*>(samples.data()));

    // work directly on locked cricital pointer into the output byte array. the region writes happen in bulk at the end so we can release the ptr without holding it acress the jni bound
    jbyte* out_ptr = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(outInt8Buffer, nullptr));
    if (!out_ptr) return -1;

    int rows_written = 0;
    size_t cursor = 0;
    while (cursor < static_cast<size_t>(sample_count)) {
        size_t consumed = 0;
        FrontendOutput out = FrontendProcessSamples(
                &inst->state,
                samples.data() + cursor,
                sample_count - cursor,
                &consumed);
        cursor += consumed;
        if (out.size == 0 || out.values == nullptr) break;

        if ((rows_written + 1) * static_cast<int>(out.size) > outCapacityBytes) {
            // out buffer full. caller should habe sized it correctly
            __android_log_print(ANDROID_LOG_WARN, TAG, "feature out buffer overflow: rows=%d size=%zu cap=%d",
                                rows_written, out.size, outCapacityBytes);
            break;
        }

        const int32_t value_scale = 256;
        const int32_t value_div = 666;  // 25.6 * 26.0 rounded
        for (size_t i = 0; i < out.size; ++i) {
            int32_t v = (static_cast<int32_t>(out.values[i]) * value_scale + value_div / 2) / value_div;
            v += INT8_MIN;
            if (v < INT8_MIN) v = INT8_MIN;
            else if (v > INT8_MAX) v = INT8_MAX;
            out_ptr[rows_written * PREPROCESSOR_FEATURE_SIZE + i] = static_cast<jbyte>(v);
        }
        ++rows_written;
    }

    env->ReleasePrimitiveArrayCritical(outInt8Buffer, out_ptr, 0);
    return rows_written;
}

// variant that writes raw uint16 features into a short[] output for callers that need pre quant values (not used but for diag im,portant)
extern "C" JNIEXPORT jint JNICALL
Java_me_rapierxbox_shellyelevatev2_voice_NativeMelExtractor_nativeFeedUint16(
        JNIEnv* env, jclass /*clazz*/, jlong handle,
        jbyteArray pcm, jint pcmByteLen,
        jshortArray outU16Buffer, jint outCapacityShorts) {
    auto* inst = reinterpret_cast<Instance*>(handle);
    if (!inst || !inst->state_initialised) return -1;

    if (pcmByteLen <= 0 || (pcmByteLen & 1) != 0) return 0;
    const jsize sample_count = pcmByteLen / 2;

    std::vector<int16_t> samples(sample_count);
    env->GetByteArrayRegion(pcm, 0, pcmByteLen,
                            reinterpret_cast<jbyte*>(samples.data()));

    jshort* out_ptr = static_cast<jshort*>(env->GetPrimitiveArrayCritical(outU16Buffer, nullptr));
    if (!out_ptr) return -1;

    int rows_written = 0;
    size_t cursor = 0;
    while (cursor < static_cast<size_t>(sample_count)) {
        size_t consumed = 0;
        FrontendOutput out = FrontendProcessSamples(
                &inst->state,
                samples.data() + cursor,
                sample_count - cursor,
                &consumed);
        cursor += consumed;
        if (out.size == 0 || out.values == nullptr) break;
        if ((rows_written + 1) * static_cast<int>(out.size) > outCapacityShorts) break;
        for (size_t i = 0; i < out.size; ++i)
            out_ptr[rows_written * PREPROCESSOR_FEATURE_SIZE + i] = static_cast<jshort>(out.values[i]);
        ++rows_written;
    }

    env->ReleasePrimitiveArrayCritical(outU16Buffer, out_ptr, 0);
    return rows_written;
}
