#include "ptq_recode.h"
#define GGML_COMMON_DECL_C
#include "ggml-common.h"
#include <immintrin.h>

#define DOT8_VNNI __attribute__((target("avx512f,avx512bw,avx512dq,avx512vl,avx512vnni,fma,f16c")))

// Original non-butterfly VNNI expansion and reduction from ptq_recode_kernels.c.
DOT8_VNNI static inline __m512i expand_pair(const uint8_t * pair) {
    const __m512i raw = _mm512_cvtepu8_epi32(_mm_loadu_si128((const __m128i *)pair));
    const __m512i spread = _mm512_or_si512(_mm512_or_si512(raw,_mm512_slli_epi32(raw,6)),
                                          _mm512_or_si512(_mm512_slli_epi32(raw,12),_mm512_slli_epi32(raw,18)));
    return _mm512_and_si512(spread,_mm512_set1_epi8(3));
}

DOT8_VNNI static inline __m128i reduce_four(__m512i a, __m512i b) {
    const __m256i h0 = _mm256_hadd_epi32(_mm512_castsi512_si256(a),_mm512_extracti64x4_epi64(a,1));
    const __m256i h1 = _mm256_hadd_epi32(_mm512_castsi512_si256(b),_mm512_extracti64x4_epi64(b,1));
    const __m256i hh = _mm256_hadd_epi32(h0,h1);
    return _mm_add_epi32(_mm256_castsi256_si128(hh),_mm256_extracti128_si256(hh,1));
}

DOT8_VNNI static inline __m256i vnni_activation(const block_q8_0 * q) {
    const __m256i bytes = _mm256_loadu_si256((const __m256i *)q->qs);
#if BONSAI_PTQ_PLANAR
    const __m256i chunks = _mm256_permutevar8x32_epi32(bytes,_mm256_setr_epi32(0,2,4,6,1,3,5,7));
    return _mm256_shuffle_epi8(chunks,_mm256_setr_epi8(
        0,4,8,12,1,5,9,13,2,6,10,14,3,7,11,15,
        0,4,8,12,1,5,9,13,2,6,10,14,3,7,11,15));
#else
    return bytes;
#endif
}

// Caller gates CPU features and guarantees n >= 0, n % 128 == 0.
// weights: Cache4[n/128], then Cache4[n/128] (not block-interleaved).
// activations: unchanged Q8_0[n/32], immediately followed by int16 sums[n/32].
// Writes exactly eight floats, including eight +0 outputs for n == 0.
DOT8_VNNI void bonsai_ptq_recode_dot8(int n, float * out, const void * weights, const void * activations) {
    const BonsaiPtqCache4 * x = weights;
    const block_q8_0 * y = activations;
    const int blocks = n/128;
    __m128 total0 = _mm_setzero_ps(), total1 = _mm_setzero_ps();
    // n == 0 needs no readable input, nor even pointer arithmetic on null.
    if (blocks) {
        const int16_t * activation_sums = (const int16_t *)(y+n/32);
        for (int b=0;b<blocks;++b) {
            __m128 subtotal0 = _mm_setzero_ps(), subtotal1 = _mm_setzero_ps();
            #pragma GCC unroll 4
            for (int k=0;k<4;++k) {
                const block_q8_0 * q = y+b*4+k;
                // One load/reorder/broadcast, half conversion and correction per k,
                // shared by both groups without changing either FMA chain.
                const __m512i activation = _mm512_broadcast_i64x4(vnni_activation(q));
                const __m128 dy = _mm_set1_ps(_mm_cvtss_f32(_mm_cvtph_ps(_mm_cvtsi32_si128(q->d))));
                const __m128i correction = _mm_set1_epi32(activation_sums[b*4+k]);
                const __m512i p01 = _mm512_dpbusd_epi32(_mm512_setzero_si512(),expand_pair(x[b].qs+k*32),activation);
                const __m512i p23 = _mm512_dpbusd_epi32(_mm512_setzero_si512(),expand_pair(x[b].qs+k*32+16),activation);
                const __m512i p45 = _mm512_dpbusd_epi32(_mm512_setzero_si512(),expand_pair(x[blocks+b].qs+k*32),activation);
                const __m512i p67 = _mm512_dpbusd_epi32(_mm512_setzero_si512(),expand_pair(x[blocks+b].qs+k*32+16),activation);
                const __m128i integers0 = _mm_sub_epi32(reduce_four(p01,p23),correction);
                const __m128i integers1 = _mm_sub_epi32(reduce_four(p45,p67),correction);
                subtotal0 = _mm_fmadd_ps(_mm_cvtepi32_ps(integers0),dy,subtotal0);
                subtotal1 = _mm_fmadd_ps(_mm_cvtepi32_ps(integers1),dy,subtotal1);
            }
            const __m128 scales0 = _mm_cvtph_ps(_mm_loadl_epi64((const __m128i *)x[b].d));
            const __m128 scales1 = _mm_cvtph_ps(_mm_loadl_epi64((const __m128i *)x[blocks+b].d));
            total0 = _mm_fmadd_ps(scales0,subtotal0,total0);
            total1 = _mm_fmadd_ps(scales1,subtotal1,total1);
        }
    }
    _mm_storeu_ps(out,total0);
    _mm_storeu_ps(out+4,total1);
}
