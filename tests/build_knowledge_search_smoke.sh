#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
./gradlew --no-daemon --max-workers=2 :app:compileDebugJavaWithJavac
output=build/knowledge-search-smoke
classes=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
mkdir -p "$output/classes" "$output/dex"
javac -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar:$classes" \
  -d "$output/classes" tests/KnowledgeSearchSmoke.java
jar --create --file "$output/input.jar" -C "$classes" . -C "$output/classes" .
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 28 \
  --lib "$ANDROID_HOME/platforms/android-35/android.jar" \
  --output "$output/dex" "$output/input.jar"
jar --create --file "$output/knowledge-search-smoke.jar" -C "$output/dex" classes.dex
printf 'Built %s/%s/knowledge-search-smoke.jar\n' "$root" "$output"
