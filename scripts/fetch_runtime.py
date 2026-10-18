#!/usr/bin/env python3
"""Fetch the exact PrismML runtime used by Bonsai Offline. Build-time only."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO = "https://github.com/PrismML-Eng/llama.cpp.git"
TAG = "prism-b10777-e3de715"
COMMIT = "e3de715222b2667c54a267441159a5729f11155e"
DEST = ROOT / "vendor" / "llama.cpp"


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def main():
    DEST.parent.mkdir(exist_ok=True)
    if DEST.is_dir() and not any(DEST.iterdir()):
        DEST.rmdir()  # An uninitialized submodule leaves an empty directory.
    if not DEST.exists():
        subprocess.run(["git", "clone", "--depth", "1", "--branch", TAG, REPO, str(DEST)], check=True)
    if git("-C", str(DEST), "rev-parse", "HEAD") != COMMIT:
        raise RuntimeError(f"Runtime revision differs from {COMMIT}. Move {DEST} aside and run again.")
    if git("-C", str(DEST), "status", "--porcelain"):
        raise RuntimeError("Runtime checkout contains local changes. Use an unmodified checkout.")
    if not (DEST / "include" / "llama.h").is_file():
        raise RuntimeError("Incomplete runtime checkout.")
    print(f"PrismML {TAG} verified ({COMMIT})")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Runtime setup failed: {error}", file=sys.stderr)
        sys.exit(1)
