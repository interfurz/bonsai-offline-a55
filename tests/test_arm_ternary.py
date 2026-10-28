"""Execute the compiled AArch64 SDOT kernel, not a Python translation.

Requires clang, unicorn and pyelftools. This is arithmetic validation only;
Unicorn instruction execution is not a Galaxy A55 performance measurement.
"""
import math
import random
import struct
import unittest

from arm_kernel_runner import ArmKernelRunner


class ArmTernaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = ArmKernelRunner("ternary_dot.c", "bonsai_dot_pq2_q8k")

    @classmethod
    def tearDownClass(cls):
        cls.runner.close()

    def execute(self, n, weights=b"", activations=b""):
        return self.runner.execute(n, weights, activations)

    def check_dot(self, n, packed, values, scales):
        weights = bytearray()
        for block in range(n // 128):
            weights.extend(struct.pack("<e", scales[block]))
            weights.extend(packed[block * 32:(block + 1) * 32])
        activations = bytearray()
        for block in range(n // 256):
            activations.extend(struct.pack("<f", (block % 5 + 1) / 32))
            activations.extend(struct.pack("<256b", *values[block * 256:(block + 1) * 256]))
            activations.extend(bytes(32))  # Q8_K bsums are not read by this kernel.
        expected, magnitude = 0.0, 0.0
        for block in range(n // 128):
            integer = 0
            for j in range(128):
                code = (packed[block * 32 + j // 4] >> (2 * (j % 4))) & 3
                integer += (code - 1) * values[block * 128 + j]
            product = scales[block] * ((block // 2) % 5 + 1) / 32 * integer
            expected += product
            magnitude += abs(product)
        accepted, actual = self.execute(n, bytes(weights), bytes(activations))
        self.assertEqual(accepted, 1)
        self.assertTrue(math.isfinite(actual))
        self.assertLessEqual(abs(actual - expected), 1e-5 * max(1, magnitude))

    def test_all_packed_byte_values_and_activation_extremes(self):
        for code in range(256):
            self.check_dot(256, bytes([code]) * 64,
                           [-128, 127, -127, 126, 0, 1, -1, 42] * 32, [0.125, -0.25])

    def test_random_rows_and_independent_block_scales(self):
        rng = random.Random(715)
        for n in (256, 512, 768, 1024, 5120):
            for repeat in range(8):
                self.check_dot(n, bytes(rng.randrange(256) for _ in range(n // 4)),
                               [rng.randrange(-128, 128) for _ in range(n)],
                               [rng.choice([0.0, 2**-20, -0.125, 0.25, 1.5, -2.0]) for _ in range(n // 128)])

    def test_rejects_widths_requiring_q8_0_without_writing_result(self):
        for n in (-256, 1, 128, 384, 640):
            self.assertEqual(self.execute(n), (0, 12345.0))

    def test_empty_dot(self):
        self.assertEqual(self.execute(0), (1, 0.0))


if __name__ == "__main__":
    unittest.main()
