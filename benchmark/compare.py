#!/usr/bin/env python3
"""Compare two exported bonsai-chat-5-v1 runs. No third-party dependencies."""
import argparse
import json


def compare(left, right):
    for report in (left, right):
        if report.get("benchmark") != "bonsai-chat-5-v1" or not report.get("completed"):
            raise ValueError("Both exports must be completed bonsai-chat-5-v1 runs")
        if len(report.get("turns", [])) != 5:
            raise ValueError("Expected five turns")
    fixed = ("model_sha256", "context_tokens", "seed", "max_output_tokens", "reasoning_budget", "gbnf", "prefix_warmup", "runtime_commit")
    mismatches = [key for key in fixed if key not in left or key not in right or left[key] != right[key]]
    if mismatches:
        raise ValueError("Incomparable settings: " + ", ".join(mismatches))
    rows = []
    for a, b in zip(left["turns"], right["turns"]):
        if a["question"] != b["question"] or a["turn"] != b["turn"]:
            raise ValueError("Questions or turn order differ")
        row = {"turn": a["turn"]}
        for key in ("ttft_ms", "tokens_per_second", "reused_prompt_tokens", "decoded_prompt_tokens", "reasoning_tokens", "prefill_ms", "cleanup_ms", "commit_ms", "turn_ms", "thermal_status_before", "thermal_status_after", "battery_celsius_before", "battery_celsius_after"):
            x, y = a.get(key), b.get(key)
            row[key] = {"a": x, "b": y, "delta_b_minus_a": y-x if isinstance(x, (int, float)) and isinstance(y, (int, float)) else None}
        rows.append(row)
    return {"a": {k: left.get(k) for k in ("backend", "requested_profile", "app_version", "timestamp_utc", "device")},
            "b": {k: right.get(k) for k in ("backend", "requested_profile", "app_version", "timestamp_utc", "device")},
            "turns": rows,
            "notes": "Delta B minus A. Answers and reasoning lengths can differ across backends; inspect exports. Temperature/status observations alone do not establish thermal causation."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("a")
    parser.add_argument("b")
    args = parser.parse_args()
    try:
        with open(args.a, encoding="utf-8") as first, open(args.b, encoding="utf-8") as second:
            result = compare(json.load(first), json.load(second))
    except (ValueError, KeyError) as error:
        parser.error(str(error))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
