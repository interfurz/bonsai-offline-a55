#!/usr/bin/env python3
"""Conditional speculative-cost scenarios; never measured accepted-token speedups.

A scenario assumes a fixed iid per-draft acceptance alpha and gamma draft tokens,
plus one correction/bonus token. Verify cost and *all* overhead are normalized to
one serial target decode. Mapping a batch width to gamma is an explicit hypothesis,
not a property of a width-only benchmark.
"""
import argparse
import json
import math
from pathlib import Path
import statistics


def _number(value, label, minimum, maximum=None, strict=False):
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError(f"{label} must be a finite number")
    try:
        valid = math.isfinite(value) and (value > minimum if strict else value >= minimum)
        valid = valid and (maximum is None or value <= maximum)
    except (OverflowError, TypeError):
        valid = False
    if not valid:
        raise ValueError(f"Invalid {label}")
    return value


def expected_tokens(draft_tokens, acceptance):
    if type(draft_tokens) is not int or not 0 <= draft_tokens <= 4096:
        raise ValueError("draft_tokens must be an integer in [0,4096]")
    _number(acceptance, "acceptance", 0.0, 1.0)
    return math.fsum(acceptance ** i for i in range(draft_tokens + 1))


def scenario(draft_tokens, acceptance, verify_cost_ratio, overhead_ratio=0.0):
    expected = expected_tokens(draft_tokens, acceptance)
    _number(verify_cost_ratio, "verify_cost_ratio", 0.0, strict=True)
    _number(overhead_ratio, "overhead_ratio", 0.0)
    cost = verify_cost_ratio + overhead_ratio
    _number(cost, "total_cost_ratio", 0.0, strict=True)
    speedup = expected / cost
    _number(speedup, "hypothetical_speedup", 0.0, strict=True)
    return {"draft_tokens_assumed": draft_tokens, "acceptance_assumed": acceptance,
            "expected_tokens_assumed": expected, "normalized_verify_cost": verify_cost_ratio,
            "overhead_ratio_assumed": overhead_ratio, "hypothetical_speedup": speedup,
            "ideal_full_acceptance_ceiling": (draft_tokens + 1) / cost,
            "hypothetical_not_measured": True, "measured_acceptance": None}


def break_even_acceptance(draft_tokens, verify_cost_ratio, overhead_ratio=0.0):
    low_case = scenario(draft_tokens, 0.0, verify_cost_ratio, overhead_ratio)
    high_case = scenario(draft_tokens, 1.0, verify_cost_ratio, overhead_ratio)
    if low_case["hypothetical_speedup"] >= 1.0:
        return 0.0
    if high_case["hypothetical_speedup"] < 1.0:
        return None
    if high_case["hypothetical_speedup"] == 1.0:
        return 1.0
    lo, hi = 0.0, 1.0
    cost = verify_cost_ratio + overhead_ratio
    for _ in range(64):
        mid = (lo + hi) / 2
        if expected_tokens(draft_tokens, mid) < cost:
            lo = mid
        else:
            hi = mid
    return hi


def analyze_report(report):
    if report.get("timing_protocol") != "immediate-same-shape-warmup-v2":
        raise ValueError("Unsupported or unverified timing protocol")
    processes = report.get("raw_reports", [])
    declared_count = report.get("process_count")
    if type(declared_count) is not int or not processes or declared_count != len(processes):
        raise ValueError("Missing/incomplete process count")
    seen = set()
    analyzed = []
    for process in processes:
        path = process.get("path")
        if not isinstance(path, str) or not path or path in seen:
            raise ValueError("Invalid/duplicate process path")
        seen.add(path)
        grouped = {}
        for run in process.get("timing_runs", []):
            width = run.get("verify_width")
            if type(width) is not int or not 1 <= width <= 4097:
                raise ValueError("Invalid verify width")
            times = run.get("pass_ms", [])
            if not times:
                raise ValueError("Missing measured pass timings")
            for value in times:
                _number(value, "pass_ms", 0.0, strict=True)
            measured = statistics.median(times)
            declared = _number(run.get("median_pass_ms"), "median_pass_ms", 0.0, strict=True)
            if not math.isclose(measured, declared, rel_tol=1e-12, abs_tol=1e-12):
                raise ValueError("Declared median disagrees with measured passes")
            grouped.setdefault(width, []).append(measured)
        if 1 not in grouped or len(grouped) < 2:
            raise ValueError("Need serial flanks and at least one wider pass")
        serial = statistics.median(grouped[1])
        cases = []
        for width in sorted(grouped):
            if width == 1:
                continue
            ratio = statistics.median(grouped[width]) / serial
            gamma = width - 1
            cases.append({"verify_width": width, "draft_tokens_assumed": gamma,
                          "normalized_verify_cost": ratio,
                          "serial_pass_ms": serial, "wide_pass_ms": statistics.median(grouped[width]),
                          "break_even_acceptance_assuming_zero_overhead": break_even_acceptance(gamma, ratio),
                          "scenarios": [scenario(gamma, alpha, ratio) for alpha in (0.7, 0.8, 0.9, 1.0)],
                          "measured_accepted_tokens_per_second": None})
        analyzed.append({"path": path, "column_tile": process.get("column_tile"), "widths": cases})
    return {"schema": 1, "purpose": "Conditional scenarios calculated from existing measured pass times, no new benchmark",
            "mapping_assumed": "verify_width = one accepted anchor + gamma draft inputs; ideal emission gamma+1",
            "acceptance_model_assumed": "constant independent per-draft alpha; not measured on this model",
            "overhead_model_assumed": "zero draft, rollback, sampling and snapshot-baseline differential cost",
            "source_timing_protocol": report["timing_protocol"],
            "source_configuration": report.get("configuration_excluding_tested_flag", {}),
            "processes": analyzed,
            "limitations": ["not a universal bound",
                "No accepted-token throughput or acceptance probabilities were measured",
                "Width-to-draft/anchor mapping and bonus-token convention require an actual sampler implementation",
                "Short host runs with snapshots enabled; no snapshot-free serial baseline or A55 timing",
                "Different column-tile variants are kept separate per process",
                "Low batch scaling does not prove compute limitation or measured DRAM bandwidth",
                "A less than 2x extra speedup does not rule out 2x overall relative to original PTQ",
                "Historical ratios must not be multiplied to claim final target attainment"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding="utf-8"))
    print(json.dumps(analyze_report(report), indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
