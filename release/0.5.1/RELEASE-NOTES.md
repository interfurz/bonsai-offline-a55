# Bonsai Offline v0.5.1

**Download:** [Signierte CPU/Vulkan-APK](https://github.com/interfurz/bonsai-offline-a55/raw/020c78c69badfab4e9cc53cacb41b4b3c9fd079f/v0.5.1/Bonsai-Offline-0.5.1-arm64-CPU-Vulkan.apk) · [SHA256SUMS.txt](https://github.com/interfurz/bonsai-offline-a55/raw/020c78c69badfab4e9cc53cacb41b4b3c9fd079f/v0.5.1/SHA256SUMS.txt) · [Buildnachweis](build-info.json).

Version **0.5.1**, Versionscode **14**, Paket `de.bonsai.offline.debug`,
ARM64 mit CPU und experimentellem Vulkan. Enthält den in `main` gemergten PR #20.

- **Systemprompt bearbeiten:** Menü → Modell → Antworten anpassen. Eigene
  Vorgaben sind direkt bearbeitbar, Tool-Anweisungen erst nach einer Warnung.
  Lokale Speicherung, Standard-Reset und Entwurfserhalt bei Drehung sind enthalten.
- **Live-Zeile unter Bonsai:** aktueller Thinking-Text oder Prompt-Cache-Fortschritt
  und gemessene Prefill-Tokens/s, jeweils einzeilig. Die Endantwort wird wieder
  normal mehrzeilig angezeigt. Thinking bleibt vorübergehend und wird nicht
  als Chatantwort oder in Logs gespeichert. Ohne Modell-Thinking kein erfundener Text.
- **Vergleichbare Messungen:** Chat-Benchmark exportiert den Systemprompt-Hash,
  ohne eigene Vorgaben offenzulegen; abweichende Prompts werden erkannt.

Die signierte APK wurde lokal aus `main`-Commit
`2dfe2c41d6fcfa58b2537171e204a815baec4897` gebaut. **151 Release-/Robolectric-Tests**
bestanden ohne Fehler oder übersprungene Tests, Release-Lint ohne Fehler mit
neun Warnungen. Vier Python-Vergleichstests sowie Manifest-/JNI-/UTF-8-/Reasoning-
Hostchecks bestanden. CPU/Vulkan-Bibliothek und unsignierte APK wurden gebaut;
anschließend wurden die lokale Signatur, Paket/Version/Debugflag, Berechtigungen,
16-KiB-ZIP-/ELF-Ausrichtung und bytegleiche Payloads vor/nach dem Signing geprüft.

APK: **68.188.916 Bytes**, SHA256
`0046bf0ae77773e5b854793ba933bea730a7e5afcb5795f8dc40645aa91a638c`.
Signaturprüfungen für v3 (API 28+) und separat v2 (Verifizierer API 24–27) sind
im [Signaturprotokoll](signature-verification.txt) dokumentiert; minSdk bleibt 28.
Der tatsächliche [Updatevergleich mit v0.5](update-compatibility.json) prüft Paket,
Zertifikat und Versionscode. Der Release-Tag darf gegenüber diesem Buildcommit
nur Dokumentation/Nachweise ergänzen; Anwendung und Buildquellen bleiben gleich.

Ein unabhängiger [CPU/Vulkan-Actions-Lauf](https://github.com/interfurz/bonsai-offline-a55/actions/runs/39833109407)
prüft denselben Quellcommit; sein tatsächlich beobachteter Status steht im
Buildnachweis. Die veröffentlichte APK stammt aus dem lokalen Build, nicht aus
CI. Der Keystore und die Passwortdatei wurden nicht an CI oder Git übergeben.
Direkte Release-Asset-Uploads antworten mit HTTP 401; APK und Prüfsumme liegen
deshalb über feste Commitlinks im bestehenden `downloads`-Branch.

Physische A55-Abnahme und persönliche UX-Ergebnisse bleiben ausstehend; siehe
[Testplan](../../A55-ECHTWELT-TESTPLAN.md). Hosttests und Renderbilder sind keine
Gerätemessung. Weder reale Bonsai-Inferenz noch Geräteupdate, Prefill-Tempo,
Tastatur/TalkBack, RAM oder Thermik wurden durch diesen Release auf dem A55 geprüft.

Die Signierung erfolgt mit dem bereitgestellten Release-Schlüssel. Er entspricht
v0.5 und 0.4.3/0.4.4. Direkte Updates von 0.4.5/0.3.0 sind wegen eines anderen
Zertifikats weiterhin nicht möglich. Bestehende Daten nicht durch vorschnelle
Deinstallation löschen. [Signierhinweise](../../SIGNIEREN.md).

Modellgewichte, RAG-/Wikidata-Datenbanken und Embedding-Gewichte sind nicht
Bestandteil der APK. Die NPU wird nicht unterstützt; Vulkan bleibt experimentell.
