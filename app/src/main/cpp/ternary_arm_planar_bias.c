/* Isolated exact integer-bias candidate; not integrated into dispatch.
 *
 * Identical planar/Q8_0 contract to ternary_arm_planar.c (no layout change):
 * each 128-weight/four-row block has uint16_t d[4], uint8_t qs[4][4][8],
 * exactly 136 bytes, with little-endian FP16 scale bits and no padding.
 * qs[k][r][j] packs weight positions 32*k+j+8*p as (w+1) << (2*p),
 * p=0..3. All codes 0..3 decode to -1..2. Each Q8_0 block remains
 * FP16 d followed by 32 signed activation bytes (34 bytes, including -128).
 *
 * Dot raw codes, then subtract activation-only INTEGER lane sums once per
 * paired-row dot. The sums are shared by both row pairs in each Q8_0 block.
 * s02 owns original lanes 0,1 (duplicated for the paired rows), s13 lanes
 * 2,3. Never substitute sum32 or reduce floating lanes before the last vaddv.
 * Four float32x4_t accumulators, scale multiplication and FMA order match
 * the original planar and pinned ARM PTQ SDOT kernels exactly.
 *
 * API: n >= 0, divisible by 128; four nonoverlapping output floats,
 * (n/128)*136 weight bytes and (n/32)*34 activation bytes, no overlap.
 * Invalid n returns false without any buffer access. n=0 writes four +0
 * values without reading inputs. Valid-width buffer sizes are caller-owned.
 * Unsupported ISAs emit no kernel, exactly like the original planar source.
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
} BonsaiArmPlanarBias4;
_Static_assert(sizeof(BonsaiArmPlanarBias4) == 136, "Planar cache must not grow");
_Static_assert(offsetof(BonsaiArmPlanarBias4, qs) == 8, "Scale header changed");
_Static_assert(sizeof(block_q8_0) == 34, "Q8_0 activation layout changed");

static inline float planar_bias_half_float(uint16_t bits) {
    __fp16 value;
    __builtin_memcpy(&value, &bits, sizeof(value));
    return (float) value;
}

/* a[p] duplicates activation positions [8*p,8*p+8) for two rows.
 * bias02 = [sum(a[0:4]+a[16:20]), sum(a[4:8]+a[20:24]), repeat].
 * bias13 = [sum(a[8:12]+a[24:28]), sum(a[12:16]+a[28:32]), repeat].
 * Each correction is bounded by [-1024,1016]; raw-code SDOT and corrected
 * lanes fit int32 exactly. No int8 negation/addition can overflow at -128.
 */
static inline void planar_bias_pair(const uint8_t * packed, const int8x16_t a[4],
                                    int32x4_t bias02, int32x4_t bias13,
                                    int32x4_t * first, int32x4_t * second) {
    const uint8x16_t raw = vld1q_u8(packed);
    const uint8x16_t mask = vdupq_n_u8(3);
    const int8x16_t p0 = vreinterpretq_s8_u8(vandq_u8(raw, mask));
    const int8x16_t p1 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(raw, 2), mask));
    const int8x16_t p2 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(raw, 4), mask));
    const int8x16_t p3 = vreinterpretq_s8_u8(vshrq_n_u8(raw, 6));
    const int32x4_t s02 = vsubq_s32(
        vdotq_s32(vdotq_s32(vdupq_n_s32(0), p0, a[0]), p2, a[2]), bias02);
    const int32x4_t s13 = vsubq_s32(
        vdotq_s32(vdotq_s32(vdupq_n_s32(0), p1, a[1]), p3, a[3]), bias13);
    *first  = vcombine_s32(vget_low_s32(s02), vget_low_s32(s13));
    *second = vcombine_s32(vget_high_s32(s02), vget_high_s32(s13));
}

bool bonsai_arm_planar_bias_dot4(int n, float * out,
                                 const void * weights, const void * activations) {
    if (n < 0 || n % 128) return false;
    const BonsaiArmPlanarBias4 * x = weights;
    const block_q8_0 * y = activations;
    float32x4_t total[4] = {vdupq_n_f32(0), vdupq_n_f32(0),
                            vdupq_n_f32(0), vdupq_n_f32(0)};
    for (int b = 0; b < n / 128; ++b) {
        const float d0 = planar_bias_half_float(x[b].d[0]);
        const float d1 = planar_bias_half_float(x[b].d[1]);
        const float d2 = planar_bias_half_float(x[b].d[2]);
        const float d3 = planar_bias_half_float(x[b].d[3]);
        for (int k = 0; k < 4; ++k) {
            const block_q8_0 * q = y + 4*b + k;
            int8x16_t a[4];
            for (int p = 0; p < 4; ++p) {
                const int8x8_t eight = vld1_s8(q->qs + 8*p);
                a[p] = vcombine_s8(eight, eight);
            }
            /* Activation-only lane corrections, once per k, before all rows.
             * Four additional SDOTs trade for fewer SUBs, not a speed claim. */
            const int8x16_t one = vdupq_n_s8(1);
            const int32x4_t bias02 = vdotq_s32(
                vdotq_s32(vdupq_n_s32(0), one, a[0]), one, a[2]);
            const int32x4_t bias13 = vdotq_s32(
                vdotq_s32(vdupq_n_s32(0), one, a[1]), one, a[3]);
            int32x4_t s0, s1, s2, s3;
            planar_bias_pair(&x[b].qs[k][0][0], a, bias02, bias13, &s0, &s1);
            planar_bias_pair(&x[b].qs[k][2][0], a, bias02, bias13, &s2, &s3);
            const float dy = planar_bias_half_float(q->d);
            total[0] = vfmaq_n_f32(total[0], vcvtq_f32_s32(s0), d0*dy);
            total[1] = vfmaq_n_f32(total[1], vcvtq_f32_s32(s1), d1*dy);
            total[2] = vfmaq_n_f32(total[2], vcvtq_f32_s32(s2), d2*dy);
            total[3] = vfmaq_n_f32(total[3], vcvtq_f32_s32(s3), d3*dy);
        }
    }
    for (int row = 0; row < 4; ++row) out[row] = vaddvq_f32(total[row]);
    return true;
}
#endif // Unsupported ISAs retain the independent scalar cache consumer.
