# PQ-Inferenz: CPU-Referenz und Textausgabe

Der bisherige CPU-Kompatibilitätsmodus ersetzte die ternären Dot-Produkte durch skalare Implementierungen. Schnelle Hadamard-Transformationen und CPU-Kernfusionen blieben dabei aktiv. Ein Fehler in diesen gemeinsamen Pfaden konnte somit auch die Referenz verfälschen und die automatische Rechenprüfung passieren.

Der Referenzmodus aktiviert jetzt zusätzlich GGMLs `use_ref` für den CPU-Backend jedes neuen Kontexts. Hadamard-Rotationen werden als dichte Matrixprodukte berechnet; die CPU-Kernfusionen werden umgangen. Das gilt auch nach einer Profiländerung. Optimierte Profile bleiben nach erfolgreichem Vergleich verfügbar. Die Referenzkennung ändert sich, damit alte Prüfergebnisse neu berechnet werden.

Die bereits separat entwickelte UTF-8-Korrektur ist auf den aktuellen 0.4.1-Quellstand übernommen: fehlerhafte Bytefolgen gelangen nicht mehr unbemerkt zum Java-Decoder. Gültige, an Tokengrenzen geteilte Unicode-Zeichen bleiben gepuffert. Diese Prüfung stellt keine beschädigten Gewichte wieder her und kann inhaltlich falsche, aber gültig kodierte Ausgaben nicht erkennen.

Der eigenständige ARM-Modelltest verwendet jetzt denselben Sampler wie die App, standardmäßig Kontext 4096 und vier Threads. Aufruf: `bonsai-model-test modell.gguf scalar|optimized [kontext] [threads]`.

## Prüfung

- Native Quantisierungstests auf x86_64 und mit dem Android-NDK gebautem ARM64 unter QEMU (`cortex-a76`): bestanden.
- Neue Hadamard-Tests vergleichen die Ausgaben mit unabhängig berechneten dichten Produkten für Breiten 128/1024 und einen/drei Tokens. Sie prüfen zusätzlich, dass der Referenzmodus den schnellen FWHT-Pfad tatsächlich umgeht und ein neuer optimierter Kontext ihn wieder verwendet.
- Gegenprobe mit dem bisherigen Verhalten (`use_ref=false`): der neue Regressionstest schlägt mit „CPU reference did not select an independent Hadamard path“ fehl.
- Android-ARM64-JNI-Bibliothek erfolgreich gebaut; Projekt-/UTF-8-Prüfungen bestanden.
- Alle 32 Android-Unit-/Robolectric-Tests sowie `lintDebug` bestanden (keine Lint-Fehler).
- Echter Modelltest: offizielle `Ternary-Bonsai-2-4B-PQ2_0.gguf` (1.146.465.120 Bytes, SHA256 `d406b1e912c0d2a4c124ad10bf38a76ae662fdc4d24b176451403dc7b7f30409`) mit Android-NDK/ARM64 unter QEMU `cortex-a76`, optimiertem CPU-Pfad, Kontext 4096, vier Threads, Microbatch 32 und dem App-Sampler (Seed 42): Die Antwort auf „Was ist 2 + 2? Antworte nur mit der Zahl.“ lautet exakt `4`. Der vollständige emulierte Lauf dauerte rund neun Minuten. Der skalare Referenzmodus wurde in dieser Prüfung durch die nativen Dot-/Hadamard-Tests abgedeckt; der vollständige Modelllauf erfolgte im optimierten Modus.

Ein physisches Galaxy A55 und die konkret fehlerhafte Modelldatei liegen nicht vor. Die Schwachstelle des Referenzpfads und die UTF-8-Prüfung sind belegt; eine vollständige Behebung des gemeldeten Zeichensalats bei allen PQ-Modellen ist damit noch nicht nachgewiesen.
