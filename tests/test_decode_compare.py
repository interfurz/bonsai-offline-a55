import copy
import importlib.util
import struct
import tempfile
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("compare_decode", ROOT / "benchmark/compare_decode.py")
assert spec is not None and spec.loader is not None
compare_decode = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare_decode)


def fixture(latency=10.0):
    """Synthetic test fixture only, never a measured performance result."""
    return {"schema": 1, "benchmark": "bonsai-fixed-decode-v1", "model_path": "fixture.gguf",
            "model_bytes": 4096, "model_parameters": 128, "context": 4096,
            "threads": 4, "batch_threads": 4, "micro_batch": 512, "flash_attention": False,
            "kv_type": "f16", "repack": True, "prompt_tokens": [1, 2],
            "decode_tokens": 4, "vocab_size": 128, "warmup_steps": 4,
            "runs": [{"latency_ms": [latency] * 4, "top_tokens": [7] * 4,
                      "signature": [0.25] * 256}]}


class DecodeComparisonTests(unittest.TestCase):
    def test_full_logit_comparison_rejects_truncation_nonfinite_and_changed_values(self):
        with tempfile.TemporaryDirectory() as temp:
            a, b = Path(temp) / "a.f32", Path(temp) / "b.f32"
            a.write_bytes(struct.pack("<4f", 0.25, 0.5, -1.0, 2.0))
            b.write_bytes(a.read_bytes())
            self.assertTrue(compare_decode.compare_full_logits(a, b, 4)["bitwise_equal"])
            for data in (b"", b"x", struct.pack("<4f", 0.25, 0.5, -1.0, float("nan")),
                         struct.pack("<4f", 0.25, 0.5, -1.0, 3.0)):
                b.write_bytes(data)
                with self.assertRaises(ValueError):
                    compare_decode.compare_full_logits(a, b, 4)

    def test_computes_speedup_from_actual_trial_timings(self):
        a, b = fixture(10), fixture(5)
        a["median_tokens_per_second"] = 999999  # Do not trust declared totals.
        result = compare_decode.compare(a, b)
        self.assertEqual(result["speedup"], 2)
        self.assertTrue(result["target_met"])

    def test_does_not_claim_target_for_subthreshold_result(self):
        self.assertFalse(compare_decode.compare(fixture(10), fixture(6))["target_met"])

    def test_batches_include_flanking_baseline_not_best_pair(self):
        self.assertTrue(compare_decode.compare(fixture(12),fixture(5))["target_met"])
        result = compare_decode.compare_batches([fixture(12),fixture(6)],[fixture(5),fixture(5)])
        self.assertFalse(result["target_met"])
        self.assertEqual(result["baseline_trials"],2)
        self.assertEqual(result["candidate_trials"],2)
        self.assertEqual(result["baseline_tokens_per_second_range"],[1000/12,1000/6])

    def test_batches_validate_every_report(self):
        bad = fixture()
        bad["threads"] = 8
        for baselines,candidates in (([],[fixture()]),([fixture()],[]),
                                     ([fixture(),bad],[fixture()]),([fixture()],[fixture(),bad])):
            with self.assertRaises(ValueError):
                compare_decode.compare_batches(baselines,candidates)

    def test_batches_allow_experimental_settings_to_differ_between_variants(self):
        a, b = fixture(10), fixture(5)
        a.update(app_cpu_wrappers=False, persistent_cpu_pool=False, cpu_pool_poll=None,
                 ptq_recode=False, system_info="baseline CPU features")
        b.update(app_cpu_wrappers=True, persistent_cpu_pool=True, cpu_pool_poll=0,
                 ptq_recode=True, system_info="candidate CPU features")
        self.assertTrue(compare_decode.compare(a, b)["target_met"])
        result = compare_decode.compare_batches([a, copy.deepcopy(a)], [b, copy.deepcopy(b)])
        self.assertTrue(result["target_met"])
        self.assertEqual(result["baseline_processes"], 2)
        self.assertEqual(result["candidate_processes"], 2)

    def test_batches_reject_setting_changes_within_either_variant(self):
        settings = {"app_cpu_wrappers": (False, True), "persistent_cpu_pool": (False, True),
                    "cpu_pool_poll": (None, 0), "ptq_recode": (False, True),
                    "ptq_recode_kernel": ("avx512-vnni", "avx512-vbmi-vnni"),
                    "ptq_recode_column_tile": (False, True),
                    "ptq_recode_butterfly": (False, True),
                    "ptq_recode_dot8": (False, True),
                    "ptq_recode_avx2_only": (False, True),
                    "ptq_profile_compiled": (False, True),
                    "ptq_profile_enabled": (False, True),
                    "ptq_planar": (False, True),
                    "system_info": ("CPU A", "CPU B")}
        for key, (initial, changed) in settings.items():
            for side, label in enumerate(("baseline", "candidate")):
                with self.subTest(field=key, side=label):
                    batches = [[fixture() for _ in range(3)] for _ in range(2)]
                    for report in batches[side]:
                        report[key] = initial
                    batches[side][-1][key] = changed
                    with self.assertRaisesRegex(ValueError, f"{label} variant field: {key}"):
                        compare_decode.compare_batches(*batches)

    def test_batches_reject_partial_metadata_in_either_order_within_variant(self):
        settings = {"app_cpu_wrappers": False, "persistent_cpu_pool": False,
                    "cpu_pool_poll": None, "ptq_recode": False,
                    "ptq_recode_kernel": "disabled", "ptq_recode_column_tile": False, "ptq_planar": False, "system_info": "CPU"}
        for key, value in settings.items():
            for side, label in enumerate(("baseline", "candidate")):
                for missing_index in (0, 1):
                    with self.subTest(field=key, side=label, missing_index=missing_index):
                        batches = [[fixture(), fixture()] for _ in range(2)]
                        batches[side][1 - missing_index][key] = value
                        with self.assertRaisesRegex(ValueError, f"{label} variant field: {key}"):
                            compare_decode.compare_batches(*batches)

    def test_batches_preserve_legacy_reports_and_optional_fields_across_variants(self):
        for modern_side in (None, 0, 1):
            with self.subTest(modern_side=modern_side):
                batches = [[fixture(10), fixture(10)], [fixture(5), fixture(5)]]
                if modern_side is not None:
                    for report in batches[modern_side]:
                        report.update(app_cpu_wrappers=True, persistent_cpu_pool=True,
                                      cpu_pool_poll=0, ptq_recode=True, system_info="CPU")
                self.assertTrue(compare_decode.compare_batches(*batches)["target_met"])

    def test_batches_do_not_compare_per_process_counters_as_settings(self):
        a, b = fixture(), fixture()
        a["ptq_recode_graph_calls"], b["ptq_recode_graph_calls"] = 1, 2
        self.assertEqual(compare_decode.compare_batches([a, b], [a, b])["speedup"], 1)

    def test_api_rejects_invalid_minimum_speedup(self):
        for value in (0, -1, float("nan"), float("inf"), -float("inf"),
                      None, "2", True, False, [], 10 ** 400):
            for api in (compare_decode.compare, compare_decode.compare_batches):
                with self.subTest(value=value, api=api.__name__):
                    a, b = fixture(), fixture()
                    args = ([a], [b]) if api is compare_decode.compare_batches else (a, b)
                    with self.assertRaisesRegex(ValueError, "minimum_speedup"):
                        api(*args, minimum_speedup=value)
        for value in (0.5, 1, 2.0):
            with self.subTest(valid_value=value):
                self.assertTrue(compare_decode.compare(fixture(10), fixture(5), value)["target_met"])
                self.assertTrue(compare_decode.compare_batches([fixture(10)], [fixture(5)], value)["target_met"])

    def test_rejects_nonfinite_or_zero_derived_rates_and_speedups(self):
        for a_latency, b_latency in ((1e308, 1), (1, 5e-324),
                                     (1e300, 1e-300), (1e-300, 1e300)):
            for api in (compare_decode.compare, compare_decode.compare_batches):
                with self.subTest(a=a_latency, b=b_latency, api=api.__name__):
                    a, b = fixture(a_latency), fixture(b_latency)
                    args = ([a], [b]) if api is compare_decode.compare_batches else (a, b)
                    with self.assertRaises(ValueError):
                        api(*args)

    def test_rejects_nonfinite_median_from_finite_trial_rates(self):
        a = fixture(1e-305)
        a["runs"].append(copy.deepcopy(a["runs"][0]))
        with self.assertRaisesRegex(ValueError, "Median decode rate"):
            compare_decode.compare(a, fixture())
        with self.assertRaisesRegex(ValueError, "Median decode rate"):
            compare_decode.compare_batches([fixture(1e-305), fixture(1e-305)], [fixture()])

    def test_rejects_incompatible_workload(self):
        for key in ("model_path", "threads", "micro_batch", "context", "prompt_tokens", "decode_tokens"):
            b = fixture()
            b[key] = None
            with self.assertRaisesRegex(ValueError, key):
                compare_decode.compare(fixture(), b)

    def test_rejects_incomplete_and_nonfinite_results(self):
        for value in ([], [1], [1, 1, 1, float("nan")], [1, 1, 1, 0], [1, 1, 1, -1]):
            b = fixture()
            b["runs"][0]["latency_ms"] = value
            with self.assertRaises(ValueError):
                compare_decode.compare(fixture(), b)

    def test_rejects_changed_top_token_or_logit(self):
        for key, value in (("top_tokens", 8), ("signature", float("inf")), ("signature", 1.0)):
            b = fixture()
            b["runs"][0][key][0] = value
            with self.assertRaises(ValueError):
                compare_decode.compare(fixture(), b)

    def test_checks_all_trials_not_only_first(self):
        b = fixture()
        b["runs"].append(copy.deepcopy(b["runs"][0]))
        b["runs"][1]["top_tokens"][0] = 8
        with self.assertRaises(ValueError):
            compare_decode.compare(fixture(), b)

    def test_rejects_missing_trials_and_signatures(self):
        for key in ("runs", "signature", "top_tokens"):
            b = fixture()
            if key == "runs":
                b["runs"] = []
            else:
                b["runs"][0].pop(key)
            with self.assertRaises(ValueError):
                compare_decode.compare(fixture(), b)


if __name__ == "__main__":
    unittest.main()
