// jni wrapper around the tflm microfrontend feature extractor
// parameters mirror the esphome micro_wake_word component so output is bit exact
// java holds one handle per instance and the native side owns the config and state
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <vector>

extern "C" {
#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
#include "tensorflow/lite/experimental/microfrontend/lib/frontend_util.h"
}

#define TAG "MelJni"

namespace {

// mirrors esphome/components/micro_wake_word/preprocessor_settings.h
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

// int8 quantization as done by esphome
constexpr int32_t INT8_VALUE_SCALE = 256;
// 25.6 * 26.0 rounded
constexpr int32_t INT8_VALUE_DIV = 666;

// only ever created fully populated so every live handle has valid state
struct Instance {
    FrontendConfig config;
    FrontendState state;
    // reused across feed calls so steady state feeding does not allocate
    std::vector<int16_t> samples;
    std::vector<jbyte> out_bytes;
    std::vector<jshort> out_shorts;
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

jbyte quantizeInt8(uint16_t value) {
    int32_t v = (static_cast<int32_t>(value) * INT8_VALUE_SCALE + INT8_VALUE_DIV / 2) / INT8_VALUE_DIV;
    v += INT8_MIN;
    if (v < INT8_MIN) v = INT8_MIN;
    else if (v > INT8_MAX) v = INT8_MAX;
    return static_cast<jbyte>(v);
}

jshort passThrough(uint16_t value) {
    return static_cast<jshort>(value);
}

// copies the little endian pcm16 bytes in and runs the frontend over them
// writing one converted row per 10 ms step into out. returns the row count
// or -1 when the java arguments are invalid and an exception is pending
template <typename T, typename Convert>
int processPcm(JNIEnv* env, Instance* inst, jbyteArray pcm, jint pcmByteLen,
               std::vector<T>& out, jint outCapacity, Convert convert) {
    if (pcmByteLen <= 0 || (pcmByteLen & 1) != 0) return 0;
    const jsize sample_count = pcmByteLen / 2;

    std::vector<int16_t>& samples = inst->samples;
    samples.resize(sample_count);
    env->GetByteArrayRegion(pcm, 0, pcmByteLen, reinterpret_cast<jbyte*>(samples.data()));
    // a length past the array end leaves an exception pending and no jni call is legal after it
    if (env->ExceptionCheck()) return -1;

    // compute into a member buffer then copy out once so no jni critical
    // section is held across the dsp loop
    out.resize(outCapacity > 0 ? static_cast<size_t>(outCapacity) : 0);

    int rows_written = 0;
    size_t cursor = 0;
    while (cursor < static_cast<size_t>(sample_count)) {
        size_t consumed = 0;
        FrontendOutput frame = FrontendProcessSamples(
                &inst->state,
                samples.data() + cursor,
                sample_count - cursor,
                &consumed);
        cursor += consumed;
        if (frame.size == 0 || frame.values == nullptr) break;

        if ((rows_written + 1) * static_cast<int>(frame.size) > outCapacity) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "feature out buffer overflow: rows=%d size=%zu cap=%d",
                                rows_written, frame.size, outCapacity);
            break;
        }

        T* row = out.data() + rows_written * PREPROCESSOR_FEATURE_SIZE;
        for (size_t i = 0; i < frame.size; ++i) row[i] = convert(frame.values[i]);
        ++rows_written;
    }
    return rows_written;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_me_rapierxbox_shellyelevatev2_voice_NativeMelExtractor_nativeCreate(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    auto* inst = new Instance();
    initConfig(inst->config);
    if (!FrontendPopulateState(&inst->config, &inst->state, SAMPLE_RATE)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "FrontendPopulateState failed");
        // a partial populate can leave buffers allocated and freeing null is fine
        FrontendFreeStateContents(&inst->state);
        delete inst;
        return 0;
    }
    return reinterpret_cast<jlong>(inst);
}

extern "C" JNIEXPORT void JNICALL
Java_me_rapierxbox_shellyelevatev2_voice_NativeMelExtractor_nativeDestroy(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* inst = reinterpret_cast<Instance*>(handle);
    if (!inst) return;
    FrontendFreeStateContents(&inst->state);
    delete inst;
}

extern "C" JNIEXPORT void JNICALL
Java_me_rapierxbox_shellyelevatev2_voice_NativeMelExtractor_nativeReset(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* inst = reinterpret_cast<Instance*>(handle);
    if (!inst) return;
    FrontendReset(&inst->state);
}

// writes one row of 40 int8 features per 10 ms step matching esphome as
//   int8 = clamp((uint16 * 256 + 333) / 666 - 128 to -128..127)
extern "C" JNIEXPORT jint JNICALL
Java_me_rapierxbox_shellyelevatev2_voice_NativeMelExtractor_nativeFeedInt8(
        JNIEnv* env, jclass /*clazz*/, jlong handle,
        jbyteArray pcm, jint pcmByteLen,
        jbyteArray outInt8Buffer, jint outCapacityBytes) {
    auto* inst = reinterpret_cast<Instance*>(handle);
    if (!inst) return -1;

    int rows = processPcm(env, inst, pcm, pcmByteLen, inst->out_bytes, outCapacityBytes, quantizeInt8);
    if (rows > 0)
        env->SetByteArrayRegion(outInt8Buffer, 0, rows * PREPROCESSOR_FEATURE_SIZE, inst->out_bytes.data());
    return rows;
}

// raw uint16 features before quantization for diagnostics and unused by the detector
extern "C" JNIEXPORT jint JNICALL
Java_me_rapierxbox_shellyelevatev2_voice_NativeMelExtractor_nativeFeedUint16(
        JNIEnv* env, jclass /*clazz*/, jlong handle,
        jbyteArray pcm, jint pcmByteLen,
        jshortArray outU16Buffer, jint outCapacityShorts) {
    auto* inst = reinterpret_cast<Instance*>(handle);
    if (!inst) return -1;

    int rows = processPcm(env, inst, pcm, pcmByteLen, inst->out_shorts, outCapacityShorts, passThrough);
    if (rows > 0)
        env->SetShortArrayRegion(outU16Buffer, 0, rows * PREPROCESSOR_FEATURE_SIZE, inst->out_shorts.data());
    return rows;
}
