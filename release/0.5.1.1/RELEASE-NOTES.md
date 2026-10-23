# Bonsai Offline v0.5.1.1

**Download:** [Signierte CPU/Vulkan-APK](https://github.com/interfurz/bonsai-offline-a55/raw/eb74304c78b0a2655ff0a7190704d11aa91ede82/v0.5.1.1/Bonsai-Offline-0.5.1.1-arm64-CPU-Vulkan.apk) · [SHA256SUMS.txt](https://github.com/interfurz/bonsai-offline-a55/raw/eb74304c78b0a2655ff0a7190704d11aa91ede82/v0.5.1.1/SHA256SUMS.txt) · [Buildnachweis](build-info.json).

Version **0.5.1.1**, Versionscode **15**, Paket `de.bonsai.offline.debug`,
ARM64 mit CPU und experimentellem Vulkan. Enthält den in `main` gemergten PR #23.

- Weniger UI-Arbeit beim Streaming: Text-, Thinking- und Prefill-Updates ändern
  nur Antwort und Fortschritt; unveränderte Textpuffer und Metadaten bleiben erhalten.
- Die Lichteffekte bleiben auch während der Berechnung animiert. Weniger
  Zeichenschichten, begrenzte Highlightbereiche und ein gemeinsamer, an die
  Bildsynchronisation gebundener Taktgeber reduzieren den Aufwand.
- Langsame Hintergrundfelder werden seltener aktualisiert als aktive Konturen;
  Farbpaletten, Spotanzahl und Zustandswechsel bleiben erhalten.
- Der Sekunden-Timer aktualisiert nur den Status; Scroll-Aufträge werden
  zusammengefasst. Hintergrund/Wiederaufnahme und Pop-ups sind geprüft.

Im nativen Host-Zeichentest benötigen Kontur und Antwortlinie beim Generieren
rund 50–60 % weniger Zeit pro Bild. Das sind Software-Canvas-Werte auf dem Host,
keine A55-Inferenz- oder Android-GPU-Messung. [Messmethode und Einzelwerte](../../docs/validation/PERFORMANCE-UI.md).

Die signierte APK wurde lokal aus `main`-Commit
`29fb2099b0518a4c11033f9f96ce7eb15a728c3c` gebaut. **154 Release-/Robolectric-Tests**
bestanden ohne Fehler oder übersprungene Tests. Release-Lint meldet **0 Fehler
und 8 Warnungen**. Vier Python-Vergleichstests, Hostchecks für
Manifest/XML, JNI, Runtime-Backport, UTF-8/Reasoning/Präfix sowie Java-Syntax und
alle 37 Icon-Paare sind erfolgreich. Die lokale Toolchain verwendet JDK 21.0.10
mit Java-Ziel 17, Android API 35, Build-Tools 35.0.0, NDK 28.2.13676358,
CMake 3.22.1 und das per SHA256 geprüfte Vulkan SDK 1.4.363.0.

APK: **68.188.916 Bytes**, SHA256
`c3307efef72b9b469b4108624ecf9215d874f9bf9ae9a7e60983d37e90bed120`.

Signatur v3 für API 28+ und separat v2 für API 24–27, Paket/Version/Debugflag,
Berechtigungen, 16-KiB-ZIP-/ELF-Ausrichtung und bytegleiche komprimierte
App-Inhalte vor/nach dem Signing sind geprüft. Details:
[Signaturprotokoll](signature-verification.txt),
[Updatevergleich mit der tatsächlich heruntergeladenen v0.5.1-APK](update-compatibility.json).
Der veröffentlichte Download wurde erneut abgerufen und per SHA256 mit der
lokal geprüften signierten Datei verglichen.

Der unabhängige [CPU/Vulkan-Actions-Lauf](https://github.com/interfurz/bonsai-offline-a55/actions/runs/39883100345)
ist für denselben Quellcommit vollständig erfolgreich abgeschlossen.
Die APK stammt aus dem lokalen Build, nicht aus einem CI-Artefaktdownload.
Der Release-Tag ergänzt den Buildcommit nur um Dokumentation und Nachweise;
die Git-Objekt-IDs der Bauinputs stehen im Buildnachweis und werden vor dem Tag
gegen den tatsächlichen `main`-Stand verglichen.

Direkte Release-Asset-Uploads wurden mit HTTP 401 abgewiesen. APK und Prüfsumme
liegen deshalb über feste Commitlinks im bestehenden `downloads`-Branch.
Keystore und Passwortdatei wurden weder an Git noch an CI oder Release-Assets
übergeben. Die Signieridentität entspricht v0.5.1, v0.5 und 0.4.3/0.4.4;
Versionscode 15 ist höher als 14. Direkte Updates von 0.4.5/0.3.0 sind wegen
eines anderen Zertifikats weiterhin nicht möglich. [Signierhinweise](../../SIGNIEREN.md).

Physische A55-Abnahme, tatsächlicher Inferenzgewinn und Geräteupdate sind nicht
getestet. Modellgewichte, RAG-/Wikidata-Datenbanken und Embedding-Gewichte sind
nicht Bestandteil der APK. Vulkan bleibt experimentell.
