# Bonsai Offline v0.5 – integrierter main-Stand

**Download:** [Signierte CPU/Vulkan-APK](https://github.com/interfurz/bonsai-offline-a55/raw/de4cbc824a2c08f5c090371c61ad15394287bd0e/v0.5/Bonsai-Offline-0.5-arm64-CPU-Vulkan.apk) · [SHA256SUMS.txt](https://github.com/interfurz/bonsai-offline-a55/raw/de4cbc824a2c08f5c090371c61ad15394287bd0e/v0.5/SHA256SUMS.txt) · [Buildnachweis](build-info.json).

ARM64-Release mit CPU und optionalem Vulkan, Appversion `0.5`, Versionscode 13,
Paket `de.bonsai.offline.debug`, Debugging ausgeschaltet. Modelle und
Wissensdaten werden separat bereitgestellt; weiterhin keine Internetberechtigung.

## Enthaltene Änderungen

- #12: echter lokaler EmbeddingGemma-2-Query-Encoder über CPU/JNI; reproduzierbarer
  Runtimebackport, 256D/L2, Profilprüfung, Abbruch und FTS-Rückfall.
- #13: wiederaufnehmbarer Wikipedia-Import mit stabilen Chunk-IDs,
  Revisions-/Quellenmetadaten, Originaltexten, Markdown und Fehlerjournal.
- #14: strukturierte Wikidata-SQLite-Pipeline und begrenztes read-only Tool für
  Namen, Fakten, Graphpfade und Zahlen; keine freie SQL-Ausführung.
- #15/#17: neue Chatoberfläche, Modell-/Leistungs-Pop-ups, native Lichtzustände,
  gemeinsame Icon-Familie mit optischen 16-dp-Varianten und zugänglichen Touchzielen.
- #16: aktuelle technische Roadmap und A55-Echtwelttestplan mit Debugaufzeichnung
  und persönlichem Nutzerfeedback.
- Bestehende Chat-/Gedächtnisdaten, Tool-Loop, IVF/FTS/RRF, Quellenanzeige,
  Hintergrunddienst und KV-/Benchmarkdiagnosen bleiben enthalten.

## Installation und Signatur

Auf Wunsch mit `bonsai-release.keystore` signiert, Zertifikat SHA256:
`d45ceaf8f512e9a1e774a8278480a83ead37f693add7ffa342064556d317720d`.

Die Signatur entspricht 0.4.3/0.4.4; die veröffentlichte 0.4.4-APK wurde zum
Vergleich tatsächlich heruntergeladen und mit `apksigner` geprüft.
**Kein direktes Update von 0.4.5/0.3.0**, die einen anderen Schlüssel verwenden.
Ein tatsächlicher Updateversuch auf dem A55 steht aus. Bestehende Installation
nicht vorschnell deinstallieren: private Chats, Gedächtnis und Modellkopien
würden gelöscht. Keine automatische Datenmigration zwischen Signieridentitäten.
Keystore und Passwörter bleiben außerhalb von Git, CI und Release-Assets.

## Nachweise und Grenzen

Die veröffentlichte APK wurde **lokal aus `main`-Commit
`efcd87a0b02fbc0325fbc30a20ce9b1dac291a15`** gebaut, mit Vulkan-SDK 1.4.363.0
(offizielle SHA256 geprüft), NDK 28.2.13676358, SDK 35 und dem gepatchten
PrismML-Runtimestand. Sie stammt nicht aus einem heruntergeladenen CI-Artefakt:
Der Artefaktspeicher verweigerte den Download. Der unabhängige
[CI-Lauf desselben Quellstands](https://github.com/interfurz/bonsai-offline-a55/actions/runs/39821276142)
ist im zweiten Versuch vollständig erfolgreich (Tests, Lint, nativer Build und
APK-/Konfigurationsprüfung); der genaue Versuch ist im Buildnachweis erfasst. Ein erster Versuch
scheiterte am Robolectric-Abhängigkeitsdownload; die Wiederholung auf unverändertem
Code bestand die Teststufe. Signierung und alle Prüfungen der veröffentlichten
APK erfolgen lokal; Keystore/Passwort wurden nicht an CI übergeben.

Lokal bestanden: **138 Release-/Robolectric-Tests** mit CPU- und Vulkan-
Buildkonfiguration, keine Fehler/übersprungenen Tests; Release-Lint null Fehler,
acht bestehende Warnungen. Zusätzlich 39 RAG-/Wikipedia-, 14 Wikidata- und vier
weitere Python-Tests, Hostchecks, Fixturevergleich und 37 SVG/XML-Paarprüfungen.
Das sind Host-/Funktionsprüfungen, keine Geschwindigkeitsmessungen des A55.

Signierte APK: **68.172.532 Bytes**, SHA256
`1a6633bf93c9a0d7073aaf5ffd32916315163c2038f2750c894a4317b2607da5`.
Paket/Version/Debugflag, Berechtigungen, Encoder-/Wikidata-/UI-Klassen sowie
16-KiB-ZIP-/ELF-Ausrichtung sind geprüft. Die ZIP-Dateipayloads stimmen vor und
nach dem Signing bytegenau überein. Modellgewichte/DBs/IVF sind nicht eingebettet.

Direkter Upload von Release-Assets wurde mit HTTP 401 verweigert. Daher liegen
APK und Prüfsumme wie bereits bei 0.4.5 im bestehenden `downloads`-Branch;
die obigen Links verwenden einen festen Commit. Der Release-Tag enthält
zusätzlich die erzeugten Nachweisdokumente; seine App-/Runtime-/Buildquellen
müssen mit dem gebauten Quellcommit identisch bleiben. Bei diesem privaten
Repository benötigt der Download eine berechtigte GitHub-Anmeldung.

Keine physische A55-Inferenz, kein Geräteupdate, keine echte RAG-/Vulkan-
Geschwindigkeitsmessung und keine neue Nutzerbewertung durch diesen Release.
Host-/Robolectric-/Buildprüfungen sind ausdrücklich keine Geräteabnahme.
Insbesondere gemeinsamer Bonsai-/Encoder-RAM, Modellparität, reale Suchqualität,
Treiberverhalten, Tastatur/TalkBack und Animation unter Inferenz bleiben offen.
Die App hat noch keinen normalen Wissenspaketimport im Release.

Nächster Schritt: [A55-Echtwelttestplan](../../A55-ECHTWELT-TESTPLAN.md), beginnend
mit korrekter Modelldatei und CPU-Modelltest; danach Alltag/Logs und erst bei
vorbereiteten kompatiblen Daten echtes RAG. [Roadmap](../../ROADMAP.md) und
[Signier-/Updatehinweise](../../SIGNIEREN.md).
