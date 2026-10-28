"""Bit-exact compiled ARM planar integer-bias experiment; never a benchmark.

Run with BONSAI_ARM_CC pointing to clang, the existing Unicorn/pyelftools deps
on PYTHONPATH, and TMPDIR set to a private scratch directory. Inherits the
original planar regression fixtures, but every check_rows invocation compares
the candidate to BOTH the Fraction/FMA oracle and the compiled unchanged planar
kernel. Original PTQ fixtures additionally execute the pinned PTQ decoder/dot.
"""
from collections import Counter
import io
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection
import test_arm_planar as planar
from arm_kernel_runner import ArmKernelRunner, ROOT
from arm_recode_reference import PinnedArmPtqRunner


class ArmPlanarBiasTests(planar.ArmPlanarTests):
    @classmethod
    def setUpClass(cls):
        cls.runner = ArmKernelRunner("ternary_arm_planar_bias.c", "bonsai_arm_planar_bias_dot4")
        cls.addClassCleanup(cls.runner.close)
        cls.planar = ArmKernelRunner("ternary_arm_planar.c", "bonsai_arm_planar_dot4")
        cls.addClassCleanup(cls.planar.close)
        cls.original = PinnedArmPtqRunner()
        cls.addClassCleanup(cls.original.close)

    def check_rows(self, rows, values, scales=None, activation_scales=None, **kwargs):
        result = planar.ArmPlanarTests.check_rows(
            self, rows, values, scales, activation_scales, **kwargs)
        actual, _, scales, activation_scales = result
        weights = planar.planar_packet(rows, scales)
        activations = planar.activation_packet(values, activation_scales)
        accepted, baseline = self.planar.execute(len(values), weights, activations, output_count=4)
        self.assertEqual(accepted, 1)
        self.assert_bits(actual, baseline, "compiled unchanged planar")
        return result

    def test_lane_bias_exact_with_position_dependent_signed_endpoints(self):
        rows = [[((j * 3 + j // 8 + r) % 4) - 1 for j in range(128)]
                for r in range(4)]
        values = [(-128, 127, -127, 126, 0, 1, -1, 42)[j % 8]
                  if j % 3 else (j * 29 % 256) - 128 for j in range(128)]
        self.check_rows(rows, values)

    def test_layout_contract_and_all_encoded_bytes(self):
        # Override the inherited fixture: distinct activation values in every
        # plane/lane, not the same eight bytes repeated through all planes.
        values = [((j * 29 + (j // 8) * 7) % 256) - 128 for j in range(128)]
        values[0], values[4], values[8], values[12] = -128, 127, 127, -128
        for byte in range(256):
            with self.subTest(byte=byte):
                rows = [[((byte >> (2 * ((j % 32) // 8))) & 3) - 1
                         for j in range(128)] for _ in range(4)]
                packet = planar.planar_packet(rows, [[1.] for _ in range(4)])
                self.assertEqual(packet[8:], bytes([byte]) * 128)
                self.check_rows(rows, values)

    def test_each_bias_lane_and_signed_endpoint(self):
        # Zero decoded weights still dot raw ones: each lane must remove only
        # its own eight activations. This rejects a sum32 correction directly.
        rows = [[0] * 128 for _ in range(4)]
        for endpoint in (-128, 127):
            for position in range(32):
                with self.subTest(endpoint=endpoint, position=position):
                    values = [0] * 128
                    values[position] = endpoint
                    actual, _, _, _ = self.check_rows(
                        rows, values, [[1.] for _ in range(4)], [1.] * 4,
                        guarded=True)
                    self.assert_bits(actual, (0.,) * 4, "independent bias lanes")
        for endpoint in (-128, 127):
            self.check_rows([[-1] * 128, [0] * 128, [1] * 128, [2] * 128],
                            [endpoint] * 128, guarded=True)


class ArmPlanarBiasObjectTests(unittest.TestCase):
    compiler: str

    @classmethod
    def setUpClass(cls):
        compiler = os.environ.get("BONSAI_ARM_CC") or shutil.which("clang")
        if not compiler:
            raise unittest.SkipTest("Set BONSAI_ARM_CC to clang")
        cls.compiler = compiler
        cls.temp = tempfile.TemporaryDirectory(prefix="bonsai-planar-bias-object-")
        cls.addClassCleanup(cls.temp.cleanup)

    def compile_object(self, source, march):
        target = Path(self.temp.name) / (Path(source).stem + "-" + march + ".o")
        subprocess.run([
            self.compiler, "--target=aarch64-none-elf", "-march=" + march,
            "-O3", "-std=c11", "-ffreestanding", "-fno-stack-protector",
            "-Wall", "-Wextra", "-Werror",
            "-I", str(ROOT / "vendor/llama.cpp/ggml/src"),
            "-c", str(ROOT / "app/src/main/cpp" / source), "-o", str(target),
        ], check=True, capture_output=True, text=True)
        return target

    def test_baseline_arm_without_dotprod_emits_no_code_or_kernel_symbol(self):
        for source in ("ternary_arm_planar.c", "ternary_arm_planar_bias.c"):
            with self.subTest(source=source):
                target = self.compile_object(source, "armv8-a")
                elf = ELFFile(io.BytesIO(target.read_bytes()))
                text = elf.get_section_by_name(".text")
                assert text is not None, "Missing .text section"
                self.assertEqual(text["sh_size"], 0)
                symbols = elf.get_section_by_name(".symtab")
                assert isinstance(symbols, SymbolTableSection), "Missing symbol table"
                self.assertFalse(any(s.name.startswith("bonsai_arm_planar")
                                     for s in symbols.iter_symbols()))

    def test_object_has_fewer_bias_adjustments_and_unchanged_float_operations(self):
        objdump = (os.environ.get("BONSAI_ARM_OBJDUMP") or
                   shutil.which("llvm-objdump") or
                   str(Path(self.compiler).with_name(
                       "llvm-objdump.exe" if os.name == "nt" else "llvm-objdump")))
        if not Path(objdump).is_file():
            self.skipTest("Set BONSAI_ARM_OBJDUMP to llvm-objdump")
        reports = []
        for source in ("ternary_arm_planar.c", "ternary_arm_planar_bias.c"):
            target = self.compile_object(source, "armv8.2-a+dotprod")
            disassembly = subprocess.run(
                [objdump, "-d", "--no-show-raw-insn", str(target)],
                check=True, capture_output=True, text=True).stdout
            instructions = re.findall(
                r"^\s*[0-9a-f]+:\s+([a-z][a-z0-9.]*)\s*([^\n]*)",
                disassembly, re.MULTILINE)
            counts = Counter(opcode for opcode, _ in instructions)
            # Clang 19 implements original SUB-one with six ADD-byte and
            # two USRA instructions (shift-add into an all-minus-one vector).
            # Literal SUB counts alone would misleadingly say 0 -> 4.
            byte_adjustments = sum(
                (op in ("add", "sub") and re.match(r"v\d+\.16b,", args) is not None)
                or (op == "usra" and ".16b" in args)
                for op, args in instructions)
            lane_subs = sum(op == "sub" and
                            re.match(r"v\d+\.4s,", args) is not None
                            for op, args in instructions)
            text = ELFFile(io.BytesIO(target.read_bytes())).get_section_by_name(".text")
            assert text is not None, "Missing .text section"
            report = dict(counts=counts, byte_adjustments=byte_adjustments,
                          lane_subs=lane_subs, text_bytes=text["sh_size"])
            reports.append(report)
            self.assertGreater(counts["sdot"], 0, disassembly)
            self.assertFalse(set(counts) & {"tbl", "tbx", "sshl", "ushl", "srshl", "urshl"})
        baseline, candidate = reports
        self.assertGreater(baseline["byte_adjustments"], 0)
        self.assertEqual(candidate["byte_adjustments"], 0)
        self.assertGreater(candidate["lane_subs"], 0)
        self.assertLess(candidate["lane_subs"], baseline["byte_adjustments"])
        # Eight row-pair dots plus four shared activation-only dots per k,
        # not four extra activation dots recomputed for each of the row pairs.
        self.assertEqual(2 * candidate["counts"]["sdot"],
                         3 * baseline["counts"]["sdot"])
        for opcode in ("fmul", "fmla", "faddp", "scvtf"):
            self.assertGreater(baseline["counts"][opcode], 0)
            self.assertEqual(candidate["counts"][opcode], baseline["counts"][opcode])
        summary = [dict(
            source=source, byte_bias_ops=report["byte_adjustments"],
            lane_subs=report["lane_subs"], text_bytes=report["text_bytes"],
            opcodes={op: report["counts"][op] for op in
                     ("sub", "subs", "usra", "sdot", "fmul", "fmla", "faddp")},
        ) for source, report in zip(("original planar", "integer bias"), reports)]
        print("\nObject-only inspection (not timing):", summary, flush=True)


if __name__ == "__main__":
    unittest.main()
