"""Execute the isolated fixed-shift ARM prototype; this is not a benchmark.

The planar serializer below consumes decoded row values, not production cache
bytes. Exact results are checked against the independent rational lane/FMA
oracle AND the compiled pinned original ARM PTQ decoder/dot (original bytes).
"""
import math
import random
import struct
import unittest
from unittest import mock

import arm_kernel_runner
from arm_kernel_runner import ArmKernelRunner
from arm_recode_reference import (PinnedArmPtqRunner, arm_lane_dot,
                                  decode_ptq_block, fp16, fp32_bits)
from unicorn import UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE, UC_MEM_WRITE


def half(bits):
    return struct.unpack("<e", struct.pack("<H", bits))[0]


WEIGHT_SCALES = tuple(map(half, (
    0x3555, 0x3C01, 0xB6DB, 0x4103, 0x0401, 0x03FF,
    0x8001, 0x0000, 0x8000, 0x7BFF, 0xBBFF,
)))
ACTIVATION_SCALES = tuple(map(half, (
    0x2E67, 0x3555, 0x3AAB, 0x0401, 0x0001, 0x0000, 0x3C01,
)))


def planar_packet(rows, scales):
    """136 bytes/block: <4e scales then k, row, j (eight bytes per row).

    Byte j stores codes at j, j+8, j+16, j+24, least-significant first.
    This intentionally does not call any production upload/recode helper.
    """
    n = len(rows[0])
    assert len(rows) == 4 and n % 128 == 0
    assert all(len(row) == n for row in rows)
    assert len(scales) == 4 and all(len(s) == n // 128 for s in scales)
    packet = bytearray()
    for block in range(n // 128):
        packet.extend(struct.pack("<4e", *(s[block] for s in scales)))
        for k in range(4):
            start = block * 128 + k * 32
            for row in rows:
                for j in range(8):
                    codes = [row[start + j + 8*p] + 1 for p in range(4)]
                    assert all(0 <= code <= 3 for code in codes)
                    packet.append(sum(code << (2*p) for p, code in enumerate(codes)))
    assert len(packet) == (n // 128) * 136
    return bytes(packet)


def canonical_packet(rows):
    """Independent adjacent-code serialization used ONLY by the oracle."""
    return bytes(sum((row[start + j] + 1) << (2*j) for j in range(4))
                 for row in rows for start in range(0, len(row), 4))


def activation_packet(values, scales):
    assert len(values) == len(scales) * 32
    return b"".join(struct.pack("<e32b", scale, *values[b*32:(b+1)*32])
                    for b, scale in enumerate(scales))


def execute_guarded(runner, n, weights=b"", activations=b""):
    """Reuse the existing runner, with byte-exact emulated memory bounds.

    Its broad mapped input regions otherwise conceal vector overreads. Hooks
    reject ANY access past either supplied input, input writes, and accesses
    outside the four output floats. Stack/code are the harness's own regions.
    """
    real_uc = arm_kernel_runner.Uc
    accesses = []

    def make_machine(*args, **kwargs):
        machine = real_uc(*args, **kwargs)

        def check(machine, access, address, size, value, user_data):
            end = address + size
            regions = ((0x300000, 0x301000, 0x300000 + 16, "output"),
                       (0x301000, 0x310000, 0x301000 + len(weights), "weights"),
                       (0x310000, 0x31F000, 0x310000 + len(activations), "activations"))
            for start, reserved_end, allowed_end, label in regions:
                if address < reserved_end and end > start:
                    if not (start <= address and end <= allowed_end):
                        raise AssertionError(f"{label} out of bounds: {address:#x}+{size}")
                    if access == UC_MEM_WRITE and label != "output":
                        raise AssertionError(f"write to {label}")
                    accesses.append((label, access, address, size))
                    break

        # Unicorn freezes CPU selection once hooks initialize its translator.
        # Install after the existing harness has selected MAX, not beforehand.
        set_model = machine.ctl_set_cpu_model

        def set_model_then_guard(model):
            set_model(model)
            machine.hook_add(UC_HOOK_MEM_READ | UC_HOOK_MEM_WRITE, check)

        machine.ctl_set_cpu_model = set_model_then_guard
        return machine

    with mock.patch.object(arm_kernel_runner, "Uc", make_machine):
        result = runner.execute(n, weights, activations, output_count=4)
    return result, accesses


class ArmPlanarTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = ArmKernelRunner("ternary_arm_planar.c", "bonsai_arm_planar_dot4")
        cls.addClassCleanup(cls.runner.close)
        cls.original = PinnedArmPtqRunner()
        cls.addClassCleanup(cls.original.close)

    def assert_bits(self, actual, expected, label):
        self.assertEqual(len(actual), 4)
        self.assertEqual(len(expected), 4)
        for row, (got, want) in enumerate(zip(actual, expected)):
            self.assertTrue(math.isfinite(got), f"{label}, row {row}: {got}")
            self.assertEqual(fp32_bits(got), fp32_bits(want),
                             f"{label}, row {row}: {got!r} != {want!r}")

    def check_rows(self, rows, values, scales=None, activation_scales=None,
                   *, original=None, cancellation=False, guarded=False):
        n = len(values)
        self.assertTrue(all(len(row) == n for row in rows))
        if scales is None:
            scales = [[WEIGHT_SCALES[(r*5 + b) % len(WEIGHT_SCALES)]
                       for b in range(n // 128)] for r in range(4)]
        if activation_scales is None:
            activation_scales = [ACTIVATION_SCALES[(b*5+1) % len(ACTIVATION_SCALES)]
                                 for b in range(n // 32)]
        scales = [[fp16(s) for s in row] for row in scales]
        activation_scales = [fp16(s) for s in activation_scales]
        weights = planar_packet(rows, scales)
        activations = activation_packet(values, activation_scales)
        self.assertEqual(len(weights), n // 128 * 136)
        self.assertEqual(len(activations), n // 32 * 34)
        if guarded:
            (accepted, actual), accesses = execute_guarded(self.runner, n, weights, activations)
            self.assertTrue(any(a[0] == "weights" for a in accesses))
            self.assertTrue(any(a[0] == "activations" for a in accesses))
        else:
            accepted, actual = self.runner.execute(n, weights, activations, output_count=4)
        self.assertEqual(accepted, 1)
        packed = canonical_packet(rows)
        expected = arm_lane_dot(n, packed, values, scales, activation_scales)
        self.assert_bits(actual, expected, f"rational SDOT/FMA, n={n}")
        if original is not None:
            accepted, baseline = self.original.execute(n, original, activations)
            self.assertEqual(accepted, 1)
            self.assert_bits(actual, baseline, f"compiled pinned original, n={n}")
        for r in range(4):
            products = [rows[r][j] * values[j] * scales[r][j // 128] *
                        activation_scales[j // 32] for j in range(n)]
            dense = math.fsum(products)
            magnitude = math.fsum(abs(p) for p in products)
            self.assertLessEqual(abs(actual[r] - dense), 1e-5 * max(1., magnitude))
            if cancellation:
                self.assertGreater(magnitude, 0.)
                self.assertLessEqual(abs(dense), 1e-3 * magnitude)
        return actual, packed, scales, activation_scales

    def check_ptq(self, n, payloads, values, scales, activation_scales, **kwargs):
        original = bytearray()
        rows = [[] for _ in range(4)]
        for r in range(4):
            self.assertEqual(len(payloads[r]), n // 128)
            for b, payload in enumerate(payloads[r]):
                original.extend(payload)
                original.extend(struct.pack("<e", scales[r][b]))
                rows[r].extend(decode_ptq_block(payload))
        self.assertEqual(len(original), n // 128 * 4 * 28)
        return self.check_rows(rows, values, scales, activation_scales,
                               original=bytes(original), **kwargs)

    def test_layout_contract_and_all_encoded_bytes(self):
        # Every possible packed planar byte, all four planes and signed endpoints.
        values = [-128, 127, -127, 126, 0, 1, -1, 42] * 16
        for byte in range(256):
            rows = [[((byte >> (2*((j % 32) // 8))) & 3) - 1
                     for j in range(128)] for _ in range(4)]
            packet = planar_packet(rows, [[1.] for _ in range(4)])
            self.assertEqual(packet[:8], struct.pack("<4e", 1., 1., 1., 1.))
            self.assertEqual(packet[8:], bytes([byte]) * 128)
            self.check_rows(rows, values)

    def test_individual_positions_preserve_each_original_lane(self):
        # A single live weight at each offset catches plane/row/lane transposes.
        for position in range(128):
            rows = [[0] * 128 for _ in range(4)]
            for r in range(4):
                rows[r][(position + 7*r) % 128] = (-1, 1, 2, 1)[r]
            self.check_rows(rows, [((j*29) % 256)-128 for j in range(128)])

    def test_irregular_scales_full_alphabet_and_long_rows(self):
        rng = random.Random(74136)
        for n in (128, 256, 384, 640, 1024, 5120, 12288, 16384):
            with self.subTest(n=n):
                rows = [[rng.randrange(-1, 3) for _ in range(n)] for _ in range(4)]
                self.check_rows(rows, [rng.randrange(-128, 128) for _ in range(n)])

    def test_pinned_original_ptq_irregular_scales_long_rows(self):
        rng = random.Random(7428)
        for n in (128, 384, 640, 5120, 12288, 16384):
            with self.subTest(n=n):
                payloads = [[rng.randbytes(26) for _ in range(n // 128)] for _ in range(4)]
                scales = [[WEIGHT_SCALES[(r*3+b) % len(WEIGHT_SCALES)]
                           for b in range(n // 128)] for r in range(4)]
                dy = [ACTIVATION_SCALES[b % len(ACTIVATION_SCALES)]
                      for b in range(n // 32)]
                self.check_ptq(n, payloads, [rng.randrange(-128, 128) for _ in range(n)],
                               scales, dy)

    def test_cancellation_and_rounding_negative_controls(self):
        rng = random.Random(34136)
        distinguishes_unfused = distinguishes_early_reduction = False
        for n in (5120, 16384):
            with self.subTest(n=n):
                payloads, scales = [[] for _ in range(4)], [[] for _ in range(4)]
                values, dy = [], []
                for pair in range(n // 256):
                    for r in range(4):
                        payload = bytes(rng.randrange(256) for _ in range(26))
                        payloads[r].extend((payload, payload))
                        scale = WEIGHT_SCALES[(pair+r) % 4]
                        scales[r].extend((scale, scale))
                    positive = [rng.randrange(-127, 128) for _ in range(128)]
                    values.extend(positive)
                    values.extend(-v for v in positive)
                    subscales = [ACTIVATION_SCALES[j] for j in (0, 1, 2, 6)]
                    dy.extend(subscales + subscales)
                index = n - 128
                values[index] += -1 if values[index] == 127 else 1
                actual, packed, scales, dy = self.check_ptq(
                    n, payloads, values, scales, dy, cancellation=True)
                unfused = arm_lane_dot(n, packed, values, scales, dy, fused=False)
                early = arm_lane_dot(n, packed, values, scales, dy, preserve_lanes=False)
                distinguishes_unfused |= any(fp32_bits(a) != fp32_bits(b)
                                             for a, b in zip(actual, unfused))
                distinguishes_early_reduction |= any(fp32_bits(a) != fp32_bits(b)
                                                     for a, b in zip(actual, early))
        self.assertTrue(distinguishes_unfused, "Fixture must reject MUL+ADD instead of FMA")
        self.assertTrue(distinguishes_early_reduction, "Fixture must reject early lane reduction")

    def test_exact_input_output_bounds(self):
        rng = random.Random(74128)
        for n in (128, 384, 16384):
            with self.subTest(n=n):
                self.check_rows([[rng.randrange(-1, 3) for _ in range(n)] for _ in range(4)],
                                [rng.randrange(-128, 128) for _ in range(n)], guarded=True)

    def test_bounds_hooks_reject_truncated_inputs(self):
        # Negative control: prove broad harness mappings cannot mask overreads.
        weights = planar_packet([[1] * 128 for _ in range(4)], [[1.] for _ in range(4)])
        activations = activation_packet([127] * 128, [1.] * 4)
        for w, a, label in ((weights[:-1], activations, "weights"),
                            (weights, activations[:-1], "activations")):
            with self.subTest(truncated=label):
                with self.assertRaisesRegex(AssertionError, label + " out of bounds"):
                    execute_guarded(self.runner, 128, w, a)

    def test_invalid_widths_touch_no_memory(self):
        for n in (-(1 << 31), -16384, -129, -128, -1, 1, 31, 32, 127, 129, 255,
                  (1 << 31)-1):
            with self.subTest(n=n):
                result, accesses = execute_guarded(self.runner, n)
                self.assertEqual(result, (0, (12345.,) * 4))
                self.assertEqual(accesses, [])

    def test_empty_dot_only_writes_four_positive_zeros(self):
        result, accesses = execute_guarded(self.runner, 0)
        self.assertEqual(result[0], 1)
        self.assert_bits(result[1], (0.,) * 4, "empty")
        self.assertTrue(accesses)
        self.assertTrue(all(a[0] == "output" and a[1] == UC_MEM_WRITE for a in accesses))


if __name__ == "__main__":
    unittest.main()
