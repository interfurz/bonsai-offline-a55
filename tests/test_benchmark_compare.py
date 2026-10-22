import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("compare", Path(__file__).parents[1]/"benchmark/compare.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class CompareTest(unittest.TestCase):
    def fixture(self):
        report = dict(benchmark="bonsai-chat-5-v1", completed=True, model_sha256="fixture", context_tokens=4096,
                      seed=42, max_output_tokens=128, reasoning_budget=600, gbnf=False, prefix_warmup=True, runtime_commit="fixture")
        report["turns"] = [dict(turn=i, question=str(i), ttft_ms=100, tokens_per_second=10, battery_celsius_before=None) for i in range(1,6)]
        return report
    def test_delta_and_missing_temperature(self):
        left = self.fixture(); right = copy.deepcopy(left); right["turns"][0]["ttft_ms"] = 75
        result = module.compare(left, right)["turns"][0]
        self.assertEqual(-25, result["ttft_ms"]["delta_b_minus_a"])
        self.assertIsNone(result["battery_celsius_before"]["delta_b_minus_a"])
    def test_rejects_mismatches_and_partial_runs(self):
        for field, value in (("completed",False),("context_tokens",8192),("gbnf",True),("model_sha256","other")):
            left = self.fixture(); right = copy.deepcopy(left); right[field] = value
            with self.assertRaises(ValueError): module.compare(left,right)
    def test_rejects_question_change(self):
        left=self.fixture();right=copy.deepcopy(left);right["turns"][1]["question"]="changed"
        with self.assertRaises(ValueError): module.compare(left,right)
    def test_checks_prompt_identity_when_either_export_has_it(self):
        left=self.fixture();left["system_prompt_sha256"]="a"*64
        right=copy.deepcopy(left)
        module.compare(left,right)
        right["system_prompt_sha256"]="b"*64
        with self.assertRaisesRegex(ValueError,"system_prompt_sha256"): module.compare(left,right)
        del right["system_prompt_sha256"]
        with self.assertRaisesRegex(ValueError,"system_prompt_sha256"): module.compare(left,right)

if __name__ == "__main__": unittest.main()
