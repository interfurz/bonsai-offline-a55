"""Dense-reference checks for the compiled ARM PTQ1 streaming decoder."""
import math
import random
import struct
import unittest

from arm_kernel_runner import ArmKernelRunner


def decode_trits(packed):
    result = []
    for start, width in ((0, 16), (16, 8)):
        for power in (1, 3, 9, 27, 81):
            result.extend((((packed[start + j] * power) & 255) * 3 >> 8) - 1 for j in range(width))
    for power in (1, 3, 9, 27):
        result.extend((((packed[24 + j] * power) & 255) * 3 >> 8) - 1 for j in range(2))
    assert len(result) == 128
    return result


class ArmPtqTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = ArmKernelRunner("ternary_ptq.c", "bonsai_dot_ptq1_q80")

    @classmethod
    def tearDownClass(cls):
        cls.runner.close()

    def check_dot(self, n, packed, values, weight_scales):
        weights, activations = bytearray(), bytearray()
        for block in range(n // 128):
            weights.extend(packed[block * 26:(block + 1) * 26])
            weights.extend(struct.pack("<e", weight_scales[block]))
        for block in range(n // 32):
            activations.extend(struct.pack("<e32b", (block % 7 + 1) / 32,
                                           *values[block * 32:(block + 1) * 32]))
        expected, magnitude = 0.0, 0.0
        for block in range(n // 128):
            decoded = decode_trits(packed[block * 26:(block + 1) * 26])
            for k in range(4):
                integer = sum(decoded[k * 32 + j] * values[block * 128 + k * 32 + j] for j in range(32))
                product = weight_scales[block] * ((block * 4 + k) % 7 + 1) / 32 * integer
                expected += product
                magnitude += abs(product)
        accepted, actual = self.runner.execute(n, bytes(weights), bytes(activations))
        self.assertEqual(accepted, 1)
        self.assertTrue(math.isfinite(actual))
        self.assertLessEqual(abs(actual - expected), 1e-5 * max(1.0, magnitude))

    def test_all_byte_codes_and_activation_extremes(self):
        for code in range(256):
            self.check_dot(128, bytes([code]) * 26, [-128, 127, -127, 126, 0, 1, -1, 42] * 16, [0.125])

    def test_random_rows_and_scales(self):
        rng = random.Random(128)
        for n in (128, 256, 384, 1024, 5120):
            for repeat in range(8):
                self.check_dot(n, bytes(rng.randrange(256) for _ in range(n // 128 * 26)),
                               [rng.randrange(-128, 128) for _ in range(n)],
                               [rng.choice([0.0, 2**-20, -0.125, 0.25, 1.5, -2.0]) for _ in range(n // 128)])

    def test_invalid_widths_leave_result_untouched(self):
        for n in (-128, 1, 127, 129):
            self.assertEqual(self.runner.execute(n), (0, 12345.0))

    def test_empty_dot(self):
        self.assertEqual(self.runner.execute(0), (1, 0.0))


if __name__ == "__main__":
    unittest.main()
