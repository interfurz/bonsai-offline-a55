import hashlib
import json
import os
from pathlib import Path
import shutil


def digest(path, algorithm="sha256"):
    result = hashlib.new(algorithm)
    with Path(path).open("rb") as stream:
        while block := stream.read(4 * 1024**2):
            result.update(block)
    return result.hexdigest()


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def require_space(path, required, reserve=5 * 1024**3):
    path = Path(path).resolve()
    while not path.exists():
        path = path.parent
    free = shutil.disk_usage(path).free
    if free < required + reserve:
        raise OSError(f"Insufficient disk: free={free}, required={required}, reserve={reserve}")
    return free


def publish(source, target):
    """Atomic no-clobber publication on the same volume, including Windows."""
    os.link(source, target)
    Path(source).unlink()
