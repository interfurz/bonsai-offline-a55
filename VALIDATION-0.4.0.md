# Prüfnachweis 0.4.0

19. Oktober 2026. Android SDK 35, NDK 28.2.13676358, CMake 3.22.1, JDK 17. ARM64-CPU-Testbuild, versionCode 5. Details und APK-Prüfsumme: [BUILD-INFO.json](BUILD-INFO.json); historische Release-Metadaten: [BUILD-INFO-0.3.0.json](BUILD-INFO-0.3.0.json).

## Android

`./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` bestanden. Zehn Tests unter Robolectric API 35 bestehen: vier vorhandene UI-/Profiltests und sechs neue Tests für Hintergrundverarbeitung und Referenzrückfall.

Neue Prüffälle:

- Eine Antwort läuft nach Stoppen/Zerstören der Activity weiter, wird gespeichert und ist beim Wiederöffnen verfügbar.
- Die CPU-Wachhaltung ist während der Verarbeitung aktiv und nach Abschluss frei.
- Abbruch über die Service-Aktion erreicht die Engine und räumt anschließend auf.
- Ein Inferenzfehler gibt die Wachhaltung frei und entfernt die unbeantwortete Nutzernachricht.
- Ein verspäteter Dienststart nach Aufgabenende hält die CPU nicht wach.
- Abweichender optimierter CPU-Pfad fällt auf skalare Kernel zurück; ein übereinstimmender Pfad bleibt verfügbar.

Lint meldet keine Fehler und vier Warnungen (API-Attribut, ChromeOS-ABI, Touch-Accessibility und Stringressourcen). APK-Signaturprüfung und `zipalign -c -P 16 4` bestehen. Der neue lokale Debug-Schlüssel stimmt **nicht** mit dem früheren Release überein. Das APK ist deshalb kein direktes Update einer mit dem alten Schlüssel signierten Installation.

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

## Grenzen

Kein physisches Galaxy A55 ist angeschlossen. Robolectric prüft keine reale Samsung-Energieverwaltung, und QEMU bildet den Samsung-Treiber-/Gerätezustand nicht ab. Das vom Nutzer gezeigte inhaltliche Wirrwarr auf „1+1“ ist bisher nicht mit seiner konkreten Gerätedatei reproduziert. Die genaue Dateibezeichnung und ein Modelltestprotokoll mit SHA256 wurden angefragt. Die skalare Referenz teilt weiterhin andere Runtime-Operationen mit dem optimierten Pfad. Deshalb wird keine vollständige Behebung des A55-Fehlers behauptet.

Exynos-1480-NPU-Inferenz ist nicht implementiert; Begründung und Primärquellen stehen in [NPU-STATUS.md](NPU-STATUS.md).
