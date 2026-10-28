"""Real-model gate for selecting the existing AVX2 kernel in the loadcache."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


@unittest.skipUnless(all(os.environ.get(key) for key in
    ("BONSAI_KERNEL_TEST_BASE", "BONSAI_KERNEL_TEST_AVX2", "BONSAI_TEST_MODEL")),
    "Set both benchmark executable paths and BONSAI_TEST_MODEL for the real-model gate")
class HostKernelSelectionTests(unittest.TestCase):
    def test_forced_avx2_executes_without_changing_full_logits(self):
        with tempfile.TemporaryDirectory(prefix="bonsai-avx2-model-") as directory:
            outputs = []
            reports = []
            for forced, variable in ((False, "BONSAI_KERNEL_TEST_BASE"), (True, "BONSAI_KERNEL_TEST_AVX2")):
                prefix = Path(directory) / str(int(forced))
                report_path, logits_path = prefix.with_suffix(".json"), prefix.with_suffix(".f32")
                run = subprocess.run([os.environ[variable], os.environ["BONSAI_TEST_MODEL"],
                    "4", "4", "1", "8", str(report_path), str(logits_path), "0"],
                    capture_output=True, text=True, timeout=120)
                self.assertEqual(run.returncode, 0, run.stderr[-2000:])
                report = json.loads(report_path.read_text())
                self.assertGreater(report["ptq_recode_graph_calls"], 0)
                if forced:
                    self.assertEqual(report["ptq_recode_kernel"], "avx2")
                reports.append(report)
                self.assertFalse(report["ptq_profile_compiled"])
                self.assertFalse(report["ptq_recode_dot8"])
                outputs.append(logits_path.read_bytes())
            self.assertEqual(len(outputs), 2)
            self.assertEqual([r["ptq_recode_avx2_only"] for r in reports], [False, True])
            self.assertTrue(outputs[0])
            self.assertEqual(outputs[0], outputs[1], "Forced AVX2 changed complete real-model logits")


if __name__ == "__main__":
    unittest.main()
