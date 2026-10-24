#!/usr/bin/env python3
"""Fetch the exact PrismML runtime used by Bonsai Offline. Build-time only."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO = "https://github.com/PrismML-Eng/llama.cpp.git"
TAG = "prism-b10777-e3de715"
COMMIT = "e3de715222b2667c54a267441159a5729f11155e"
DEST = ROOT / "vendor" / "llama.cpp"
PATCH = ROOT / "runtime" / "embeddinggemma2.patch"


def ensure_embeddinggemma2():
    """Apply only to a clean pin, then verify every byte and reject unrelated edits."""
    if not git("-C", str(DEST), "status", "--porcelain"):
        patch=PATCH.read_bytes().replace(b"\r\n",b"\n")
        # Context EOLs can differ under Windows autocrlf. Final byte hashes below
        # still require the exact audited sources; unrelated edits remain fatal.
        subprocess.run(["git", "-C", str(DEST), "apply", "--ignore-space-change", "--check", "-"], input=patch, check=True)
        subprocess.run(["git", "-C", str(DEST), "apply", "--ignore-space-change", "-"], input=patch, check=True)
    expected = json.loads((ROOT / "runtime" / "embeddinggemma2-files.json").read_text())
    changes = subprocess.check_output(["git", "-C", str(DEST), "status", "--porcelain"], text=True)
    paths = {line[3:] for line in changes.splitlines()}
    if paths != set(expected):
        raise RuntimeError("Runtime checkout differs from the verified EmbeddingGemma 2 backport.")
    for path, digest in expected.items():
        content = (DEST / path).read_bytes()
        if sys.platform == "win32" and hashlib.sha256(content.replace(b"\r\n", b"\n")).hexdigest() == digest:
            # Git's Windows autocrlf checkout changes source bytes, including new
            # patch files. Restore only a proven byte-equivalent LF copy; never
            # normalize an unverified modification to make it pass.
            (DEST / path).write_bytes(content.replace(b"\r\n", b"\n"))
        if hashlib.sha256((DEST / path).read_bytes()).hexdigest() != digest:
            raise RuntimeError(f"Runtime backport checksum differs: {path}")


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def main():
    DEST.parent.mkdir(exist_ok=True)
    if DEST.is_dir() and not any(DEST.iterdir()):
        DEST.rmdir()  # An uninitialized submodule leaves an empty directory.
    if not DEST.exists():
        subprocess.run(["git", "clone", "-c", "core.longpaths=true", "-c", "core.autocrlf=false", "--depth", "1", "--branch", TAG, REPO, str(DEST)], check=True)
    if git("-C", str(DEST), "rev-parse", "HEAD") != COMMIT:
        raise RuntimeError(f"Runtime revision differs from {COMMIT}. Move {DEST} aside and run again.")
    ensure_embeddinggemma2()
    if not (DEST / "include" / "llama.h").is_file():
        raise RuntimeError("Incomplete runtime checkout.")
    print(f"PrismML {TAG} + verified EmbeddingGemma 2 backport ({COMMIT})")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Runtime setup failed: {error}", file=sys.stderr)
        sys.exit(1)
