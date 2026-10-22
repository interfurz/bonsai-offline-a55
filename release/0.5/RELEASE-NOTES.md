# Bonsai Offline v0.5 – integrierter main-Stand

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

Die APK muss aus dem vollständigen CPU/Vulkan-CI-Lauf des angegebenen
`main`-Quellcommits stammen; Signierung und APK-Prüfung erfolgen anschließend
lokal. Der veröffentlichte Buildnachweis nennt Quell-/Runtime-/Patchidentität,
CI-Lauf, Test-/Lintresultate, Datei-/Signaturhash und APK-/ELF-Ausrichtung.
Prüfsumme und Buildnachweis werden mit der signierten APK bereitgestellt.

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
