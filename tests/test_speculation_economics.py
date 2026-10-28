"""Economics unit fixtures are synthetic; they are never performance results."""
import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("speculation_economics", ROOT / "benchmark/speculation_economics.py")
assert spec is not None and spec.loader is not None
economics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(economics)


def fixture():
    return {"process_count": 1, "timing_protocol": "immediate-same-shape-warmup-v2",
            "configuration_excluding_tested_flag": {"n_rs_seq_actual": 8},
            "raw_reports": [{"path": "synthetic-unit-fixture.json", "column_tile": False,
                "timing_runs": [
                    {"verify_width": 1, "median_pass_ms": 10.0, "pass_ms": [9.0, 11.0]},
                    {"verify_width": 1, "median_pass_ms": 10.0, "pass_ms": [10.0]},
                    {"verify_width": 2, "median_pass_ms": 18.0, "pass_ms": [18.0]},
                    {"verify_width": 4, "median_pass_ms": 30.0, "pass_ms": [29.0, 31.0]}]}]}


class SpeculationEconomicsTests(unittest.TestCase):
    def test_expected_tokens_includes_one_correction_or_bonus_token(self):
        self.assertEqual(economics.expected_tokens(0, 0.0), 1.0)
        self.assertEqual(economics.expected_tokens(3, 0.0), 1.0)
        self.assertEqual(economics.expected_tokens(3, 1.0), 4.0)
        self.assertEqual(economics.expected_tokens(3, 0.5), 1.875)

    def test_costs_and_acceptance_are_explicit_assumptions(self):
        result = economics.scenario(1, 0.8, 1.5, 0.3)
        self.assertAlmostEqual(result["hypothetical_speedup"], 1.0)
        self.assertTrue(result["hypothetical_not_measured"])
        self.assertIsNone(result["measured_acceptance"])
        self.assertEqual(result["ideal_full_acceptance_ceiling"], 2.0 / 1.8)

    def test_break_even_can_be_impossible_or_an_endpoint(self):
        self.assertIsNone(economics.break_even_acceptance(1, 2.1))
        self.assertEqual(economics.break_even_acceptance(1, 2.0), 1.0)
        self.assertEqual(economics.break_even_acceptance(1, 0.9), 0.0)
        self.assertAlmostEqual(economics.break_even_acceptance(1, 1.8), 0.8)

    def test_rejects_invalid_inputs_not_infinite_or_boolean_results(self):
        for invalid in (-1.0, 1.1, float("inf"), float("nan"), True, None):
            with self.subTest(acceptance=invalid), self.assertRaises(ValueError):
                economics.expected_tokens(2, invalid)
        for invalid in (-1, 1.5, True, 4097):
            with self.subTest(draft_tokens=invalid), self.assertRaises(ValueError):
                economics.expected_tokens(invalid, 0.5)
        for invalid in (0.0, -1.0, float("nan"), float("inf"), True):
            with self.subTest(cost=invalid), self.assertRaises(ValueError):
                economics.scenario(1, 0.5, invalid)
        with self.assertRaises(ValueError):
            economics.scenario(1, 0.5, 1.0, -0.1)
        with self.assertRaises(ValueError):
            economics.scenario(1, 0.5, 1e308, 1e308)

    def test_analyzes_each_process_without_mixing_feature_variants(self):
        result = economics.analyze_report(fixture())
        self.assertEqual(len(result["processes"]), 1)
        cases = result["processes"][0]["widths"]
        self.assertEqual([case["verify_width"] for case in cases], [2, 4])
        self.assertEqual(cases[0]["normalized_verify_cost"], 1.8)
        self.assertEqual(cases[0]["draft_tokens_assumed"], 1)
        self.assertIsNone(cases[0]["measured_accepted_tokens_per_second"])
        self.assertIn("not a universal bound", result["limitations"])

    def test_rejects_bad_protocol_counts_duplicates_or_unverified_medians(self):
        for mutate in (
            lambda x: x.update(timing_protocol="old"),
            lambda x: x.update(process_count=2),
            lambda x: x["raw_reports"].append(copy.deepcopy(x["raw_reports"][0])),
            lambda x: x["raw_reports"][0]["timing_runs"][2].update(median_pass_ms=17.0),
            lambda x: x["raw_reports"][0]["timing_runs"][2].update(pass_ms=[float("nan")]),
        ):
            report = fixture()
            mutate(report)
            with self.assertRaises(ValueError):
                economics.analyze_report(report)


if __name__ == "__main__":
    unittest.main()
