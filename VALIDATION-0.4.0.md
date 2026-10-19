# Prüfnachweis 0.4.0

19. Oktober 2026. Android SDK 35, NDK 28.2.13676358, CMake 3.22.1, JDK 17. ARM64-CPU-Testbuild, versionCode 7. Details und APK-Prüfsumme: [BUILD-INFO-0.4.0-tests.json](BUILD-INFO-0.4.0-tests.json); historische Release-Metadaten: [BUILD-INFO-0.3.0.json](BUILD-INFO-0.3.0.json).

## Android

Alle 26 Unit-/Robolectric-Tests, `assembleDebug` und `lintDebug` bestehen. Die Prüfungen umfassen die übernommenen UI-/Profil-/Temperaturtests aus 0.3.1, Hintergrundverarbeitung und Referenzrückfall sowie Dateiintegrität und den erweiterten Benchmarkexport. Der Teststand und APK-Hash stehen in `BUILD-INFO-0.4.0-tests.json`.

Neue Prüffälle:

- Eine Antwort läuft nach Stoppen/Zerstören der Activity weiter, wird gespeichert und ist beim Wiederöffnen verfügbar.
- Die CPU-Wachhaltung ist während der Verarbeitung aktiv und nach Abschluss frei.
- Abbruch über die Service-Aktion erreicht die Engine und räumt anschließend auf.
- Ein Inferenzfehler gibt die Wachhaltung frei und entfernt die unbeantwortete Nutzernachricht.
- Ein verspäteter Dienststart nach Aufgabenende hält die CPU nicht wach.
- Abweichender optimierter CPU-Pfad fällt auf skalare Kernel zurück; ein übereinstimmender Pfad bleibt verfügbar.
- Der Benchmarkexport enthält den tatsächlichen Dateihash und alle drei generierten Antworten.
- Die vom A55 gemeldete abweichende SHA256 wird trotz gleicher Dateigröße zurückgewiesen. Die beiden geprüften offiziellen Dateien werden akzeptiert, eine unvollständige Kopie wird abgewiesen, andere Modellnamen werden nicht falsch zugeordnet.
- Ein abgewiesener Import erhält die bisherige Modellkopie und die geladene Engine; ein schon importiertes abweichendes Modell gelangt beim Laden nicht zur nativen Engine.

Lint meldet keine Fehler und vier Warnungen (API-Attribut, ChromeOS-ABI, Touch-Accessibility und Stringressourcen). APK-Signaturprüfung und `zipalign -c -P 16 4` bestehen. Der neue lokale Debug-Schlüssel stimmt **nicht** mit dem früheren Release überein. Das APK ist deshalb kein direktes Update einer mit dem alten Schlüssel signierten Installation.

Der vollständige [CPU/Vulkan-Release-Build auf GitHub](https://github.com/interfurz/bonsai-offline-a55/actions/runs/39196937446) besteht für Quellstand `2bb08b43ccb068460e8c9f1a00d0194748d6c32d`: alle 26 Release-Tests ohne Fehler, Release-Lint, nativer Build und Prüfung beider Backends. Die heruntergeladenen Testberichte und Build-Provenienz bestätigen diese Ergebnisse. Das ebenfalls heruntergeladene unsignierte APK hat 67.976.300 Bytes und SHA256 `fbc14cd3eb31da22414a86c6760b41efe71a39a772489f36dcf87605044617cd`; seine Prüfsumme stimmt mit dem CI-Nachweis überein. Paket `de.bonsai.offline.debug`, Version 0.4.0, Code 7 und 16-KB-Zipalignment sind lokal nachgeprüft. Das Release-Artefakt benötigt vor der Installation den bisherigen lokalen Signierschlüssel. Diese JVM-/Build-Prüfungen führen Vulkan nicht auf einem echten Gerät aus.

`python3 scripts/check_project.py`, Java-Syntaxprüfung und die UTF-8-Tests bestehen. Die Manifestprüfung verlangt genau die vier Hintergrund-/Benachrichtigungsberechtigungen; INTERNET bleibt ausgeschlossen.

## Quantisierungskernel

`bonsai-reference-test` vergleicht optimierte und skalare Dot-Produkte mit unabhängig berechneten dichten Produkten aus dequantisierten Werten. Geprüft werden Q1_0, Q2_0, PQ2_0/Q8_0, PQ2_0/Q8_K und PTQ1_0 sowie mehrere Breiten von 128 bis 5120 und der skalare 2x2-Fallback. Bestanden auf x86_64 und als statisch mit dem Android-NDK gebautes ARM64-Programm unter QEMU 10.0.13, CPU `cortex-a55`, mit `armv8.2-a+dotprod`. Dabei wurde kein Fehler dieser einzelnen ARM-Quantisierungskernel reproduziert.

Host-Prüfung:

```bash
cmake -S app/src/main/cpp -B build/kernel-check -DBONSAI_KERNEL_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build/kernel-check --target bonsai-reference-test --parallel 2
./build/kernel-check/bonsai-reference-test
```

## Echte Modelldatei

Offizielle `Ternary-Bonsai-2-4B-PQ2_0.gguf`, 1.146.465.120 Bytes, SHA256 `d406b1e912c0d2a4c124ad10bf38a76ae662fdc4d24b176451403dc7b7f30409`.

`tests/NativeSmoke.java` besteht gegen die aus dem festgelegten Runtime-Quellcode gebaute x86_64-JNI-Bibliothek: ungültige Datei/Profil, verschiedene CPU-Profile, Abbruch/Reset, 2+2 -> 4 und Folgefrage +3 -> 7. Auch der skalare Modus mit Microbatch 1 liefert 4 und denselben Probe-Fingerprint `2977870351680999971`.

`tests/ModelArithmeticSmoke.cpp` ist ein eigenständiger Rechentest ohne JNI, auch für ARM64. Er kann mit `-DBONSAI_MODEL_TESTS=ON` als Ziel `bonsai-model-test` gebaut werden; Aufruf: `bonsai-model-test /pfad/modell.gguf scalar` oder `optimized`.

Der vollständige Rechentest mit derselben Modelldatei besteht außerdem als statisch gelinktes Android-ARM64-Programm unter QEMU `cortex-a55`: optimierte ARM-Kernel, 2 Threads, Microbatch 32, Kontext 512, 54 Prompttokens inklusive Systemnachricht und deaktiviertem Thinking. Greedy-Ausgabe exakt `4`. Auch hier wurde das gemeldete Wirrwarr nicht reproduziert. Das ist ein emulierter ARM-Test, kein Nachweis auf einem Samsung-Gerät.

## Gemeldete Q2_0-Datei

Die offizielle Datei auf Hugging-Face-Revision `8d6557168168f91ea6697a4a49ed2ef622b53adb` hat 1.212.103.520 Bytes und SHA256 `64a5183b44921bdb5ac4a6f5f92007821d6ebe5c66997d3c147fd214ab9433d4`. Hash und Größe stimmen mit dem offiziellen LFS-Pointer überein; die Datei bleibt auch nach dem Laden unverändert. Stichproben ihrer entpackten Gewichte stimmen mit der PQ2_0-Datei überein.

`tests/ModelResponseSmoke.java` prüft die App-JNI samt tatsächlichem Sampler mit 4 Threads, Kontext 4096, Microbatch 32, Seed 42 und der App-Systemnachricht. Die offizielle Q2_0-Datei antwortet auf „1+1“ mit `1+1 = 2` und auf „Was ist 2 + 2? Antworte nur mit der Zahl.“ exakt mit `4`.

Derselbe JNI-Test besteht auch im skalaren CPU-Kompatibilitätsmodus mit Microbatch 1: erneut `1+1 = 2` und exakt `4`. Die Host-Laufzeiten sind keine Messung der A55-Geschwindigkeit.

Auch der vollständige Q2_0-Rechentest besteht als statisch gelinktes Android-ARM64-Programm unter QEMU `cortex-a55`: optimierte ARM-Kernel, 2 Threads, Microbatch 32, Kontext 512 und 54 Prompttokens. Die Greedy-Ausgabe lautet exakt `4`. Damit sind beide geprüften 4B-Dateien durch den vollständigen emulierten ARM-Modelltest gelaufen.

Der bereitgestellte A55-Modelltest nennt dieselbe Dateigröße, aber SHA256 `4e157769ecbdbdc9f555c58a0834d00bce09430a99efd53a8f3beca642042eff`, und dokumentiert falsche Antworten auch ohne Chatverlauf. Die konkrete private Kopie liegt nicht vor. Eine abweichende oder beschädigte Datei ist damit eingegrenzt; der genaue Ursprung der Byteabweichung und das Verhalten nach Neuimport sind noch nicht auf dem Gerät geprüft.

## Grenzen

Kein physisches Galaxy A55 ist angeschlossen. Robolectric prüft keine reale Samsung-Energieverwaltung, und QEMU bildet den Samsung-Treiber-/Gerätezustand nicht ab. Das vorgelegte Gerätelog identifiziert inzwischen die abweichende Q2_0-Datei. Die skalare Referenz teilt weiterhin andere Runtime-Operationen mit dem optimierten Pfad. Eine vollständige Behebung nach Austausch der Datei muss auf dem A55 bestätigt werden.

Exynos-1480-NPU-Inferenz ist nicht implementiert; Begründung und Primärquellen stehen in [NPU-STATUS.md](NPU-STATUS.md).
