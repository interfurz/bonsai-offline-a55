#include "ptq_recode.h"
#define GGML_COMMON_DECL_C
#include "ggml-common.h"
#ifndef BONSAI_RECODE_AVX2_ONLY
#define BONSAI_RECODE_AVX2_ONLY 0
#endif
#ifndef BONSAI_RECODE_VNNI_ONLY
#define BONSAI_RECODE_VNNI_ONLY 0
#endif
#ifndef BONSAI_RECODE_VBMI
#define BONSAI_RECODE_VBMI 0
#endif
#ifndef BONSAI_RECODE_BUTTERFLY
#define BONSAI_RECODE_BUTTERFLY 0
#endif
#if BONSAI_RECODE_BUTTERFLY
extern void bonsai_ptq_butterfly_dot4(int n, float * output, const void * weights, const void * activations);
#endif
static_assert(sizeof(BonsaiPtqCache4) == 4*sizeof(block_pq2_0), "Unexpected expanded cache padding");

void bonsai_ptq_recode_blocks(const void * source, void * destination, size_t blocks) {
    const block_ptq1_0 * input = source;
    block_pq2_0 * output = destination;
    static const unsigned powers[5] = {1, 3, 9, 27, 81};
    for (size_t block = 0; block < blocks; ++block) {
        uint8_t codes[128];
        int position = 0;
        for (int stage = 0; stage < 2; ++stage) {
            const int start = stage ? 16 : 0, width = stage ? 8 : 16;
            for (int trit = 0; trit < 5; ++trit)
                for (int j = 0; j < width; ++j) {
                    const uint8_t byte = (uint8_t)(input[block].qs[start + j] * powers[trit]);
                    codes[position++] = (uint8_t)(((unsigned)byte * 3) >> 8);
                }
        }
        for (int trit = 0; trit < 4; ++trit)
            for (int j = 0; j < 2; ++j) {
                const uint8_t byte = (uint8_t)(input[block].qh[j] * powers[trit]);
                codes[position++] = (uint8_t)(((unsigned)byte * 3) >> 8);
            }
        output[block].d = input[block].d;
        for (int j = 0; j < 32; ++j)
            output[block].qs[j] = codes[4*j] | (codes[4*j+1] << 2) | (codes[4*j+2] << 4) | (codes[4*j+3] << 6);
    }
}

void bonsai_ptq_recode_upload(const void * source, void * destination, size_t first_block, size_t blocks, size_t row_blocks) {
    const block_ptq1_0 * input = source;
    BonsaiPtqCache4 * output = destination;
    for (size_t i=0;i<blocks;++i) {
        block_pq2_0 converted;
        bonsai_ptq_recode_blocks(input+i,&converted,1);
        const size_t row = (first_block+i)/row_blocks;
        const size_t block = (first_block+i)%row_blocks;
        BonsaiPtqCache4 * target = output+(row/4)*row_blocks+block;
        target->d[row%4] = converted.d;
        #if BONSAI_PTQ_PLANAR
        for (int k=0;k<4;++k) for (int j=0;j<8;++j) {
            unsigned byte=0;
            for (int plane=0;plane<4;++plane) {
                const int position=j+8*plane;
                const unsigned code=(converted.qs[k*8+position/4]>>(2*(position%4)))&3;
                byte|=code<<(2*plane);
            }
            target->qs[k*32+(row%4)*8+j]=(uint8_t)byte;
        }
        #else
        for (int k=0;k<4;++k)
            __builtin_memcpy(target->qs+k*32+(row%4)*8,converted.qs+k*8,8);
        #endif
    }
}

#if defined(__aarch64__) && defined(__ARM_FEATURE_DOTPROD)
#if BONSAI_PTQ_PLANAR
extern bool bonsai_arm_planar_dot4(int n, float * output, const void * weights, const void * activations);
static void dot4_arm(int n, float * output, const void * weights, const void * activations) {
    (void)bonsai_arm_planar_dot4(n,output,weights,activations);
}
#else
#include <arm_neon.h>
static float half_float(ggml_half bits) {
    __fp16 value;
    __builtin_memcpy(&value, &bits, sizeof(value));
    return (float)value;
}
static void dot4_arm(int n, float * output, const void * weights, const void * activations) {
    const BonsaiPtqCache4 * x = weights;
    const block_q8_0 * y = activations;
    const int blocks = n / 128;
    float32x4_t total[4] = {vdupq_n_f32(0), vdupq_n_f32(0), vdupq_n_f32(0), vdupq_n_f32(0)};
    const uint8x16_t mask = vdupq_n_u8(3);
    const int8x16_t one = vdupq_n_s8(1);
    // Eight packed bytes expand to 32 weights in their ORIGINAL order. Unlike a
    // bitplane float accumulation, this retains the SDOT lane groups of PTQ1.
    const uint8_t idx0[16] = {0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3};
    const uint8_t idx1[16] = {4,4,4,4,5,5,5,5,6,6,6,6,7,7,7,7};
    const int8_t shifts_data[16] = {0,-2,-4,-6,0,-2,-4,-6,0,-2,-4,-6,0,-2,-4,-6};
    const uint8x16_t lo = vld1q_u8(idx0), hi = vld1q_u8(idx1);
    const int8x16_t shifts = vld1q_s8(shifts_data);
    for (int b = 0; b < blocks; ++b) {
        float scales[4];
        #pragma GCC unroll 4
        for (int row = 0; row < 4; ++row) scales[row] = half_float(x[b].d[row]);
        #pragma GCC unroll 4
        for (int k = 0; k < 4; ++k) {
            const block_q8_0 * q = y + b*4+k;
            const int8x16_t y0 = vld1q_s8(q->qs), y1 = vld1q_s8(q->qs + 16);
            const float dy = half_float(q->d);
            #pragma GCC unroll 4
            for (int row = 0; row < 4; ++row) {
                const uint8x8_t bytes = vld1_u8(x[b].qs + k*32 + row*8);
                const uint8x16_t raw = vcombine_u8(bytes, bytes);
                const int8x16_t w0 = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(vshlq_u8(vqtbl1q_u8(raw,lo),shifts),mask)),one);
                const int8x16_t w1 = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(vshlq_u8(vqtbl1q_u8(raw,hi),shifts),mask)),one);
                const int32x4_t sum = vdotq_s32(vdotq_s32(vdupq_n_s32(0),w0,y0),w1,y1);
                total[row] = vfmaq_n_f32(total[row],vcvtq_f32_s32(sum),scales[row]*dy);
            }
        }
    }
    for (int row = 0; row < 4; ++row) output[row] = vaddvq_f32(total[row]);
}
#endif
#endif

#if (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
#include <immintrin.h>
#define RECODE_AVX2 __attribute__((target("avx2,fma,f16c")))
RECODE_AVX2 static inline float half_float_x86(ggml_half bits) {
    return _mm_cvtss_f32(_mm_cvtph_ps(_mm_cvtsi32_si128(bits)));
}
RECODE_AVX2 static inline int sum8(__m256i value) {
    __m128i sum = _mm_add_epi32(_mm256_castsi256_si128(value), _mm256_extracti128_si256(value,1));
    sum = _mm_hadd_epi32(sum,sum);
    return _mm_cvtsi128_si32(_mm_hadd_epi32(sum,sum));
}
RECODE_AVX2 static void dot4_x86(int n, float * output, const void * weights, const void * activations) {
    const BonsaiPtqCache4 * x = weights;
    const block_q8_0 * y = activations;
    const int blocks = n / 128;
    const __m256i shifts = _mm256_setr_epi64x(0,2,4,6), mask = _mm256_set1_epi8(3);
    const __m256i ones16 = _mm256_set1_epi16(1);
    const int16_t * activation_sums = (const int16_t *)(y+n/32);
    #if !BONSAI_PTQ_PLANAR
    const __m256i shuffle = _mm256_setr_epi8(0,4,8,12,1,5,9,13,2,6,10,14,3,7,11,15,
                                            0,4,8,12,1,5,9,13,2,6,10,14,3,7,11,15);
    const __m256i permute = _mm256_setr_epi32(0,4,1,5,2,6,3,7);
    #endif
    float total[4] = {0,0,0,0};
    for (int b = 0; b < blocks; ++b) {
        float sub[4] = {0,0,0,0};
        #pragma GCC unroll 4
        for (int k = 0; k < 4; ++k) {
            const block_q8_0 * q = y + b*4+k;
            #if BONSAI_PTQ_PLANAR
            const __m256i activation = _mm256_loadu_si256((const __m256i *)q->qs);
            #else
            const __m256i activation = _mm256_permutevar8x32_epi32(
                _mm256_shuffle_epi8(_mm256_loadu_si256((const __m256i *)q->qs),shuffle),permute);
            #endif
            const int correction = activation_sums[b*4+k];
            const float dy = half_float_x86(q->d);
            #pragma GCC unroll 4
            for (int row = 0; row < 4; ++row) {
                int64_t bits;
                __builtin_memcpy(&bits,x[b].qs+k*32+row*8,sizeof(bits));
                const __m256i codes = _mm256_and_si256(_mm256_srlv_epi64(_mm256_set1_epi64x(bits),shifts),mask);
                const int dot = sum8(_mm256_madd_epi16(_mm256_maddubs_epi16(codes,activation),ones16)) - correction;
                sub[row] = _mm_cvtss_f32(_mm_fmadd_ss(_mm_set_ss(dy),_mm_set_ss((float)dot),_mm_set_ss(sub[row])));
            }
        }
        #pragma GCC unroll 4
        for (int row = 0; row < 4; ++row)
            total[row] = _mm_cvtss_f32(_mm_fmadd_ss(_mm_set_ss(half_float_x86(x[b].d[row])),_mm_set_ss(sub[row]),_mm_set_ss(total[row])));
    }
    for (int row = 0; row < 4; ++row) output[row] = total[row];
}

#define RECODE_VNNI __attribute__((target("avx512f,avx512bw,avx512dq,avx512vl,avx512vnni,fma,f16c")))
RECODE_AVX2 static inline __m256i vnni_activation(const block_q8_0 * q) {
    const __m256i bytes=_mm256_loadu_si256((const __m256i *)q->qs);
    #if BONSAI_PTQ_PLANAR
    const __m256i chunks=_mm256_permutevar8x32_epi32(bytes,_mm256_setr_epi32(0,2,4,6,1,3,5,7));
    return _mm256_shuffle_epi8(chunks,_mm256_setr_epi8(
        0,4,8,12,1,5,9,13,2,6,10,14,3,7,11,15,
        0,4,8,12,1,5,9,13,2,6,10,14,3,7,11,15));
    #else
    return bytes;
    #endif
}
RECODE_VNNI static inline __m512i expand_pair(const uint8_t * pair) {
    const __m512i raw = _mm512_cvtepu8_epi32(_mm_loadu_si128((const __m128i *)pair));
    const __m512i spread = _mm512_or_si512(_mm512_or_si512(raw,_mm512_slli_epi32(raw,6)),
                                          _mm512_or_si512(_mm512_slli_epi32(raw,12),_mm512_slli_epi32(raw,18)));
    return _mm512_and_si512(spread,_mm512_set1_epi8(3));
}
RECODE_VNNI static inline __m128i reduce_four(__m512i a, __m512i b) {
    const __m256i h0 = _mm256_hadd_epi32(_mm512_castsi512_si256(a),_mm512_extracti64x4_epi64(a,1));
    const __m256i h1 = _mm256_hadd_epi32(_mm512_castsi512_si256(b),_mm512_extracti64x4_epi64(b,1));
    const __m256i hh = _mm256_hadd_epi32(h0,h1);
    return _mm_add_epi32(_mm256_castsi256_si128(hh),_mm256_extracti128_si256(hh,1));
}
RECODE_VNNI static void dot4_vnni(int n, float * output, const void * weights, const void * activations) {
    const BonsaiPtqCache4 * x = weights;
    const block_q8_0 * y = activations;
    const int blocks = n/128;
    const int16_t * activation_sums = (const int16_t *)(y+n/32);
    __m128 total = _mm_setzero_ps();
    for (int b=0;b<blocks;++b) {
        __m128 subtotal = _mm_setzero_ps();
        #pragma GCC unroll 4
        for (int k=0;k<4;++k) {
            const block_q8_0 * q = y+b*4+k;
            const __m512i activation = _mm512_broadcast_i64x4(vnni_activation(q));
            const __m512i p01 = _mm512_dpbusd_epi32(_mm512_setzero_si512(),
                expand_pair(x[b].qs+k*32),activation);
            const __m512i p23 = _mm512_dpbusd_epi32(_mm512_setzero_si512(),
                expand_pair(x[b].qs+k*32+16),activation);
            const int correction = activation_sums[b*4+k];
            const __m128i integers = _mm_sub_epi32(reduce_four(p01,p23),_mm_set1_epi32(correction));
            subtotal = _mm_fmadd_ps(_mm_cvtepi32_ps(integers),_mm_set1_ps(half_float_x86(q->d)),subtotal);
        }
        const __m128 scales = _mm_cvtph_ps(_mm_loadl_epi64((const __m128i *)x[b].d));
        total = _mm_fmadd_ps(scales,subtotal,total);
    }
    _mm_storeu_ps(output,total);
}

#define RECODE_VBMI __attribute__((target("avx512f,avx512bw,avx512dq,avx512vl,avx512vnni,avx512vbmi,fma,f16c")))
RECODE_VBMI static inline __m512i expand_pair_vbmi(const uint8_t * pair) {
    // Replicate each original eight-byte row into four 64-bit lanes. Each
    // multishift selects eight adjacent two-bit codes, preserving weight order.
    const __m512i pairs = _mm512_broadcast_i64x2(_mm_loadu_si128((const __m128i *)pair));
    const __m512i rows = _mm512_permutexvar_epi64(_mm512_setr_epi64(0,0,0,0,1,1,1,1),pairs);
    const __m512i shifts = _mm512_setr_epi64(
        0x0e0c0a0806040200LL,0x1e1c1a1816141210LL,0x2e2c2a2826242220LL,0x3e3c3a3836343230LL,
        0x0e0c0a0806040200LL,0x1e1c1a1816141210LL,0x2e2c2a2826242220LL,0x3e3c3a3836343230LL);
    return _mm512_and_si512(_mm512_multishift_epi64_epi8(shifts,rows),_mm512_set1_epi8(3));
}
RECODE_VBMI static void dot4_vbmi(int n, float * output, const void * weights, const void * activations) {
    const BonsaiPtqCache4 * x = weights;
    const block_q8_0 * y = activations;
    const int blocks = n/128;
    const int16_t * activation_sums = (const int16_t *)(y+n/32);
    __m128 total = _mm_setzero_ps();
    for (int b=0;b<blocks;++b) {
        __m128 subtotal = _mm_setzero_ps();
        #pragma GCC unroll 4
        for (int k=0;k<4;++k) {
            const block_q8_0 * q = y+b*4+k;
            const __m512i activation = _mm512_broadcast_i64x4(vnni_activation(q));
            const __m512i p01 = _mm512_dpbusd_epi32(_mm512_setzero_si512(),expand_pair_vbmi(x[b].qs+k*32),activation);
            const __m512i p23 = _mm512_dpbusd_epi32(_mm512_setzero_si512(),expand_pair_vbmi(x[b].qs+k*32+16),activation);
            const __m128i integers = _mm_sub_epi32(reduce_four(p01,p23),_mm_set1_epi32(activation_sums[b*4+k]));
            // Identical block boundaries and fused accumulation to dot4_vnni.
            subtotal = _mm_fmadd_ps(_mm_cvtepi32_ps(integers),_mm_set1_ps(half_float_x86(q->d)),subtotal);
        }
        const __m128 scales = _mm_cvtph_ps(_mm_loadl_epi64((const __m128i *)x[b].d));
        total = _mm_fmadd_ps(scales,subtotal,total);
    }
    _mm_storeu_ps(output,total);
}
#endif

BonsaiPtqDot4 bonsai_ptq_recode_select_dot4(void) {
#if defined(__aarch64__) && defined(__ARM_FEATURE_DOTPROD)
    return dot4_arm;
#elif (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
    if (!BONSAI_RECODE_AVX2_ONLY && __builtin_cpu_supports("avx512f") && __builtin_cpu_supports("avx512bw") &&
        __builtin_cpu_supports("avx512dq") && __builtin_cpu_supports("avx512vl") &&
        __builtin_cpu_supports("avx512vnni") && __builtin_cpu_supports("fma") && __builtin_cpu_supports("f16c")) {
        if (BONSAI_RECODE_VBMI && !BONSAI_RECODE_VNNI_ONLY && __builtin_cpu_supports("avx512vbmi"))
            return dot4_vbmi;
        #if BONSAI_RECODE_BUTTERFLY
        return bonsai_ptq_butterfly_dot4;
        #endif
        return dot4_vnni;
    }
    if (__builtin_cpu_supports("avx2") && __builtin_cpu_supports("fma") && __builtin_cpu_supports("f16c")) {
        return dot4_x86;
    }
#endif
    return NULL;
}

bool bonsai_ptq_recode_dot4(int n, float * output, const void * weights, const void * activations) {
    if (n < 0 || n % 128) return false;
#if defined(__aarch64__) && defined(__ARM_FEATURE_DOTPROD)
    dot4_arm(n,output,weights,activations);
    return true;
#elif (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
    if (!BONSAI_RECODE_AVX2_ONLY && __builtin_cpu_supports("avx512f") && __builtin_cpu_supports("avx512bw") &&
        __builtin_cpu_supports("avx512dq") && __builtin_cpu_supports("avx512vl") &&
        __builtin_cpu_supports("avx512vnni") && __builtin_cpu_supports("fma") && __builtin_cpu_supports("f16c")) {
        if (BONSAI_RECODE_VBMI && !BONSAI_RECODE_VNNI_ONLY && __builtin_cpu_supports("avx512vbmi"))
            dot4_vbmi(n,output,weights,activations);
        #if BONSAI_RECODE_BUTTERFLY
        else bonsai_ptq_butterfly_dot4(n,output,weights,activations);
        #else
        else dot4_vnni(n,output,weights,activations);
        #endif
        return true;
    }
    if (__builtin_cpu_supports("avx2") && __builtin_cpu_supports("fma") && __builtin_cpu_supports("f16c")) {
        dot4_x86(n,output,weights,activations);
        return true;
    }
#endif
    (void)output; (void)weights; (void)activations;
    return false;
}

const char * bonsai_ptq_recode_kernel_name(void) {
    const BonsaiPtqDot4 kernel = bonsai_ptq_recode_select_dot4();
#if defined(__aarch64__) && defined(__ARM_FEATURE_DOTPROD)
    if (kernel == dot4_arm) return BONSAI_PTQ_PLANAR ? "arm-dotprod-planar" : "arm-dotprod";
#elif (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
    if (kernel == dot4_vbmi) return BONSAI_PTQ_PLANAR ? "avx512-vbmi-vnni-planar" : "avx512-vbmi-vnni";
    #if BONSAI_RECODE_BUTTERFLY
    if (kernel == bonsai_ptq_butterfly_dot4)
        return BONSAI_PTQ_PLANAR ? "avx512-vnni-butterfly-planar" : "avx512-vnni-butterfly";
    #endif
    if (kernel == dot4_vnni) return BONSAI_PTQ_PLANAR ? "avx512-vnni-planar" : "avx512-vnni";
    if (kernel == dot4_x86) return BONSAI_PTQ_PLANAR ? "avx2-planar" : "avx2";
#else
    (void)kernel;
#endif
    return BONSAI_PTQ_PLANAR ? "scalar-planar" : "scalar";
}
