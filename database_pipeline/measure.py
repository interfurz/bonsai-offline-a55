"""Run an actual pipeline command and persist host-only resource evidence."""
import json
from pathlib import Path
import platform
import subprocess
import time
import psutil

from .common import atomic_json


def measured(command, report, *, cwd=None, disk_paths=()):
    report = Path(report)
    report.parent.mkdir(parents=True, exist_ok=True)
    if report.exists():
        raise FileExistsError(report)
    peak_rss, peak_disk, samples = 0, 0, 0
    started = time.monotonic()
    with report.with_suffix(".stdout.log").open("w", encoding="utf-8") as out, report.with_suffix(".stderr.log").open("w", encoding="utf-8") as err:
        process = subprocess.Popen(command, cwd=cwd, stdout=out, stderr=err)
        parent = psutil.Process(process.pid)
        while process.poll() is None:
            try:
                total = parent.memory_info().rss
                for child in parent.children(recursive=True):
                    try:
                        total += child.memory_info().rss
                    except psutil.Error:
                        pass
                peak_rss = max(peak_rss, total)
            except psutil.Error:
                pass
            peak_disk = max(peak_disk, sum(p.stat().st_size for name in disk_paths
                            for p in Path(name).parent.glob(Path(name).name + "*") if p.is_file()))
            samples += 1
            time.sleep(.1)
    result = dict(command=command, platform=platform.platform(), exit_code=process.returncode,
                  wall_seconds=time.monotonic() - started, sampled_tree_peak_rss_sum_bytes=peak_rss,
                  sampled_disk_peak_bytes=peak_disk, samples=samples, sample_seconds=.1,
                  scope="Windows host; RSS sums can count shared pages repeatedly; sampled peaks may be missed; no A55 claim")
    atomic_json(report, result)
    if process.returncode:
        raise RuntimeError(f"Command failed ({process.returncode}); see {report.with_suffix('.stderr.log')}")
    return result
