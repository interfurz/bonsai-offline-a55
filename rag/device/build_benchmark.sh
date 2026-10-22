#!/usr/bin/env bash
set -euo pipefail
# Build only. Does not install/run anything or require an APK/native model library.
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
: "${ANDROID_HOME:?Set ANDROID_HOME to your SDK directory}"
./gradlew --no-daemon --max-workers=2 :app:compileDebugJavaWithJavac
output=rag/output/device-benchmark
classes=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
mkdir -p "$output/classes" "$output/dex"
javac -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar:$classes" \
  -d "$output/classes" rag/device/VectorBenchmark.java
jar --create --file "$output/input.jar" -C "$classes" . -C "$output/classes" .
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 28 \
  --lib "$ANDROID_HOME/platforms/android-35/android.jar" \
  --output "$output/dex" "$output/input.jar"
jar --create --file "$output/vector-benchmark.jar" -C "$output/dex" classes.dex
printf 'Built %s/%s/vector-benchmark.jar\n' "$root" "$output"
