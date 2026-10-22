# Turn-Statistik, inkrementeller KV-Cache und vergleichbarer Fünf-Turn-Benchmark

Bei langsamen Folgefragen fehlten bisher die sichtbaren Prefill-/Reuse-Werte und
Turn-Gesamtzeiten über Tool-Runden. Antwortdetails zeigen jetzt wiederverwendete
und neu dekodierte Prompt-Tokens sowie Prefill-Zeit. Abschaltbare `BonsaiTurn`-Logs
enthalten alle Runden, Reasoning, Cleanup, finales Commit und Cache-Reset-Gründe.
`configure()` erhält KV bei identischer Microbatch-/FA-/KV-Konfiguration und reinen
Thread-Änderungen. System-/Tool-Präfix wird nach der Modellprüfung einmal für die
aktuelle GBNF-Variante im bestehenden Kontext vorgewärmt.

Über den bisherigen Benchmark-Einstieg gibt es einen zusätzlichen verbundenen
Fünf-Fragen-Chat. JSON enthält TTFT, t/s, Tokens, Temperatur und thermischen Status
pro Turn; `benchmark/compare.py` vergleicht zwei Exporte. Flash Attention und Q8_0
für K/V sind ausdrücklich optional, Microbatch bleibt wählbar und nutzt die
vorhandenen Optimizer-Probes. Standards bleiben F16, FA aus, Vulkan-Microbatch 1,
`n_ctx=4096`. Kein zweiter KV-Cache; größere Microbatches können bewusst gewählte
zusätzliche Rechenpuffer benötigen.

## Befunde mit Datei und Zeile

Zeilenangaben beziehen sich auf die Basis `9922f14`, Version 0.4.4:

- `app/src/main/cpp/bonsai_jni.cpp:314–316`: unnötiger KV-Verlust beim erneuten
  Anwenden desselben Profils bzw. reinen Thread-Änderungen. Behoben.
- `app/src/main/java/de/bonsai/offline/BonsaiApp.java:346–350`: nur letzte Tool-Runde
  in Antwortdetails; dadurch unvollständige TTFT-/Prefill-Diagnose. Behoben.
- `app/src/main/cpp/bonsai_jni.cpp:478` zusammen mit `BonsaiApp.java:345–346`:
  Cache-Zahlen vor statt nach dem endgültigen Commit des bereinigten Verlaufs.
  Behoben; Commit liefert jetzt eigene Decode-/KV-Statistik.

Alle Verwerfungsstellen und die beabsichtigten Resets sind in
[benchmark/CACHE-AUDIT.md](CACHE-AUDIT.md) dokumentiert. Kein belegter pauschaler
Cache-Reset zwischen normalen Turns oder Tool-Runden. Keiner der Befunde beweist
die Ursache der gemeldeten 15 → 6 t/s bzw. 5 → 120 s auf dem A55.

## Auf dem Gerät gemessen

Keine Messung. `adb devices` zeigt kein verbundenes Gerät. Keine Aussage über eine
A55-Beschleunigung, tatsächliche Vulkan-Unterstützung der Optionen oder thermische
Ursachen. Ausstehend: Bonsai 2 4B PQ2_0, CPU/Vulkan, GBNF an/aus und optionale
Microbatch-/FA-/Q8-Varianten mit vergleichbaren Temperaturen auf dem Galaxy A55.
Ablauf und Bedeutung der Kennzahlen: [benchmark/README.md](README.md).

## Nur auf dem Host getestet

- `./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: 82 Tests erfolgreich, keine Fehler/übersprungenen Tests; `lintDebug` ohne Fehler, acht bestehende Warnungen.
- `scripts/check_project.py`: Manifest/XML, JNI-Namen, Runtime-Revision,
  UTF-8-Streaming und C++-Conversation-State erfolgreich.
- Drei Python-Tests für den Exportvergleich erfolgreich.
- Echte JNI mit SmolLM2-135M-Instruct Q4_K_M (SHA256
  `2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d`):
  Prefix-Vorwärmen in beiden Systemvarianten, warme/kalte Antwortgleichheit,
  expliziter Reset, 600 begrenzte Reasoning-Tokens mit anschließend 35 sauberen
  KV-Positionen und rollendes Kontextfenster erfolgreich.
- Separater strenger Drei-Turn-Test mit echter JNI, ohne Resets zwischen Turns:
  Turn 1: 0 wiederverwendet / 25 neu; Turn 2: 28 / 16; Turn 3: 47 / 16.
  Erneutes Konfigurieren einschließlich Thread-Änderung erhält KV. Kein Reasoning
  in Verlauf oder KV; finaler Kaltaufbau hat dieselben 66 Positionen.
- Echte JNI-Optionsprüfung: FA/F16 und FA/Q8-Kontexte mit Probe erfolgreich;
  Q8 ohne FA abgewiesen; Standardkonfiguration wiederherstellbar.
- Eigenständige native Kernel- und GBNF-/Samplerprüfungen erfolgreich.
- Diese Funktionsprüfungen sind keine Bonsai-PQ2_0- oder A55-Leistungsmessung.

## Nur gebaut

- ARM64-CPU-Debug-APK mit Android SDK 35, NDK 28.2.13676358, JDK 17 und Gradle
  8.11.1 erfolgreich gebaut. Nicht auf einem Gerät installiert oder ausgeführt.
- Host-JNI-Bibliothek für die obigen Funktionstests gebaut und ausgeführt.
- Vulkan-APK in dieser Umgebung nicht gebaut; Vulkan-Treiber/FA/Q8 auf dem A55
  bleiben ungeprüft. Es wurden keine Vulkan-Standardwerte geändert.

## Reproduktion

```bash
python3 scripts/fetch_runtime.py
python3 scripts/check_project.py
python3 -m unittest discover -s tests -p 'test_benchmark_compare.py'
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

cmake -S app/src/main/cpp -B build/host -DBONSAI_HOST_SMOKE=ON -DBONSAI_GRAMMAR_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build/host --target bonsai-jni bonsai-reference-test bonsai-grammar-test --parallel 2
build/host/bonsai-reference-test
# org.json:json:20240303 und lokales Test-GGUF; nicht Teil der APK.
javac -cp /pfad/json.jar -d build/cache-smoke app/src/main/java/de/bonsai/offline/NativeEngine.java app/src/main/java/de/bonsai/offline/ToolGrammar.java tests/ExportToolGrammar.java tests/CacheSmoke.java tests/ThreeTurnCacheSmoke.java tests/PerformanceOptionsSmoke.java
mkdir -p build/tool-grammars
java -cp build/cache-smoke de.bonsai.offline.ExportToolGrammar build/tool-grammars
build/host/bonsai-grammar-test build/tool-grammars
java -Djava.library.path=build/host -cp build/cache-smoke:/pfad/json.jar de.bonsai.offline.CacheSmoke /pfad/testmodell.gguf
java -Djava.library.path=build/host -cp build/cache-smoke:/pfad/json.jar de.bonsai.offline.ThreeTurnCacheSmoke /pfad/testmodell.gguf
java -Djava.library.path=build/host -cp build/cache-smoke:/pfad/json.jar de.bonsai.offline.PerformanceOptionsSmoke /pfad/testmodell.gguf
```

`ToolLoop.java`, `ToolGrammar.java`, `MainActivity.java` und `rag/` sind unverändert.
Fünf getrennte Implementierungscommits plus Dokumentation der Prüfung.
