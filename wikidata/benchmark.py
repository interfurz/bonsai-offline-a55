"""Small host-only measurements; never an A55 or full-dump claim."""
import argparse
import json
from pathlib import Path
import platform
import resource
import sqlite3
import statistics
import subprocess
import sys
import tempfile
import time
import tracemalloc
from .fixtures import write_dump
from .pipeline import build


def measure(source, output):
    tracemalloc.start()
    start = time.perf_counter()
    build(source, output, dump_date="2026-10-01", batch=20)
    build_seconds = time.perf_counter() - start
    _, allocation_peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    queries = {
        "capital": ("SELECT target FROM statement WHERE subject=? AND property=? AND rank>0", (17, -36)),
        "birthplace_country_capital": ("SELECT c.target FROM statement a JOIN statement b ON b.subject=a.target "
            "JOIN statement c ON c.subject=b.target WHERE a.subject=? AND a.property=? AND b.property=? AND c.property=?",
            (937, -19, -17, -36)),
        "atomic_number_over_50": ("SELECT subject,amount FROM statement WHERE property=? AND unit=0 AND kind='quantity' AND number>? ORDER BY number DESC", (-1086, 50))}
    with sqlite3.connect(f"file:{output}?mode=ro", uri=True) as db:
        timings = {}
        for name, (sql, args) in queries.items():
            for _ in range(3):
                list(db.execute(sql, args))
            values = []
            for _ in range(100):
                start = time.perf_counter_ns()
                result = list(db.execute(sql, args))
                values.append((time.perf_counter_ns()-start)/1e6)
            timings[name] = {"p50_ms": statistics.median(values), "p95_ms": sorted(values)[94],
                             "results": result, "plan": list(db.execute("EXPLAIN QUERY PLAN "+sql, args))}
        counts = {table: db.execute("SELECT COUNT(*) FROM "+table).fetchone()[0]
                  for table in ("entity", "term", "statement")}
    return {"environment": {"os": platform.platform(), "python": platform.python_version(),
                             "sqlite": sqlite3.sqlite_version, "machine": platform.machine()},
            "scope": "Small authored fixture; host Python/SQLite only. No network or model inference. Not A55.",
            "dump_bytes": source.stat().st_size, "database_bytes": output.stat().st_size,
            "build_seconds": build_seconds, "python_traced_peak_bytes": allocation_peak,
            "process_peak_rss_bytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024,
            "memory_note": "Linux subprocess lifetime peak RSS; Python tracemalloc excludes SQLite C allocations",
            "counts": counts, "queries": timings, "runs_per_query": 100, "warmup": 3}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("work/wikidata-benchmark.json"))
    parser.add_argument("--worker", nargs=2, metavar=("SOURCE", "DATABASE"))
    args = parser.parse_args()
    if args.worker:
        print(json.dumps(measure(*map(Path, args.worker)), ensure_ascii=False))
        return
    # Fresh process makes the RSS measurement independent of the caller's high-water mark.
    with tempfile.TemporaryDirectory() as temp:
        source, output = Path(temp)/"entities.json", Path(temp)/"knowledge.sqlite"
        write_dump(source)
        completed = subprocess.run([sys.executable, "-m", "wikidata.benchmark", "--worker", str(source), str(output)],
                                   check=True, capture_output=True, text=True)
        result = json.loads(completed.stdout)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n")
    print(args.output)


if __name__ == "__main__":
    main()
