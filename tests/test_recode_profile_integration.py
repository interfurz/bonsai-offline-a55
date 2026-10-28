"""Opt-in real-model integration gate; no synthetic performance reports."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.environ.get("BONSAI_PROFILE_EXE") and os.environ.get("BONSAI_TEST_MODEL"),
                     "Set BONSAI_PROFILE_EXE and BONSAI_TEST_MODEL for real-model profiling gate")
class RecodeProfileIntegrationTests(unittest.TestCase):
    def test_decode_only_profile_preserves_logits_and_exact_call_coverage(self):
        with tempfile.TemporaryDirectory(prefix="bonsai-profile-model-") as directory:
            files = {}
            for enabled in (0, 1):
                with self.subTest(enabled=enabled):
                    prefix = Path(directory) / str(enabled)
                    report_path = prefix.with_suffix(".json")
                    logits_path = prefix.with_suffix(".f32")
                    process = subprocess.run([os.environ["BONSAI_PROFILE_EXE"], os.environ["BONSAI_TEST_MODEL"],
                        "4", "4", "1", "8", str(report_path), str(logits_path), "0", str(enabled)],
                        capture_output=True, text=True, timeout=120)
                    self.assertEqual(process.returncode, 0, process.stderr[-2500:])
                    report = json.loads(report_path.read_text())
                    self.assertTrue(report["ptq_profile_compiled"])
                    self.assertEqual(report["ptq_profile_enabled"], bool(enabled))
                    profile = report["ptq_profile"]
                    self.assertEqual(profile["measurement"], "summed-worker-nanoseconds; not wall-time shares")
                    self.assertTrue(profile["decode_only"])
                    entries = profile["entries"]
                    if enabled:
                        self.assertGreater(profile["graph_calls"], 0)
                        self.assertTrue(entries)
                        samples = sum(entry["thread_samples"] for entry in entries)
                        self.assertEqual(samples, profile["graph_calls"] * 4)
                        for entry in entries:
                            self.assertEqual(entry["columns"], 1)
                            self.assertEqual(entry["threads"], 4)
                            self.assertEqual(entry["thread_samples"] % 4, 0)
                            self.assertGreater(entry["quant_ns"], 0)
                            self.assertGreater(entry["dot_ns"], 0)
                            self.assertGreaterEqual(entry["barrier_ns"], 0)
                    else:
                        self.assertEqual(entries, [])
                        self.assertEqual(profile["graph_calls"], 0)
                    files[enabled] = logits_path.read_bytes()
            self.assertEqual(set(files), {0, 1})
            self.assertTrue(files[0])
            self.assertEqual(files[0], files[1], "Profiling changed full real-model logits")


if __name__ == "__main__":
    unittest.main()
