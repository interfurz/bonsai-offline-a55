#!/usr/bin/env python3
"""Compare fixed-token decode reports; never infer speed from answer length."""
import argparse
import array
import json
import math
from pathlib import Path
import statistics
import sys


# Experimental settings may differ across A/B, but not across processes of A or B.
VARIANT_METADATA = ("app_cpu_wrappers", "persistent_cpu_pool", "cpu_pool_poll",
                    "ptq_recode", "ptq_planar", "ptq_recode_kernel", "ptq_recode_column_tile",
                    "ptq_recode_butterfly", "ptq_recode_dot8", "ptq_recode_avx2_only",
                    "ptq_profile_compiled", "ptq_profile_enabled", "system_info")


def _require_positive_finite(value, label):
    try:
        valid = not isinstance(value, bool) and math.isfinite(value) and value > 0
    except (TypeError, ValueError, OverflowError):
        valid = False
    if not valid:
        raise ValueError(f"{label} must be finite and positive")


def _validate_variant_metadata(reports, label):
    # All-legacy batches remain valid. Partial metadata cannot establish stability:
    # once a field is recorded, every process on that side must record it equally.
    missing = object()
    for key in VARIANT_METADATA:
        reference = reports[0].get(key, missing)
        for index, report in enumerate(reports[1:], start=2):
            value = report.get(key, missing)
            if (reference is missing) != (value is missing) or value != reference:
                raise ValueError(f"Incompatible or missing {label} variant field: {key} (process {index})")


def compare(baseline, candidate, minimum_speedup=2.0):
    _require_positive_finite(minimum_speedup, "minimum_speedup")
    required = ("schema", "benchmark", "model_path", "model_bytes", "model_parameters",
                "context", "threads", "batch_threads", "micro_batch", "flash_attention",
                "kv_type", "repack", "prompt_tokens", "decode_tokens", "vocab_size", "warmup_steps")
    for key in required:
        if key not in baseline or key not in candidate or baseline[key] != candidate[key]:
            raise ValueError(f"Incompatible or missing benchmark field: {key}")
    if baseline["schema"] != 1 or baseline["benchmark"] != "bonsai-fixed-decode-v1":
        raise ValueError("Unsupported benchmark schema")
    all_rates = []
    reference_tokens = None
    reference_signature = None
    maximum_error = 0.0
    for report in (baseline, candidate):
        runs = report.get("runs", [])
        if not runs:
            raise ValueError("No measured trials")
        rates = []
        for run in runs:
            times = run.get("latency_ms", [])
            if len(times) != report["decode_tokens"] or any(not math.isfinite(t) or t <= 0 for t in times):
                raise ValueError("Invalid or incomplete decode timing")
            total_ms = sum(times)
            _require_positive_finite(total_ms, "Total decode timing")
            rate = len(times) * 1000 / total_ms
            _require_positive_finite(rate, "Decode rate")
            rates.append(rate)
            top = run.get("top_tokens", [])
            signature = run.get("signature", [])
            if len(top) != len(times) or len(signature) != 64 * len(times):
                raise ValueError("Incomplete correctness signature")
            if any(not isinstance(x, int) or not 0 <= x < report["vocab_size"] for x in top):
                raise ValueError("Invalid top token")
            if reference_tokens is None:
                reference_tokens, reference_signature = top, signature
            if top != reference_tokens:
                raise ValueError("Fixed-token replay changed top tokens")
            for a, b in zip(reference_signature, signature):
                if not math.isfinite(a) or not math.isfinite(b):
                    raise ValueError("Nonfinite logits signature")
                maximum_error = max(maximum_error, abs(a - b))
                if not math.isclose(a, b, abs_tol=0.002, rel_tol=0.0002):
                    raise ValueError("Logits signature differs beyond tolerance")
        median_rate = statistics.median(rates)
        _require_positive_finite(median_rate, "Median decode rate")
        all_rates.append(median_rate)
    speedup = all_rates[1] / all_rates[0]
    _require_positive_finite(speedup, "Speedup")
    return {"baseline_median_tokens_per_second": all_rates[0],
            "candidate_median_tokens_per_second": all_rates[1],
            "speedup": speedup, "minimum_speedup": minimum_speedup,
            "target_met": speedup >= minimum_speedup,
            "top_tokens_match": True, "max_signature_error": maximum_error,
            "note": "Signature checks are not a full-logit comparison. Host measurements are not A55 results."}


def compare_full_logits(baseline_path, candidate_path, expected_count):
    maximum_error = 0.0
    count = 0
    identical = True
    with Path(baseline_path).open("rb") as a_file, Path(candidate_path).open("rb") as b_file:
        while True:
            a_bytes, b_bytes = a_file.read(65536), b_file.read(65536)
            if len(a_bytes) != len(b_bytes) or len(a_bytes) % 4:
                raise ValueError("Full-logit files have incompatible lengths")
            if not a_bytes:
                break
            identical = identical and a_bytes == b_bytes
            a, b = array.array("f"), array.array("f")
            a.frombytes(a_bytes)
            b.frombytes(b_bytes)
            if sys.byteorder != "little":
                a.byteswap()
                b.byteswap()
            count += len(a)
            for x, y in zip(a, b):
                if not math.isfinite(x) or not math.isfinite(y):
                    raise ValueError("Nonfinite full logits")
                maximum_error = max(maximum_error, abs(x - y))
                if not math.isclose(x, y, abs_tol=0.002, rel_tol=0.0002):
                    raise ValueError("Full logits differ beyond tolerance")
    if count != expected_count:
        raise ValueError("Full-logit files do not cover every replay step")
    return {"compared_values": count, "bitwise_equal": identical, "max_abs_error": maximum_error}


def compare_batches(baselines, candidates, minimum_speedup=2.0):
    """Pool every validated trial, including both flanking A processes."""
    _require_positive_finite(minimum_speedup, "minimum_speedup")
    if not baselines or not candidates:
        raise ValueError("Both variants need at least one report")
    _validate_variant_metadata(baselines, "baseline")
    _validate_variant_metadata(candidates, "candidate")
    reference = baselines[0]
    for report in [*baselines, *candidates]:
        compare(reference, report, minimum_speedup)
    merged = []
    for reports in (baselines, candidates):
        merged.append({**reports[0], "runs": [run for report in reports for run in report["runs"]]})
    result = compare(*merged, minimum_speedup)
    result["baseline_processes"] = len(baselines)
    result["candidate_processes"] = len(candidates)
    for label, report in zip(("baseline", "candidate"), merged):
        rates = [len(run["latency_ms"]) * 1000 / sum(run["latency_ms"]) for run in report["runs"]]
        result[label + "_trials"] = len(rates)
        result[label + "_tokens_per_second_range"] = [min(rates), max(rates)]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--baseline-extra", type=Path, action="append", default=[],
                        help="Additional A report; repeat for each process (all trials are pooled)")
    parser.add_argument("--candidate-extra", type=Path, action="append", default=[],
                        help="Additional B report; repeat for each process (all trials are pooled)")
    parser.add_argument("--minimum-speedup", type=float, default=2.0)
    parser.add_argument("--require-target", action="store_true")
    parser.add_argument("--baseline-logits", type=Path)
    parser.add_argument("--candidate-logits", type=Path)
    args = parser.parse_args()
    if not math.isfinite(args.minimum_speedup) or args.minimum_speedup <= 0:
        parser.error("minimum speedup must be finite and positive")
    if bool(args.baseline_logits) != bool(args.candidate_logits):
        parser.error("provide both full-logit files, or neither")
    try:
        baseline = json.loads(args.baseline.read_text())
        candidate = json.loads(args.candidate.read_text())
        baseline_reports = [baseline, *[json.loads(path.read_text()) for path in args.baseline_extra]]
        candidate_reports = [candidate, *[json.loads(path.read_text()) for path in args.candidate_extra]]
        result = compare_batches(baseline_reports, candidate_reports, args.minimum_speedup)
        if args.baseline_logits:
            result["full_logits"] = compare_full_logits(args.baseline_logits, args.candidate_logits,
                                                        baseline["decode_tokens"] * baseline["vocab_size"])
            result["note"] = "Full logits checked within tolerance. Host measurements are not A55 results."
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f"Comparison rejected: {error}\n")
    print(json.dumps(result, indent=2, allow_nan=False))
    return 1 if args.require_target and not result["target_met"] else 0


if __name__ == "__main__":
    sys.exit(main())
