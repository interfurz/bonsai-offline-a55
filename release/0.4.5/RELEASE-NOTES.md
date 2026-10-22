# Bonsai Offline 0.4.5 — lokale Wissenssuche und Cache-Diagnosen

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

**Kein direktes Update der veröffentlichten 0.4.3-/0.4.4-APKs**, deren Zertifikat
`d45ceaf8f512e9a1e774a8278480a83ead37f693add7ffa342064556d317720d` lautet.
Das Paket bleibt gleich; ein anderer Schlüssel ermöglicht keine parallele
Installation. Bestehende Apps nicht vorschnell deinstallieren: private Chats,
Erinnerungen, Einstellungen und Modellkopien würden gelöscht. Es wird keine
automatische Migration oder Schlüsselrotation behauptet.

Keystore und Passwörter werden ausschließlich lokal verwendet und gehören
nicht zu Git, CI oder Release-Assets. Download und Prüfsumme stehen unter Assets.
Weitere Hinweise: [SIGNIEREN.md](../../SIGNIEREN.md).

## Prüfungen und Grenzen

Die geprüften Commit-/Build-/Signaturdaten stehen in `build-info.json` unter
Assets. Signatur, Paket-/Versions-/Debugflags, 16-KiB-ZIP-/ELF-Ausrichtung sowie
unveränderte APK-Dateiinhalte nach Signierung werden vor Veröffentlichung geprüft.

Keine physische A55-Installation, keine reale Modellinferenz dieser APK und keine
RTX-4060-Messung. Das IVF-Untersekundenziel, reale Clusterverteilung, Vulkan-Treiber,
optionale FA/Q8-Einstellungen und Hintergrundverhalten müssen auf dem A55 geprüft
werden. Die veröffentlichten Host-Messungen sind keine Gerätewerte.
