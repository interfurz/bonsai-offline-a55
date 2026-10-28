/* Experimental ARM kernel: selected only by the opt-in BONSAI_PTQ_PLANAR cache.
 *
 * Layout contract (little-endian scale bits, no padding): each four-row,
 * 128-weight block is 136 bytes: uint16_t d[4], uint8_t qs[4][4][8].
 * qs[k][r][j] = sum_p ((w[r][128*b + 32*k + j + 8*p] + 1) << (2*p)),
 * k,p,r in [0,4), j in [0,8). Codes 0..3 decode to -1..+2.
 * Hence exactly 34 bytes per row per 128 weights, like BonsaiPtqCache4,
 * but the payload is NOT interchangeable with its adjacent-code layout.
 * Activations are unchanged block_q8_0: FP16 d followed by 32 signed bytes;
 * no appended activation sums, requantization, or extra scale storage.
 *
 * Pairing rows lets immediate shifts decode four planes. SDOTs on planes
 * 0+2 produce original lanes 0,1 for each row, and planes 1+3 produce lanes
 * 2,3. Reassemble INTEGER lanes before conversion/FMA: never float-reduce
 * a plane or row pair. FMA order and final pairwise reduction match the
 * original ARM PTQ1_0 nrc=1 SDOT kernel exactly.
 *
 * API: n >= 0 and divisible by 128; four nonoverlapping float outputs,
 * (n/128)*136 weight bytes, (n/32)*34 activation bytes. Inputs/output must
 * not overlap. Invalid widths return false without touching any buffer.
 * n=0 writes four positive zeros and need not dereference either input.
 * No buffer lengths are passed: valid-width callers must provide them.
 */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#if defined(__aarch64__) && defined(__ARM_FEATURE_DOTPROD)
#include <arm_neon.h>

#define GGML_COMMON_DECL_C
#include "ggml-common.h"

typedef struct {
    uint16_t d[4];
    uint8_t qs[4][4][8];
} BonsaiArmPlanar4;
_Static_assert(sizeof(BonsaiArmPlanar4) == 136, "Planar cache must not grow");
_Static_assert(offsetof(BonsaiArmPlanar4, qs) == 8, "Scale header changed");
_Static_assert(sizeof(block_q8_0) == 34, "Q8_0 activation layout changed");

static inline float planar_half_float(uint16_t bits) {
    __fp16 value;
    __builtin_memcpy(&value, &bits, sizeof(value));
    return (float) value;
}

/* a[p] duplicates activation bytes [8*p,8*p+8) into both row halves. */
static inline void planar_pair(const uint8_t * packed, const int8x16_t a[4],
                               int32x4_t * first, int32x4_t * second) {
    const uint8x16_t raw = vld1q_u8(packed);
    const uint8x16_t mask = vdupq_n_u8(3);
    const int8x16_t one = vdupq_n_s8(1);
    const int8x16_t p0 = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(raw, mask)), one);
    const int8x16_t p1 = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(raw, 2), mask)), one);
    const int8x16_t p2 = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(raw, 4), mask)), one);
    const int8x16_t p3 = vsubq_s8(vreinterpretq_s8_u8(vshrq_n_u8(raw, 6)), one);
    const int32x4_t s02 = vdotq_s32(vdotq_s32(vdupq_n_s32(0), p0, a[0]), p2, a[2]);
    const int32x4_t s13 = vdotq_s32(vdotq_s32(vdupq_n_s32(0), p1, a[1]), p3, a[3]);
    *first  = vcombine_s32(vget_low_s32(s02), vget_low_s32(s13));
    *second = vcombine_s32(vget_high_s32(s02), vget_high_s32(s13));
}

bool bonsai_arm_planar_dot4(int n, float * output,
                            const void * weights, const void * activations) {
    if (n < 0 || n % 128) return false;
    const BonsaiArmPlanar4 * x = weights;
    const block_q8_0 * y = activations;
    float32x4_t total[4] = {vdupq_n_f32(0), vdupq_n_f32(0),
                            vdupq_n_f32(0), vdupq_n_f32(0)};
    for (int b = 0; b < n / 128; ++b) {
        const float d0 = planar_half_float(x[b].d[0]);
        const float d1 = planar_half_float(x[b].d[1]);
        const float d2 = planar_half_float(x[b].d[2]);
        const float d3 = planar_half_float(x[b].d[3]);
        for (int k = 0; k < 4; ++k) {
            const block_q8_0 * q = y + 4*b + k;
            int8x16_t a[4];
            for (int p = 0; p < 4; ++p) {
                const int8x8_t eight = vld1_s8(q->qs + 8*p);
                a[p] = vcombine_s8(eight, eight);
            }
            int32x4_t s0, s1, s2, s3;
            planar_pair(&x[b].qs[k][0][0], a, &s0, &s1);
            planar_pair(&x[b].qs[k][2][0], a, &s2, &s3);
            const float dy = planar_half_float(q->d);
            total[0] = vfmaq_n_f32(total[0], vcvtq_f32_s32(s0), d0*dy);
            total[1] = vfmaq_n_f32(total[1], vcvtq_f32_s32(s1), d1*dy);
            total[2] = vfmaq_n_f32(total[2], vcvtq_f32_s32(s2), d2*dy);
            total[3] = vfmaq_n_f32(total[3], vcvtq_f32_s32(s3), d3*dy);
        }
    }
    for (int row = 0; row < 4; ++row) output[row] = vaddvq_f32(total[row]);
    return true;
}
#endif // Unsupported ISAs use the independent scalar cache consumer.
