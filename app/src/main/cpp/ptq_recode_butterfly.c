// Isolated opt-in candidate: no dispatch registration or CPU probing here.
// Caller guarantees AVX512F/BW/DQ/VL/VNNI + FMA/F16C and n >= 0, n % 128 == 0.
#include "ptq_recode.h"
#define GGML_COMMON_DECL_C
#include "ggml-common.h"

#if (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
#include <immintrin.h>
#define BUTTERFLY_VNNI __attribute__((target("avx512f,avx512bw,avx512dq,avx512vl,avx512vnni,fma,f16c")))

static_assert(sizeof(BonsaiPtqCache4) == 136, "Unexpected cache layout");

BUTTERFLY_VNNI static inline __m512i butterfly_expand_pair(const uint8_t * pair) {
    const __m512i raw = _mm512_cvtepu8_epi32(_mm_loadu_si128((const __m128i *)pair));
    // First spread the nibbles, then their two-bit pairs; keep original code order.
    const __m512i t = _mm512_and_si512(
        _mm512_or_si512(raw, _mm512_slli_epi32(raw, 12)), _mm512_set1_epi32(0x000f000f));
    return _mm512_and_si512(
        _mm512_or_si512(t, _mm512_slli_epi32(t, 6)), _mm512_set1_epi32(0x03030303));
}

BUTTERFLY_VNNI static inline __m256i butterfly_activation(const block_q8_0 * q) {
    const __m256i bytes = _mm256_loadu_si256((const __m256i *)q->qs);
#if BONSAI_PTQ_PLANAR
    const __m256i chunks = _mm256_permutevar8x32_epi32(bytes, _mm256_setr_epi32(0,2,4,6,1,3,5,7));
    return _mm256_shuffle_epi8(chunks, _mm256_setr_epi8(
        0,4,8,12,1,5,9,13,2,6,10,14,3,7,11,15,
        0,4,8,12,1,5,9,13,2,6,10,14,3,7,11,15));
#else
    return bytes;
#endif
}

BUTTERFLY_VNNI static inline __m128i butterfly_reduce_four(__m512i a, __m512i b) {
    const __m256i h0 = _mm256_hadd_epi32(_mm512_castsi512_si256(a), _mm512_extracti64x4_epi64(a, 1));
    const __m256i h1 = _mm256_hadd_epi32(_mm512_castsi512_si256(b), _mm512_extracti64x4_epi64(b, 1));
    const __m256i hh = _mm256_hadd_epi32(h0, h1);
    return _mm_add_epi32(_mm256_castsi256_si128(hh), _mm256_extracti128_si256(hh, 1));
}

BUTTERFLY_VNNI void bonsai_ptq_butterfly_dot4(int n, float * output, const void * weights, const void * activations) {
    const BonsaiPtqCache4 * x = weights;
    const block_q8_0 * y = activations;
    const int blocks = n / 128;
    const int16_t * activation_sums = (const int16_t *)(y + n / 32);
    __m128 total = _mm_setzero_ps();
    for (int b = 0; b < blocks; ++b) {
        __m128 subtotal = _mm_setzero_ps();
        #pragma GCC unroll 4
        for (int k = 0; k < 4; ++k) {
            const block_q8_0 * q = y + b*4 + k;
            const __m512i activation = _mm512_broadcast_i64x4(butterfly_activation(q));
            const __m512i p01 = _mm512_dpbusd_epi32(_mm512_setzero_si512(),
                butterfly_expand_pair(x[b].qs + k*32), activation);
            const __m512i p23 = _mm512_dpbusd_epi32(_mm512_setzero_si512(),
                butterfly_expand_pair(x[b].qs + k*32 + 16), activation);
            const __m128i integers = _mm_sub_epi32(butterfly_reduce_four(p01, p23),
                _mm_set1_epi32(activation_sums[b*4 + k]));
            const float dy = _mm_cvtss_f32(_mm_cvtph_ps(_mm_cvtsi32_si128(q->d)));
            // Preserve VNNI FP32 scale boundaries, FMA operands and accumulation order.
            subtotal = _mm_fmadd_ps(_mm_cvtepi32_ps(integers), _mm_set1_ps(dy), subtotal);
        }
        const __m128 scales = _mm_cvtph_ps(_mm_loadl_epi64((const __m128i *)x[b].d));
        total = _mm_fmadd_ps(scales, subtotal, total);
    }
    _mm_storeu_ps(output, total);
}
#endif
