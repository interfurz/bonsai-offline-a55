"""Compiled ARM cache GEMV: dense, exact lane/FMA and pinned-original checks."""
import math
import random
import struct
import unittest

from arm_kernel_runner import ArmKernelRunner
from arm_recode_reference import (PinnedArmPtqRunner, arm_lane_dot,
                                  decode_ptq_block, fp16, fp32_bits)


def half_bits(bits):
    return struct.unpack("<e", struct.pack("<H", bits))[0]


# Exact encoded half values: irregular mantissas, signs, signed zero,
# subnormals and the largest finite half. Deliberately exclude Inf/NaN.
WEIGHT_SCALES = tuple(map(half_bits, (
    0x3555, 0x3C01, 0xB6DB, 0x4103, 0x0401, 0x03FF,
    0x8001, 0x0000, 0x8000, 0x7BFF, 0xBBFF,
)))
ACTIVATION_SCALES = tuple(map(half_bits, (
    0x2E67, 0x3555, 0x3AAB, 0x0401, 0x0001, 0x0000, 0x3C01,
)))


class ArmRecodeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = ArmKernelRunner("ptq_recode_kernels.c", "bonsai_ptq_recode_dot4")
        cls.addClassCleanup(cls.runner.close)
        cls.original = PinnedArmPtqRunner()
        cls.addClassCleanup(cls.original.close)

    def assert_float_bits_equal(self, actual, expected, label):
        self.assertEqual(len(actual), len(expected))
        for row, (got, want) in enumerate(zip(actual, expected)):
            self.assertTrue(math.isfinite(got), f"{label}, row={row}: {got}")
            self.assertEqual(fp32_bits(got), fp32_bits(want),
                             f"{label}, row={row}: {got!r} != {want!r}")

    def check_dot(self, n, packed, values, weight_scales=None,
                  activation_scales=None, *, strict=False, cancellation=False):
        self.assertEqual(len(packed), n)
        self.assertEqual(len(values), n)
        if weight_scales is None:
            weight_scales = [[[-0.25, 0.0, 0.125, 1.5][(row + block) % 4]
                              for block in range(n // 128)] for row in range(4)]
        if activation_scales is None:
            activation_scales = [(block % 7 + 1) / 32 for block in range(n // 32)]
        self.assertEqual(len(weight_scales), 4)
        self.assertTrue(all(len(row) == n // 128 for row in weight_scales))
        self.assertEqual(len(activation_scales), n // 32)
        scales = [[fp16(value) for value in row] for row in weight_scales]
        activation_scales = [fp16(value) for value in activation_scales]
        weights, activations = bytearray(), bytearray()
        for block in range(n // 128):
            weights.extend(struct.pack("<4e", *[scales[row][block] for row in range(4)]))
            for k in range(4):
                for row in range(4):
                    start = row * n // 4 + block * 32 + k * 8
                    weights.extend(packed[start:start+8])
        for block in range(n // 32):
            activations.extend(struct.pack("<e32b", activation_scales[block],
                                           *values[block * 32:(block + 1) * 32]))
        self.assertEqual(len(weights), (n // 128) * 136)
        self.assertEqual(len(activations), (n // 32) * 34)
        accepted, actual = self.runner.execute(n, bytes(weights), bytes(activations), output_count=4)
        self.assertEqual(accepted, 1)
        for row in range(4):
            products = []
            for j in range(n):
                byte = packed[row * n // 4 + j // 4]
                value = ((byte >> (2 * (j % 4))) & 3) - 1
                products.append(value * values[j] * scales[row][j // 128] *
                                activation_scales[j // 32])
            expected = math.fsum(products)
            magnitude = math.fsum(abs(product) for product in products)
            self.assertTrue(math.isfinite(actual[row]))
            # Keep the existing dense bound; new strict checks additionally
            # require bit equality, rather than widening the tolerance.
            self.assertLessEqual(abs(actual[row] - expected), 1e-5 * max(1.0, magnitude))
            if cancellation:
                self.assertGreater(magnitude, 0.0)
                self.assertLessEqual(abs(expected), 1e-3 * magnitude,
                                     "Fixture does not actually cause heavy cancellation")
        if strict:
            exact = arm_lane_dot(n, packed, values, scales, activation_scales)
            self.assert_float_bits_equal(actual, exact, f"lane/FMA reference, n={n}")
        return actual, bytes(activations)

    def check_original_dot(self, n, payloads, values, scales, activation_scales,
                           *, cancellation=False):
        """Compare original PTQ bytes against independently serialized cache.

        The baseline receives ORIGINAL PTQ, not candidate-cache bytes or Python
        oracle results; it executes the pinned original C definition in ARM.
        """
        original, packed = bytearray(), bytearray()
        self.assertEqual(len(payloads), 4)
        for row in range(4):
            self.assertEqual(len(payloads[row]), n // 128)
            for block, payload in enumerate(payloads[row]):
                self.assertEqual(len(payload), 26)
                original.extend(payload)
                original.extend(struct.pack("<e", scales[row][block]))
                decoded = decode_ptq_block(payload)
                for start in range(0, 128, 4):
                    packed.append(sum((decoded[start + k] + 1) << (2 * k)
                                      for k in range(4)))
        self.assertEqual(len(original), 4 * (n // 128) * 28)
        actual, activations = self.check_dot(n, packed, values, scales, activation_scales,
                                             strict=True, cancellation=cancellation)
        accepted, baseline = self.original.execute(n, bytes(original), activations)
        self.assertEqual(accepted, 1)
        self.assert_float_bits_equal(actual, baseline, f"compiled original PTQ, n={n}")
        return bytes(packed), actual

    def test_all_two_bit_codes_and_activation_endpoints(self):
        for byte in range(256):
            self.check_dot(128, bytes([byte]) * 128, [-128, 127, -127, 126, 0, 1, -1, 42] * 16)

    def test_random_four_rows_and_independent_scales(self):
        rng = random.Random(34)
        for n in (128, 256, 384, 640, 1024, 5120):
            for repeat in range(4):
                self.check_dot(n, bytes(rng.randrange(256) for _ in range(n)),
                               [rng.randrange(-128, 128) for _ in range(n)])

    def test_irregular_fp16_scales_and_long_rows_exact_lanes(self):
        rng = random.Random(3401)
        # 16384 fits the existing harness's separate cache/activation regions
        # and is substantially wider than the original 5120-element coverage.
        for n in (128, 384, 640, 5120, 12288, 16384):
            with self.subTest(n=n):
                scales = [[WEIGHT_SCALES[(row * 7 + block * 3) % len(WEIGHT_SCALES)]
                           for block in range(n // 128)] for row in range(4)]
                activation_scales = [ACTIVATION_SCALES[(block * 5 + 1) % len(ACTIVATION_SCALES)]
                                     for block in range(n // 32)]
                # Exercise the full two-bit alphabet, including code 3 (= +2),
                # which cannot be represented by an original PTQ trit payload.
                self.check_dot(n, bytes(rng.randrange(256) for _ in range(n)),
                               [rng.randrange(-128, 128) for _ in range(n)],
                               scales, activation_scales, strict=True)

    def test_pinned_original_ptq_irregular_scales_and_long_rows(self):
        rng = random.Random(3428)
        for n in (128, 384, 640, 5120, 12288, 16384):
            with self.subTest(n=n):
                payloads = [[bytes(rng.randrange(256) for _ in range(26))
                             for block in range(n // 128)] for row in range(4)]
                scales = [[WEIGHT_SCALES[(row * 5 + block) % len(WEIGHT_SCALES)]
                           for block in range(n // 128)] for row in range(4)]
                activation_scales = [ACTIVATION_SCALES[block % len(ACTIVATION_SCALES)]
                                     for block in range(n // 32)]
                values = [rng.randrange(-128, 128) for _ in range(n)]
                self.check_original_dot(n, payloads, values, scales, activation_scales)

    def test_cancellation_preserves_fma_and_sdot_lane_semantics(self):
        rng = random.Random(34136)
        sensitive_to_unfused = False
        sensitive_to_early_reduction = False
        for n in (5120, 16384):
            with self.subTest(n=n):
                payloads = [[] for _ in range(4)]
                scales = [[] for _ in range(4)]
                values, activation_scales = [], []
                for pair in range(n // 256):
                    for row in range(4):
                        payload = bytes(rng.randrange(256) for _ in range(26))
                        payloads[row].extend((payload, payload))
                        scale = WEIGHT_SCALES[(pair + row) % 4]
                        scales[row].extend((scale, scale))
                    # Adjacent PTQ blocks have identical weights/scales and
                    # opposite activations. Avoid -128 only in this fixture so
                    # negation stays representable; other tests cover -128.
                    positive = [rng.randrange(-127, 128) for _ in range(128)]
                    values.extend(positive)
                    values.extend(-value for value in positive)
                    dy = [ACTIVATION_SCALES[index] for index in (0, 1, 2, 6)]
                    activation_scales.extend(dy + dy)
                # Retain a small real residual, not just an all-zero dot.
                index = n - 128
                values[index] += -1 if values[index] == 127 else 1
                packed, actual = self.check_original_dot(n, payloads, values, scales,
                                                         activation_scales, cancellation=True)
                unfused = arm_lane_dot(n, packed, values, scales, activation_scales, fused=False)
                early = arm_lane_dot(n, packed, values, scales, activation_scales,
                                     preserve_lanes=False)
                sensitive_to_unfused |= any(fp32_bits(a) != fp32_bits(b)
                                           for a, b in zip(actual, unfused))
                sensitive_to_early_reduction |= any(fp32_bits(a) != fp32_bits(b)
                                                    for a, b in zip(actual, early))
        # Negative controls prevent fixtures that accidentally pass equally
        # well after removing FMA or collapsing the original SDOT lanes.
        self.assertTrue(sensitive_to_unfused, "Fixture does not distinguish FMA from MUL+ADD")
        self.assertTrue(sensitive_to_early_reduction,
                        "Fixture does not distinguish original SDOT lane accumulation")

    def test_unsupported_width_keeps_all_outputs_untouched(self):
        for n in (-128, 1, 127, 129):
            self.assertEqual(self.runner.execute(n, output_count=4), (0, (12345.0,) * 4))

    def test_empty_dot(self):
        self.assertEqual(self.runner.execute(0, output_count=4), (1, (0.0,) * 4))


if __name__ == "__main__":
    unittest.main()
