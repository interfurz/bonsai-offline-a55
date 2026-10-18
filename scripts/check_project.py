#!/usr/bin/env python3
"""Small host checks. These do not replace Gradle/NDK or a physical A55 test."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    assert not manifest.findall("uses-permission"), "Offline MVP must request no permissions"
    for xml in (ROOT / "app/src/main/res").rglob("*.xml"):
        ET.parse(xml)
    assert manifest.find("application").get("{http://schemas.android.com/apk/res/android}allowBackup") == "false"
    bridge = (ROOT / "app/src/main/java/de/bonsai/offline/NativeEngine.java").read_text()
    native = (ROOT / "app/src/main/cpp/bonsai_jni.cpp").read_text()
    for method in re.findall(r"native\s+\S+\s+(\w+)\(", bridge):
        assert f"Java_de_bonsai_offline_NativeEngine_{method}(" in native, method
    for file in [ROOT / "scripts/fetch_runtime.py", ROOT / "app/build.gradle"]:
        assert "e3de715222b2667c54a267441159a5729f11155e" in file.read_text()
    jar=ROOT / "gradle/wrapper/gradle-wrapper.jar"
    assert jar.read_bytes()[:2] == b"PK", "Gradle wrapper JAR missing"
    print("Manifest/XML, JNI names and runtime revision: passed")
    compiler=shutil.which("g++") or shutil.which("clang++")
    if not compiler:
        raise RuntimeError("C++ compiler missing; streaming tests were not run")
    with tempfile.TemporaryDirectory() as temp:
        binary=Path(temp)/"utf8-test"
        subprocess.run([compiler,"-std=c++17","-Wall","-Wextra","-Werror",str(ROOT/"tests/utf8_stream_test.cpp"),"-o",str(binary)],check=True)
        subprocess.run([str(binary)],check=True)
    print("These are host checks only. APK build and A55 hardware validation are separate.")


if __name__ == "__main__":
    main()
