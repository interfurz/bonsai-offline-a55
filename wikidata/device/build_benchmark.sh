#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
./gradlew --no-daemon --max-workers=2 :app:compileDebugJavaWithJavac
output=work/wikidata-device
classes=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
mkdir -p "$output/classes" "$output/dex"
javac -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar:$classes" \
  -d "$output/classes" wikidata/device/WikidataBenchmark.java
jar --create --file "$output/input.jar" -C "$classes" . -C "$output/classes" .
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 28 \
  --lib "$ANDROID_HOME/platforms/android-35/android.jar" \
  --output "$output/dex" "$output/input.jar"
jar --create --file "$output/wikidata-benchmark.jar" -C "$output/dex" classes.dex
printf 'Built %s/%s/wikidata-benchmark.jar\n' "$root" "$output"
