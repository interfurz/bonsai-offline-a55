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
static inline int8x16_t trits16(uint8x16_t packed) {
    // floor(3*x/256)-1, exactly for every byte, including noncanonical codes.
    const int8x16_t ge1 = vreinterpretq_s8_u8(vcgtq_u8(packed, vdupq_n_u8(85)));
    const int8x16_t ge2 = vreinterpretq_s8_u8(vcgtq_u8(packed, vdupq_n_u8(170)));
    return vsubq_s8(vdupq_n_s8(-1), vaddq_s8(ge1, ge2));
}
static inline int8x8_t trits8(uint8x8_t packed) {
    const int8x8_t ge1 = vreinterpret_s8_u8(vcgt_u8(packed, vdup_n_u8(85)));
    const int8x8_t ge2 = vreinterpret_s8_u8(vcgt_u8(packed, vdup_n_u8(170)));
    return vsub_s8(vdup_n_s8(-1), vadd_s8(ge1, ge2));
}
static float dot_arm(int n, const block_ptq1_0 * x, const block_q8_0 * y) {
    float32x4_t total = vdupq_n_f32(0);
    for (int i = 0; i < n / QK_PTQ1_0; ++i) {
        int32x4_t sums[4] = {vdupq_n_s32(0), vdupq_n_s32(0), vdupq_n_s32(0), vdupq_n_s32(0)};
        uint8x16_t main = vld1q_u8(x[i].qs);
        uint8x8_t tail = vld1_u8(x[i].qs + 16);
        // Stream trits into SDOT rather than materializing eight decoded vectors
        // before using them. One power-of-three recurrence replaces four constants.
        #pragma GCC unroll 5
        for (int j = 0; j < 5; ++j) {
            sums[j / 2] = vdotq_s32(sums[j / 2], trits16(main),
                vld1q_s8(y[4*i + j / 2].qs + (j % 2) * 16));
            const int offset = 80 + j * 8;
            const int8x8_t w8 = trits8(tail);
            const int8x8_t y8 = vld1_s8(y[4*i + offset / 32].qs + offset % 32);
            // Keep the original integer lane assignment before scaled FMA.
            const int8x16_t w16 = offset % 16 ? vcombine_s8(vdup_n_s8(0), w8) : vcombine_s8(w8, vdup_n_s8(0));
            const int8x16_t y16 = offset % 16 ? vcombine_s8(vdup_n_s8(0), y8) : vcombine_s8(y8, vdup_n_s8(0));
            sums[offset / 32] = vdotq_s32(sums[offset / 32], w16, y16);
            main = vmulq_u8(main, vdupq_n_u8(3));
            tail = vmul_u8(tail, vdup_n_u8(3));
        }
        uint16_t bits;
        __builtin_memcpy(&bits, x[i].qh, sizeof(bits));
        static const uint8_t powers[8] = {1, 1, 3, 3, 9, 9, 27, 27};
        const uint8x8_t h = vmul_u8(vreinterpret_u8_u16(vdup_n_u16(bits)), vld1_u8(powers));
        sums[3] = vdotq_s32(sums[3], vcombine_s8(vdup_n_s8(0), trits8(h)),
            vcombine_s8(vdup_n_s8(0), vld1_s8(y[4*i + 3].qs + 24)));
        const float dx = half_float(x[i].d);
        #pragma GCC unroll 4
        for (int k = 0; k < 4; ++k)
            total = vfmaq_n_f32(total, vcvtq_f32_s32(sums[k]), dx * half_float(y[4*i + k].d));
    }
    return vaddvq_f32(total);
}
#endif

#if (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
#include <immintrin.h>
#define PTQ_AVX2 __attribute__((target("avx2,fma,f16c")))

PTQ_AVX2 static inline float half_x86(ggml_half bits) {
    return _mm_cvtss_f32(_mm_cvtph_ps(_mm_cvtsi32_si128(bits)));
}
PTQ_AVX2 static inline __m256i codes32(__m256i packed) {
    const __m256i biased = _mm256_xor_si256(packed, _mm256_set1_epi8(-128));
    const __m256i a = _mm256_cmpgt_epi8(biased, _mm256_set1_epi8(85 - 128));
    const __m256i b = _mm256_cmpgt_epi8(biased, _mm256_set1_epi8(170 - 128));
    return _mm256_sub_epi8(_mm256_setzero_si256(), _mm256_add_epi8(a, b));
}
PTQ_AVX2 static inline int sum4(__m128i value) {
    value = _mm_hadd_epi32(value, value);
    return _mm_cvtsi128_si32(_mm_hadd_epi32(value, value));
}
PTQ_AVX2 static float dot_x86(int n, const block_ptq1_0 * x, const block_q8_0 * y) {
    const __m256i ones16 = _mm256_set1_epi16(1), ones8 = _mm256_set1_epi8(1);
    float total = 0;
    for (int i = 0; i < n / QK_PTQ1_0; ++i) {
        const block_q8_0 * block = y + i * 4;
        __m256i packed = _mm256_set_m128i(_mm_loadl_epi64((const __m128i *)(x[i].qs + 16)),
                                         _mm_loadu_si128((const __m128i *)x[i].qs));
        __m128i sums[4] = {_mm_setzero_si128(), _mm_setzero_si128(), _mm_setzero_si128(), _mm_setzero_si128()};
        // Constant stage indices keep accumulators in registers, not a stack
        // array indexed by the trit loop. Preserve the subsequent FP order.
        #pragma GCC unroll 5
        for (int j = 0; j < 5; ++j) {
            const int offset = 80 + j * 8;
            const __m256i q = _mm256_set_m128i(
                _mm_loadl_epi64((const __m128i *)(block[offset / 32].qs + offset % 32)),
                _mm_loadu_si128((const __m128i *)(block[j / 2].qs + (j % 2) * 16)));
            const __m256i products = _mm256_madd_epi16(_mm256_maddubs_epi16(codes32(packed), q), ones16);
            sums[j / 2] = _mm_add_epi32(sums[j / 2], _mm256_castsi256_si128(products));
            sums[offset / 32] = _mm_add_epi32(sums[offset / 32], _mm256_extracti128_si256(products, 1));
            packed = _mm256_add_epi8(packed, _mm256_add_epi8(packed, packed));
        }
        uint16_t h;
        __builtin_memcpy(&h, x[i].qh, sizeof(h));
        const __m128i bytes = _mm_shuffle_epi8(_mm_set1_epi16(h), _mm_setr_epi8(0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,1));
        __m128i qh = _mm_mullo_epi16(_mm_cvtepu8_epi16(bytes), _mm_setr_epi16(1,1,3,3,9,9,27,27));
        qh = _mm_and_si128(qh, _mm_set1_epi16(255));
        qh = _mm_srli_epi16(_mm_add_epi16(qh, _mm_add_epi16(qh, qh)), 8);
        qh = _mm_packus_epi16(qh, _mm_setzero_si128());
        const __m128i tail = _mm_loadl_epi64((const __m128i *)(block[3].qs + 24));
        const __m128i products = _mm_madd_epi16(_mm_maddubs_epi16(qh, tail), _mm_set1_epi16(1));
        sums[3] = _mm_add_epi32(sums[3], products);
        // Codes are w+1. Correct once for each whole activation block, not once
        // for every decoded trit group; -128 activations remain representable.
        float subtotal = 0;
        #pragma GCC unroll 4
        for (int k = 0; k < 4; ++k) {
            const __m256i q = _mm256_loadu_si256((const __m256i *)block[k].qs);
            const __m256i correction = _mm256_madd_epi16(_mm256_maddubs_epi16(ones8, q), ones16);
            const __m128i c = _mm_add_epi32(_mm256_castsi256_si128(correction), _mm256_extracti128_si256(correction, 1));
            const int dot = sum4(_mm_sub_epi32(sums[k], c));
            subtotal += half_x86(block[k].d) * dot;
        }
        total += half_x86(x[i].d) * subtotal;
    }
    return total;
}
#endif

bool bonsai_dot_ptq1_q80(int n, float * result, const void * weights, const void * activations) {
    if (n < 0 || n % QK_PTQ1_0 != 0) return false;
#if defined(__aarch64__) && defined(__ARM_FEATURE_DOTPROD)
    *result = dot_arm(n, weights, activations);
    return true;
#elif (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
    if (__builtin_cpu_supports("avx2") && __builtin_cpu_supports("fma") && __builtin_cpu_supports("f16c")) {
        *result = dot_x86(n, weights, activations);
        return true;
    }
    return false;
#else
    (void) result; (void) weights; (void) activations;
    return false;
#endif
}
