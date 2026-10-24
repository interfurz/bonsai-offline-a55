import importlib.metadata
import json
import platform
import shutil
import sqlite3
import subprocess
import sys
from pathlib import Path

from .common import atomic_json


def preflight(root):
    root = Path(root).resolve()
    for name in ("downloads", "raw", "staging", "embeddings", "indexes", "packages",
                 "manifests", "checkpoints", "logs", "benchmarks"):
        (root / name).mkdir(parents=True, exist_ok=True)
    usage = shutil.disk_usage(root)
    result = dict(platform=platform.platform(), python=sys.version, executable=sys.executable,
                  data_root=str(root), disk_free_bytes=usage.free, disk_total_bytes=usage.total,
                  sqlite=sqlite3.sqlite_version, packages={})
    for name in ("mwparserfromhell", "numpy", "torch", "transformers", "sentence-transformers", "faiss-cpu", "psutil"):
        try:
            result["packages"][name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            result["packages"][name] = None
    with sqlite3.connect(":memory:") as db:
        result["fts"] = {}
        for module in ("fts4", "fts5"):
            try:
                db.execute(f"CREATE VIRTUAL TABLE test_{module} USING {module}(text)")
                result["fts"][module] = True
            except sqlite3.Error:
                result["fts"][module] = False
    try:
        import psutil
        result["ram"] = psutil.virtual_memory()._asdict()
    except ImportError:
        pass
    try:
        import torch
        result["torch"] = dict(version=torch.__version__, cuda_build=torch.version.cuda,
                               cuda_available=torch.cuda.is_available())
        if torch.cuda.is_available():
            free, total = torch.cuda.mem_get_info()
            # Execute CUDA work, not just a driver availability query.
            value = (torch.ones((64, 64), device="cuda") @ torch.ones((64, 64), device="cuda"))[0, 0].item()
            result["torch"].update(device=torch.cuda.get_device_name(0), free_vram_bytes=free,
                                    total_vram_bytes=total, matmul_check=value == 64)
    except Exception as exc:
        result["torch_error"] = repr(exc)
    for name, command in (("nvidia", ["nvidia-smi", "--query-gpu=name,driver_version,memory.total,memory.used", "--format=csv"]),
                          ("adb", ["adb", "devices", "-l"]), ("git", ["git", "status", "--short", "--branch"])):
        try:
            run = subprocess.run(command, capture_output=True, text=True, timeout=30)
            result[name] = dict(exit_code=run.returncode, stdout=run.stdout, stderr=run.stderr)
        except (OSError, subprocess.TimeoutExpired) as exc:
            result[name] = dict(error=repr(exc))
    atomic_json(root / "benchmarks" / "preflight.json", result)
    return result
