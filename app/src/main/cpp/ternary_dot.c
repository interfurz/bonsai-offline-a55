#include "ternary_dot.h"
#define GGML_COMMON_DECL_C
#include "ggml-common.h"

#if defined(__aarch64__) && defined(__ARM_FEATURE_DOTPROD)
#include <arm_neon.h>

static inline float half_float(ggml_half bits) {
    __fp16 value;
    __builtin_memcpy(&value, &bits, sizeof(value));
    return (float) value;
}

// A packed byte holds weights 4*b+j. Deinterleave the activations into
// matching bitplanes instead of replicating each weight byte with two TBLs
// and two variable shifts for every 32 weights. Immediate shifts have no
// per-lane shift vector; independent SDOT chains expose instruction parallelism.
static inline int32x4_t dot64(const uint8_t * packed, const int8_t * activation) {
    const uint8x16_t raw = vld1q_u8(packed);
    const uint8x16_t mask = vdupq_n_u8(3);
    const int8x16_t one = vdupq_n_s8(1);
    const int8x16x4_t y = vld4q_s8(activation);
    const int8x16_t w0 = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(raw, mask)), one);
    const int8x16_t w1 = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(raw, 2), mask)), one);
    const int8x16_t w2 = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(raw, 4), mask)), one);
    const int8x16_t w3 = vsubq_s8(vreinterpretq_s8_u8(vshrq_n_u8(raw, 6)), one);
    const int32x4_t a = vdotq_s32(vdotq_s32(vdupq_n_s32(0), w0, y.val[0]), w1, y.val[1]);
    const int32x4_t b = vdotq_s32(vdotq_s32(vdupq_n_s32(0), w2, y.val[2]), w3, y.val[3]);
    return vaddq_s32(a, b);
}
#endif

bool bonsai_dot_pq2_q8k(int n, float * result, const void * weights, const void * activations) {
#if defined(__aarch64__) && defined(__ARM_FEATURE_DOTPROD)
    if (n < 0 || n % QK_K != 0) return false;
    const block_pq2_0 * x = weights;
    const block_q8_K * y = activations;
    float sum = 0;
    for (int i = 0; i < n / QK_PQ2_0; ++i) {
        const block_q8_K * block = y + i / 2;
        const int8_t * q8 = block->qs + (i % 2) * QK_PQ2_0;
        const int32x4_t a = dot64(x[i].qs, q8);
        const int32x4_t b = dot64(x[i].qs + 16, q8 + 64);
        // Keep one exact integer reduction and the original scale/accumulation
        // order per 128 weights. No different activation quantization or KV type.
        const int32_t dot = vaddvq_s32(vaddq_s32(a, b));
        sum += (half_float(x[i].d) * block->d) * (float) dot;
    }
    *result = sum;
    return true;
#else
    (void) n; (void) result; (void) weights; (void) activations;
    return false;
#endif
}
