#!/usr/bin/env python3
"""Compare native host Canvas rendering with the original v0.5.1 light renderer."""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build" / "glow-profile"
SOURCE = WORK / "src" / "de" / "bonsai" / "offline"
BASELINE = "2dfe2c4"


def main():
    SOURCE.mkdir(parents=True, exist_ok=True)
    original = subprocess.check_output(
        ["git", "show", f"{BASELINE}:app/src/main/java/de/bonsai/offline/PulsingBorder.java"],
        cwd=ROOT, text=True, encoding="utf-8",
    )
    (SOURCE / "BaselinePulsingBorder.java").write_text(
        original.replace("PulsingBorder", "BaselinePulsingBorder"), encoding="utf-8",
    )
    probe = ROOT / "docs" / "validation" / "glow" / "GlowRenderProbe.java"
    (SOURCE / probe.name).write_text(probe.read_text(encoding="utf-8"), encoding="utf-8")
    init = WORK / "probe.gradle"
    init.write_text("""allprojects {
    afterEvaluate { project ->
        if (project.path == ':app') {
            project.android.sourceSets.test.java.srcDir(rootProject.file('build/glow-profile/src'))
        }
    }
}
""", encoding="utf-8")
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([
        str(wrapper), "--max-workers=2", "--init-script", str(init),
        ":app:testReleaseUnitTest", "--tests", "de.bonsai.offline.GlowRenderProbe",
    ], cwd=ROOT, check=True)
    print(f"Host-only results and comparison images: {ROOT / 'app/build/glow-profile'}")


if __name__ == "__main__":
    main()
