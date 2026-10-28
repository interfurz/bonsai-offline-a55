"""Compile the optional planar source on a baseline ARM ISA without DOTPROD."""
import os
from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PlanarFallbackBuildTests(unittest.TestCase):
    def test_optional_source_allows_non_dotprod_arm_build(self):
        compiler = os.environ.get("BONSAI_ARM_CC") or shutil.which("clang")
        if not compiler:
            self.skipTest("Set BONSAI_ARM_CC to clang")
        result = subprocess.run([compiler, "--target=aarch64-none-elf", "-march=armv8-a",
                                 "-DBONSAI_PTQ_PLANAR=1", "-std=c11", "-ffreestanding",
                                 "-Wall", "-Wextra", "-Werror", "-fsyntax-only",
                                 "-I", str(ROOT / "vendor/llama.cpp/ggml/src"),
                                 str(ROOT / "app/src/main/cpp/ternary_arm_planar.c")],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
