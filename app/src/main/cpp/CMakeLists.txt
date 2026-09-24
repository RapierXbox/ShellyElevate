cmake_minimum_required(VERSION 3.22.1)
project("shellyinput")

# hardware input monitor
add_library(shellyinput SHARED shellyinput.cpp)
target_link_libraries(shellyinput android log)

# wake word mel frontend on top of the vendored tflm microfrontend
set(TFLM_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/tflm)
set(MFE_DIR ${TFLM_ROOT}/tensorflow/lite/experimental/microfrontend/lib)

add_library(melfrontend SHARED
    mel_jni.cpp

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

    # vendored kissfft
    ${MFE_DIR}/kiss_fft.c
    ${MFE_DIR}/tools/kiss_fftr.c
)

# kissfft must be built with 16 bit fixed point butterflies and fft.cc
# reaches the kiss_fftr symbols through the kissfft_fixed16 namespace alias
target_compile_definitions(melfrontend PRIVATE FIXED_POINT=16)

# the tflm root lets sources use their upstream include paths verbatim
target_include_directories(melfrontend PRIVATE
    ${TFLM_ROOT}
    ${MFE_DIR}
    ${MFE_DIR}/tools
)

# the vendored c sources trip these harmless warnings
target_compile_options(melfrontend PRIVATE
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-sign-compare
)

target_link_libraries(melfrontend android log)
