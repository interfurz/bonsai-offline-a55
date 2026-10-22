"""Exercise the tracked patch on the pinned source, without network or model weights."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("fetch_runtime", ROOT / "scripts/fetch_runtime.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeBackportTests(unittest.TestCase):
    def test_clean_application_repeat_verification_and_rejection_of_edits(self):
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp)
            for path in json.loads((ROOT / "runtime/embeddinggemma2-files.json").read_text()):
                source = subprocess.run(["git", "-C", str(ROOT / "vendor/llama.cpp"),
                                         "show", f"{runtime.COMMIT}:{path}"], capture_output=True)
                if source.returncode == 0:
                    target = destination / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(source.stdout)
            def git(*args):
                subprocess.run(["git", "-C", str(destination), *args], check=True, capture_output=True)
            git("init")
            git("add", ".")
            git("-c", "user.name=Runtime Test", "-c", "user.email=test@example.invalid", "commit", "-m", "base")
            previous = runtime.DEST
            runtime.DEST = destination
            try:
                runtime.ensure_embeddinggemma2()
                runtime.ensure_embeddinggemma2()
                extra = destination / "unrelated.txt"
                extra.write_text("unrelated edit")
                with self.assertRaisesRegex(RuntimeError, "differs"):
                    runtime.ensure_embeddinggemma2()
                extra.unlink()
                with (destination / "src/models/gemma-embedding2.cpp").open("a") as out:
                    out.write("// unexpected edit\n")
                with self.assertRaisesRegex(RuntimeError, "checksum"):
                    runtime.ensure_embeddinggemma2()
            finally:
                runtime.DEST = previous


if __name__ == "__main__":
    unittest.main()
