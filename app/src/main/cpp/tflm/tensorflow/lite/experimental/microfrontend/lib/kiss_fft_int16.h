// Replacement for TFLM's namespace-wrapped kiss_fft_int16.h. Since this binary
// only needs one kissfft precision, we skip the namespace trick and compile
// kissfft as a plain fixed-point-16 build. The original TFLM microfrontend
// sources reference kissfft_fixed16::kiss_fft_cpx etc — we alias those names
// into that namespace below so fft.cc / fft_util.cc compile unmodified.

#ifndef TENSORFLOW_LITE_EXPERIMENTAL_MICROFRONTEND_LIB_KISS_FFT_INT16_H_
#define TENSORFLOW_LITE_EXPERIMENTAL_MICROFRONTEND_LIB_KISS_FFT_INT16_H_

#include "tensorflow/lite/experimental/microfrontend/lib/kiss_fft_common.h"

#ifndef FIXED_POINT
#define FIXED_POINT 16
#endif

extern "C" {
#include "kiss_fft.h"
#include "tools/kiss_fftr.h"
}

namespace kissfft_fixed16 {
  using ::kiss_fft_cpx;
  using ::kiss_fft_cfg;
  using ::kiss_fft_scalar;
  using ::kiss_fftr_cfg;
  using ::kiss_fftr_alloc;
  using ::kiss_fftr;
}  // namespace kissfft_fixed16

#endif  // TENSORFLOW_LITE_EXPERIMENTAL_MICROFRONTEND_LIB_KISS_FFT_INT16_H_
