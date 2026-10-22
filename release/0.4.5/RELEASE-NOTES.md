# Bonsai Offline 0.4.5 — lokale Wissenssuche und Cache-Diagnosen

**Download:** [Signierte APK](https://github.com/interfurz/bonsai-offline-a55/raw/fe9b02b46c1bd1ecc6ff24af40be464338e77057/v0.4.5/Bonsai-Offline-0.4.5-arm64-CPU-Vulkan.apk) · [SHA256SUMS.txt](https://github.com/interfurz/bonsai-offline-a55/raw/fe9b02b46c1bd1ecc6ff24af40be464338e77057/v0.4.5/SHA256SUMS.txt) · [Buildnachweis](https://github.com/interfurz/bonsai-offline-a55/raw/fe9b02b46c1bd1ecc6ff24af40be464338e77057/v0.4.5/build-info.json)

ARM64-Release mit CPU und Vulkan, Paket `de.bonsai.offline.debug`, Versionscode 12,
Debugging ausgeschaltet. Enthält die nach `main` integrierten PRs #8 bis #11:

- Kompakte Kopieren-/Neu-generieren-Icons und Messwertanzeige mit zugänglichen
  Beschreibungen, Tooltips und 48-dp-Tippflächen.
- Lokales Tool `suche` mit begrenzten Treffern und Quellen in der Endantwort;
  Tool-JSON bleibt aus dem sichtbaren und gespeicherten Verlauf heraus.
- Wikipedia-/JSONL-Vorbereitung, komprimierte Texte, FTS5 mit Android-FTS4-Fallback,
  256D-INT8-Embeddings und IVF-Index mit konfigurierbaren Clustern/nprobe.
  Der Java-Scanner liest per mmap; Geräte-Benchmark und 100-Fragen-Vergleich
  sind im Repository enthalten. Datenbank, Index und Modellgewichte werden
  separat bereitgestellt; Android enthält zunächst den Beispiel-Query-Encoder.
- Cache-/Prefill-/Turn-Diagnosen, Systempräfix-Warmup, Fünf-Turn-Benchmark sowie
  ausdrücklich optionale Flash-Attention-/KV-Q8-Einstellungen.
- Der gemeinsame RAG-UI-Test wurde an die neue Commit-Diagnose-Schnittstelle
  angepasst. Einstellungen und signierte APK allein belegen keine A55-Beschleunigung.

## Installation und Signierung

Auf Wunsch mit dem bereitgestellten **Original-Debug-Keystore** signiert.
Zertifikat SHA256:
`ce1a585cbd3b51dcb446c50814122d5c68832a6a052f2c6f139cf2e0c44208db`.

**Signaturkompatibel mit der ursprünglichen v0.3.0:** das Zertifikat der
veröffentlichten APK und ihr Paket wurden geprüft; Versionscode 12 ist höher
als 4. Ein tatsächlicher Updateversuch auf dem A55 wurde nicht durchgeführt.

**Kein direktes Update der veröffentlichten 0.4.3-/0.4.4-APKs**, deren Zertifikat
`d45ceaf8f512e9a1e774a8278480a83ead37f693add7ffa342064556d317720d` lautet.
Das Paket bleibt gleich; ein anderer Schlüssel ermöglicht keine parallele
Installation. Bestehende Apps nicht vorschnell deinstallieren: private Chats,
Erinnerungen, Einstellungen und Modellkopien würden gelöscht. Es wird keine
automatische Migration oder Schlüsselrotation behauptet.

Keystore und Passwörter werden ausschließlich lokal verwendet und gehören
nicht zu Git, CI oder Release-Assets. APK, Prüfsumme und Buildnachweis sind oben verlinkt und liegen im bestehenden
`downloads`-Zweig. Der direkte Release-Asset-Upload wurde von GitHub mit
HTTP 401 zurückgewiesen. Bei diesem privaten Repository ist ein berechtigtes
GitHub-Login für den Download erforderlich.
Weitere Hinweise: [SIGNIEREN.md](https://github.com/interfurz/bonsai-offline-a55/blob/main/SIGNIEREN.md).

## Prüfungen und Grenzen

Die geprüften Commit-/Build-/Signaturdaten stehen im oben verlinkten
`build-info.json`. Signatur, Paket-/Versions-/Debugflags, 16-KiB-ZIP-/ELF-Ausrichtung sowie
unveränderte APK-Dateiinhalte nach Signierung sind erfolgreich geprüft.
Die APK ist 68.084.780 Byte groß; ihre SHA256 steht in `SHA256SUMS.txt`.

- **Auf dem Gerät gemessen:** keine Messungen und kein Installations-/Updateversuch.
- **Nur auf dem Host getestet:** 106 Java-/Robolectric-Tests erfolgreich,
  Release-Lint ohne Fehler (acht Warnungen). Auf dem integrierten Stand außerdem 21 RAG- und drei
  Benchmark-Python-Tests, Kernel-/Grammatiktests, `lintDebug` und
  `scripts/check_project.py` erfolgreich. Keine neue Geräte-Laufzeit oder
  Recall-Zahl wird aus diesen Funktionstests abgeleitet.
- **Nur gebaut:** ARM64-CPU/Vulkan-Release aus Commit
  `39b1391e0f568a7065032eb76e757992822b7221`, lokal mit dem festgelegten
  Vulkan-SDK 1.4.363.0, NDK 28.2.13676358 und anschließend lokal signiert.
  Der [unabhängige CI-Lauf desselben Quellstands](https://github.com/interfurz/bonsai-offline-a55/actions/runs/39777528867)
  ist ebenfalls erfolgreich. Die veröffentlichte APK stammt aus dem lokalen
  Build; der CI-Artefakt-Download war durch den Umgebungsproxy blockiert.

Keine reale Modellinferenz dieser APK und keine RTX-4060-Messung. Das IVF-Untersekundenziel, reale Clusterverteilung, Vulkan-Treiber,
optionale FA/Q8-Einstellungen und Hintergrundverhalten müssen auf dem A55 geprüft
werden. Die veröffentlichten Host-Messungen sind keine Gerätewerte.
