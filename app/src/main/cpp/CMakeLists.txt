cmake_minimum_required(VERSION 3.22.1)
project("shellyinput")

# hardware input monitor
add_library(shellyinput SHARED shellyinput.cpp)
target_link_libraries(shellyinput android log)

# native tflm microfrontend
set(TFLM_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/tflm)
set(MFE_DIR ${TFLM_ROOT}/tensorflow/lite/experimental/microfrontend/lib)

add_library(melfrontend SHARED
    mel_jni.cpp

    # tflm microfrontend sources
    ${MFE_DIR}/frontend.c
    ${MFE_DIR}/frontend_util.c
    ${MFE_DIR}/window.c
    ${MFE_DIR}/window_util.c
    ${MFE_DIR}/fft.cc
    ${MFE_DIR}/fft_util.cc
    ${MFE_DIR}/filterbank.c
    ${MFE_DIR}/filterbank_util.c
    ${MFE_DIR}/noise_reduction.c
    ${MFE_DIR}/noise_reduction_util.c
    ${MFE_DIR}/pcan_gain_control.c
    ${MFE_DIR}/pcan_gain_control_util.c
    ${MFE_DIR}/log_scale.c
    ${MFE_DIR}/log_scale_util.c
    ${MFE_DIR}/log_lut.c

    # kissfft (vendored, built as fp16)
    ${MFE_DIR}/kiss_fft.c
    ${MFE_DIR}/tools/kiss_fftr.c
)

# kissfft needs FIXED_POINT=16 defined at compile time so it emits i16-arithmetic butterflies.
# mfe fft.cc picks up the kiss_fftr symbols via the kissfft_fixed16 namespace alias
target_compile_definitions(melfrontend PRIVATE FIXED_POINT=16)

# include root lets source file use absolute tflm paths verbatim
target_include_directories(melfrontend PRIVATE
    ${TFLM_ROOT}
    ${MFE_DIR}
    ${MFE_DIR}/tools
)

# kissfft warns on signed/unsigned conversions... we dont care
target_compile_options(melfrontend PRIVATE
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-sign-compare
)

target_link_libraries(melfrontend android log)
