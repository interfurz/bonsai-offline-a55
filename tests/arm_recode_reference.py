"""Independent numerical and compiled pinned-source references for ARM recoding.

This uses ArmKernelRunner's existing absolute-source-path support. No production
source, harness flags or vendored file is rewritten. The source extraction is
intentional: a hand-copied baseline could silently diverge from the pinned tree.
"""
from fractions import Fraction
import math
from pathlib import Path
import struct
import tempfile

from arm_kernel_runner import ArmKernelRunner, ROOT


def fp16(value):
    """The value actually serialized in a Q8/PTQ scale, not its Python input."""
    return struct.unpack("<e", struct.pack("<e", value))[0]


def fp32_bits(value):
    return struct.pack("<f", value)


def round_fp32(value):
    """Round an exact rational to binary32, including ties and subnormals.

    Avoid a binary64 intermediate rounding before binary32: those rare ties are
    precisely what a fused-versus-unfused regression test must not approximate.
    The fixtures stay finite and well below binary32 overflow.
    """
    value = Fraction(value)
    if not value:
        return 0.0
    sign = -1 if value < 0 else 1
    value = abs(value)
    exponent = value.numerator.bit_length() - value.denominator.bit_length()
    power = Fraction(2) ** exponent
    if value < power:
        exponent -= 1
    step_exponent = max(-149, exponent - 23)
    significand = round(value / (Fraction(2) ** step_exponent))
    if not significand:
        return math.copysign(0.0, sign)
    return math.ldexp(sign * significand, step_exponent)


def arm_lane_dot(n, packed, values, weight_scales, activation_scales, *,
                 fused=True, preserve_lanes=True):
    """Exact SDOT/FMLA/FADDP model, independent of the cache's TBL decoder.

    Each SDOT lane owns four consecutive bytes in each 16-byte half. Accumulate
    across all Q8 blocks before the pairwise (0+1)+(2+3) horizontal reduction.
    The switches are deliberate negative controls for fixture sensitivity only.
    """
    output = []
    for row in range(4):
        lanes = [0.0] * 4
        for block in range(n // 32):
            products = []
            for j in range(32):
                index = block * 32 + j
                byte = packed[row * (n // 4) + index // 4]
                weight = ((byte >> (2 * (index % 4))) & 3) - 1
                products.append(weight * values[index])
            if preserve_lanes:
                integers = [sum(products[lane * 4:lane * 4 + 4]) +
                            sum(products[16 + lane * 4:20 + lane * 4])
                            for lane in range(4)]
            else:
                integers = [sum(products), 0, 0, 0]
            scale = round_fp32(Fraction(fp16(weight_scales[row][block // 4])) *
                               Fraction(fp16(activation_scales[block])))
            for lane, integer in enumerate(integers):
                product = Fraction(integer) * Fraction(scale)
                if not fused:
                    product = Fraction(round_fp32(product))
                lanes[lane] = round_fp32(Fraction(lanes[lane]) + product)
        left = round_fp32(Fraction(lanes[0]) + Fraction(lanes[1]))
        right = round_fp32(Fraction(lanes[2]) + Fraction(lanes[3]))
        output.append(round_fp32(Fraction(left) + Fraction(right)))
    return tuple(output)


def decode_ptq_block(packed):
    """Decode the original 26 payload bytes, without the candidate's codec."""
    if len(packed) != 26:
        raise ValueError("PTQ payload must contain exactly 26 bytes")
    result = []
    for start, width, powers in ((0, 16, (1, 3, 9, 27, 81)),
                                  (16, 8, (1, 3, 9, 27, 81)),
                                  (24, 2, (1, 3, 9, 27))):
        for power in powers:
            result.extend(((((packed[start + j] * power) & 255) * 3) >> 8) - 1
                          for j in range(width))
    return result


def _extract_function(source, signature):
    start = source.index(signature)
    # These pinned C definitions have column-zero closing braces. Fail visibly
    # if their layout changes instead of falling back to a synthetic baseline.
    end = source.index("\n}\n", start) + len("\n}")
    return source[start:end]


class PinnedArmPtqRunner:
    """Compile the actual pinned NEON decoder and original PTQ dot definition."""
    def __init__(self):
        path = ROOT / "vendor/llama.cpp/ggml/src/ggml-cpu/arch/arm/quants.c"
        source = path.read_text(encoding="utf-8")
        decoder = _extract_function(source, "static inline void ggml_ptq1_0_decode_neon(")
        dot = _extract_function(source, "void ggml_vec_dot_ptq1_0_q8_0(")
        preamble = r'''
#include <stddef.h>
#include <stdint.h>
#include <arm_neon.h>
#define GGML_COMMON_DECL_C
#include "ggml-common.h"
#if !defined(__ARM_NEON) || !defined(__ARM_FEATURE_DOTPROD)
#error The pinned baseline must execute the NEON SDOT path
#endif
#if defined(__ARM_FEATURE_MATMUL_INT8)
#error This reference targets the original nrc=1 SDOT path, not I8MM
#endif
#define GGML_RESTRICT restrict
#define UNUSED(x) (void)(x)
#define assert(x) do { if (!(x)) __builtin_trap(); } while (0)
static float test_half_float(ggml_half bits) {
    __fp16 value;
    __builtin_memcpy(&value, &bits, sizeof(value));
    return (float)value;
}
#define GGML_CPU_FP16_TO_FP32(x) test_half_float(x)
'''
        wrapper = r'''
int bonsai_test_original_ptq_dot4(int n, float * output,
                                const void * weights, const void * activations) {
    if (n < 0 || n % QK_PTQ1_0) return 0;
    const block_ptq1_0 * rows = weights;
    for (int row = 0; row < 4; ++row)
        ggml_vec_dot_ptq1_0_q8_0(n, output + row, 0,
                               rows + row * (n / QK_PTQ1_0), 0,
                               activations, 0, 1);
    return 1;
}
'''
        # ArmKernelRunner joins source to a directory using pathlib; absolute
        # paths retain their root, so no shared harness change is necessary.
        with tempfile.TemporaryDirectory(prefix="bonsai-arm-ptq-reference-") as directory:
            translation_unit = Path(directory) / "original_ptq.c"
            translation_unit.write_text(preamble + decoder + "\n" + dot + wrapper,
                                        encoding="utf-8")
            self.runner = ArmKernelRunner(str(translation_unit.resolve()),
                                          "bonsai_test_original_ptq_dot4")

    def close(self):
        self.runner.close()

    def execute(self, n, weights, activations):
        return self.runner.execute(n, weights, activations, output_count=4)
