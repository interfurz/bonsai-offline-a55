# Datenbank-Abnahme — laufender Stand 23.10.2026

Fortschreibung 24.10.2026: größerer 10k-Test mit 688 menschlichen Fragen,
native-Q8-Parität, FTS4-Relevanzkorrektur und strikte Stack-Exchange-Tagprüfung.

Die Gesamtmission ist **nicht abgeschlossen**. Insbesondere sind der
Wikipedia-Vollbestand und produktive Vektoren der weiteren Textkorpora noch
nicht nachgewiesen. Diese Datei trennt fertige Komponenten, echte Messungen,
laufende Verarbeitung und konkrete offene Abnahmen.

## Preflight auf diesem Windows-Laptop

Windows 11 Build 26200, Python 3.11.9, SQLite 3.45.1, RTX 4060 Laptop mit
8.188 MiB. NVIDIA-Treiber 617.42, PyTorch 2.14.1+cu130, CUDA-Matrixrechnung
tatsächlich ausgeführt. Faiss CPU 1.15.1 funktioniert nativ unter Windows;
kein WSL installiert oder verwendet. Physisch gemessener RAM:
42.728.759.296 Bytes. Zu Beginn waren nur 109.387.091.968 Bytes auf C: frei;
nach dem vom Nutzer vorgenommenen Aufräumen 354.639.249.408 Bytes.
Die aktuellen freien Bytes werden mit `preflight`/`status` erneut ermittelt.

## Fertige echte Datenläufe

| Lauf | Tatsächlich gemessen |
|---|---|
| Wikipedia 1k, bisheriger Konverter | 1.000 Artikel, 153 Redirects, 15.742 Chunks, 156.745.728 Bytes DB, 0 Importfehler |
| Wikipedia 1k, Tabellen/ZahlExp/Exponenten + echter Tokenizer | 1.000 Artikel, 153 Redirects, 17.285 Chunks, alle Dokumentprompts ≤800 Tokens, 0 Importfehler; SQLite und FTS-ID-Mengen geprüft |
| Wikipedia 10k | 10.000 Artikel, 1.436 Redirects, 163.058 Chunks, 1.472.348.160 Bytes DB, 0 Importfehler; Integrität und FTS-/Chunk-ID-Gleichheit bestanden |
| Wikipedia 100k | 100.000 Artikel, 32.771 Redirects, 886.793 Chunks, 7.117.844.480 Bytes DB, 0 Fehler; SQLite-/FTS-/Chunk-Integrität bestanden. Fortsetzung ab 10k: 4.515,515 s, RSS-Summe maximal 913.342.464 Bytes |
| Wikibooks vollständig | 40.380 Inhaltsseiten, 1.737 Redirects, 339.997 Chunks. 2.482.978.816 Bytes; zwei CSS-Begleitseiten in neuer Generation mit Quellabgleich auditierbar umklassifiziert; anschließend 0 Inhaltsfehler, Integrität/FTS-ID-Mengen bestanden |
| Echte Import-Unterbrechung | 10k-Lauf als Prozessbaum beendet; SQLite-Checkpoint wiederaufgenommen, 10k danach vollständig validiert |
| EmbeddingGemma 2 FP32/CUDA | Alle 17.285 Pilot-Chunks exportiert; 1.867,594 s einschließlich Modellladen, abgetastete RSS-Summe 2.802.061.312 Bytes; dauerhafte Shards mit SHA256 |
| EmbeddingGemma 2, 10k/CUDA | 163.058 Vektoren in bestätigten Shards, 17.141,953 s einschließlich Laden und Schreiben, abgetastete RSS-Summe 2.802.991.104 Bytes; IVF 256/512/1.024 gebaut, 688 menschliche Fragen ausgewertet, siehe unten |
| Faiss mit realen Pilotvektoren | 64/128/256 Cluster trainiert; BRAGIVF1-Dateien 4.629.760 / 4.699.392 / 4.830.464 Bytes; keine Größen-/Latenzbehauptung für Millionen realer Vektoren |
| Wiktionary-Pilot | 1.000 Seiten, 1.437 Sprache-/Wortart-Einträge, 7.327 Formen; `Hallos` → deutsches Lemma `Hallo`; vollständiger Dumpimport läuft separat |
| Wiktionary-Vollimport, erste Generation | 1.265.548 Inhaltsseiten, 1.320.680 Sprache-/Wortart-Einträge, 1.549.379 Formen, 11.993.874 Details, 4.556.926.976 Bytes; Integrität und Referenzen bestanden. 35.024 Seiten ohne Standard-Wortartabschnitt: Varianten-/Weiterleitungsupgrade vor Paketfreigabe |
| Wiktionary freigegebene Generation | 1.266.743 Inhalts-/Weiterleitungsseiten, 1.322.640 strukturierte Einträge, 1.549.395 Formen, 37.840 Aliase, 34.259 gekennzeichnete Rohtextzugänge; keine gespeicherte Seite ohne vorgesehenen Suchzugang. DB 4.594.368.512 Bytes, 0 Importfehler |
| Electronics-Auswahl | 12.678 Fragen, 22.231 zugeordnete Antworten; nach Mindesttextprüfung 22.213 Antwortdokumente, 200.290.838 Bytes JSONL; noch kein fertiges Vektorpaket |
| Electronics-Textimport | 22.213 Dokumente, 43.958 Chunks, 389.746.688 Bytes Host-DB, 0 Fehler, SQLite-/FTS-/ID-Prüfung bestanden |
| Physics-Textimport | 17.808 ausgewählte Fragen, 27.377 Antworten; 27.372 Antwortdokumente, 58.112 Chunks, 0 Importfehler |
| Super-User-Auswahl | 30.000 Fragen und 51.884 Antworten; 51.635 Antwortdokumente nach Mindesttextprüfung, 347.545.147 Bytes JSONL |
| Super User / Mathematics | Super User: 51.635 Dokumente / 93.961 Chunks / 662.614.016 Bytes DB. Mathematics: 57.514 Dokumente / 124.849 Chunks / 956.366.848 Bytes DB. Beide ohne Importfehler, SQLite-/FTS-/ID-Prüfungen bestanden |
| OFF, deutscher Markt | 3.269.682 Quellprodukte gescannt, 257.707 Produkte importiert, 492.003.328 Bytes DB; 1.262,922 s, abgetastete RSS-Summe 61.325.312 Bytes |
| OFF-Normalisierung | In neuer Generation 104.849 Energiewerte aus der dokumentierten kJ-Spalte `energy_100g` ergänzt; Originalgeneration behalten, fehlende/negative Werte nicht zu Null gemacht |
| Konstanten/Einheiten | Vollständige CODATA-2022-Tabelle mit 355 Größen und Unsicherheiten; zusätzlich π/e mit expliziter 50-stelliger Dezimalapproximation, 712 typisierte Einheiten einschließlich SI-Präfixen; `ms` und `Ms` werden unterschieden |

Die erste Wikipedia-Auswahl besteht aus frühen Dumpartikeln und ist kein
repräsentatives Zufallssample. Auch die Vollskalierung muss ihre eigenen
Artikel-/Chunkzahlen und Ressourcen liefern. Es wird nicht aus 1k auf eine
angebliche Gesamtzahl hochgerechnet.

## Fertige installierbare Container

| Paket | Bytes | SHA256 |
|---|---:|---|
| `bonsai-dewiki-20261001-pilot-1k-v2.bonsai` | 127742451 | `be2a84133e30e579b812b43c5014e68657e3f4e227c986c47580bde78a35de7b` |
| `bonsai-dewiki-20261001-pilot-10k-v1.bonsai` | 1112408984 | `237aae2317bb3337be00760ee1f7bd0abecc0aa59ef72514a821dff6baf61e3f` |
| `bonsai-constants-codata2022-v3.bonsai` | 321813 | `b2f9c2f18dfc8eb97981bc31fd42f18a22252c0c5bdbd73908b93bf0702aac5f` |
| `bonsai-food-germany-20240624-v1.bonsai` | 492004566 | `9fef75edc40e56dbb29a817d41ef31d10259a5c53d9e22f1256818f53724fe6d` |
| `bonsai-dewiktionary-20261001-v1.bonsai` | 4594372574 | `42a2c578381d1f6d6444d50f504c72b33dc5f312d4dedc2390ebcfe42e816b73` |

Die beiden Wikipedia-Container sind ausdrücklich 1k-/10k-Piloten mit derselben
Paket-ID; jeweils die zuletzt importierte Generation wird aktiv. Der Nährwertcontainer
enthält einen vollständigen selektierten Marktbestand des offiziellen **2024**-
Exports; er ist kein aktueller 2026-Nährwertnachweis und keine Tabelle generischer
Lebensmittel. Fertige Dateinamen werden nicht überschrieben, frühere lokale
Generationen bleiben erhalten.

## Retrieval und numerische Prüfung

Der größere 10k-Bestand enthält 688 exakt titelzuordenbare menschliche
GermanQuAD-Fragen zu 66 Themen. Alle passen in die 256-Zeichen-Querygrenze.
Die Titelzuordnung bewertet den passenden Artikel, nicht die tatsächliche
Antwortspanne oder deren Aktualität. Auch dies ist kein repräsentatives Sample
der gesamten Wikipedia.

Mit HF-FP32-Queries und FTS5 erreicht 768D-Vollscan Recall@10 0,8604651,
256D 0,8386628 und Hybrid-exakt256 0,8982558. Ein solcher FTS5-Test allein
belegt den portablen Android-Pfad nicht: Der bisherige FTS4-Fallback nahm
OR-Treffer in Chunk-ID-Reihenfolge und erreichte im selben Fragensatz 0/688
passende Artikel unter Top-10. Dieser nachgewiesene Rankingfehler wird durch
`fts4-bm25-v1` behoben; die Vektoren und Chunk-Identitäten bleiben gültig.

Separater tatsächlich portabler Pfad mit 688 **nativen Q8-Queries**, dem
FTS4-Ranker und IVF256 (Beleg `portable-10k-688-v3.json`):

| Verfahren | Artikel-Recall@5 | Artikel-Recall@10 | MRR@10 |
|---|---:|---:|---:|
| FTS4 korrigiert | .770349 | .831395 | .659318 |
| INT8-IVF/nprobe64, Q8-Queries | .796512 | .835756 | .722709 |
| Hybrid/nprobe16 | .847384 | .892442 | .743978 |
| Hybrid/nprobe64 | .851744 | .888081 | .754976 |
| Hybrid/nprobe128 | .854651 | .888081 | .759609 |

Für den 10k-Pilot wird IVF256/nprobe64 paketiert: höhere Top-3-Abdeckung
(0,809593 gegenüber 0,800872 bei nprobe16), höhere MRR und gemessene ANN-
Übereinstimmung 0,970494 zum INT8-Vollscan im HF-Query-Vergleich. Hybrid-Recall@10
ist dabei nicht monoton mit nprobe; die vollständige Tabelle bleibt erhalten.
Die FTS4-Kandidatengrenze wurde in keiner der 688 Abfragen erreicht. Mittlere
warme FTS4-Hostzeit 81,311 ms, keine A55-Zeit und kein isolierter Benchmark.
Der 10k-Pilot ist kein produktiver Voll-Wikipedia-Index.

Native-Q8-Parität für dieselben 688 Fragen: minimale 256D-Kosinusähnlichkeit
0,9989648366, Mittel 0,9998755563, maximale absolute Komponentendifferenz
0,0089514636. HF-CPU 44,062 s, native CPU 203,688 s, einschließlich des konkret
protokollierten Ablaufs; keine Android-/JNI-Messung. GGUF-/Runtime-/HF-Pins
bleiben identisch zum 40-Fragen-Test.

Die folgenden 1k-Zahlen beziehen sich auf die frühere **Host-FTS5**-Auswertung:

40 menschliche GermanQuAD-Testfragen passen zu sechs Titeln im 1k-Pilotbestand.
Das ist ein kleiner, thematisch geclusterter Test. Historische Antwortwerte
werden nicht ungeprüft als 2026-Fakten bewertet.

| Verfahren | Artikel-Recall@5 | Artikel-Recall@10 | MRR@10 |
|---|---:|---:|---:|
| FTS5 | .950 | .950 | .86250 |
| HF FP32 768D | .950 | .975 | .94107 |
| HF FP32 256D | .950 | .975 | .88274 |
| INT8-Vollscan, globaler IVF-Scale | .950 | .975 | .88438 |
| Hybrid, IVF 64 Cluster/nprobe 16 | .975 | 1.000 | .95417 |

ANN-Recall@10 für 64/nprobe16: .9725 relativ zum INT8-Vollscan, .9200
relativ zur exakten 256D-FP32-Rangliste. Das sind unterschiedliche Bezugsgrößen.
Gemessene warme Host-Suche im Index: durchschnittlich 2,548 ms ohne Query-
Encoding; kein Android-, Cache-kalt- oder A55-Wert. Das nprobe-/Cluster-Raster
und Einzelabfragen stehen im lokalen JSON-Beleg. 64 Cluster sind eine
Pilotentscheidung; Vollindizes benötigen neue Messungen.

Die CPU-/CUDA-/GGUF-Pins und Paritätsgrenzen stehen in
[EMBEDDING-PROFILE.md](EMBEDDING-PROFILE.md). BF16-Proben sind separat erfasst;
die veröffentlichten Pilotvektoren wurden nicht damit vermischt.

## Tests und Builds

- 50 RAG-/IVF-/Wikipedia-/Shard-/Fachkonverter-/Benchmark-Tests bestanden.
- 16 Wikidata-Pipelinetests bestanden, einschließlich parallelem Bzip2-
  Resume, bytegleichem Stream, identischen DB-Inhalten, Quellbindung und
  Ablehnung beschädigter Blockindizes.
- 17 Adapter-/Downloadtests bestanden, ohne Netzwerk und Modellgewichte;
  einschließlich direktem 7z-Stream, Energie-Alias und auditierter CSS-Reparatur.
- 5 weitere Hosttests einschließlich Runtime-Backport bestanden.
- Neuer GBNF-Vertrag im echten gepinnten nativen Parser auf Windows geprüft:
  Wörterbuch, Tabellen und Korpusauswahl akzeptiert; SQL/OSM/falsche Argumente
  und Tools in der Finalrunde abgewiesen.
- Vollständiger CPU-Release-Build sowie Release-Lint und 167 Release-/Robolectric-
  Tests ohne Fehler oder übersprungene Tests erfolgreich ausgeführt. Nach
  weiteren Änderungen erneut mit dem finalen Commit abgleichen.
- Der echte Wikipedia-Pilot wurde über denselben Android-Importer unter
  Robolectric importiert und über den read-only Suchpfad abgefragt. Zusätzlich:
  Hashfehler, Pfadversuche, falscher DB-Typ, Platzmangel, Abbruch, Updateerhalt,
  Deaktivierung, zusammengesetzte Identitäten und Schreibsperre nach Wissensabruf.
- Vollständiges 4,59-GB-Wörterbuch, OFF-Marktbestand und SI-Paket über denselben
  Android-Importer unter Robolectric installiert und mit echten Inhalten
  abgefragt: Konvergenz, Nordatlantic, Bank, Haferflocken und 3 Lichtjahre.
  Belege und Hostimportzeiten in `android-real-structured-packages.json`.
- Der zusätzliche gezielte Android-Hosttest importiert das echte 1.112.408.984-
  Byte-10k-Paket, prüft seine Integrität und findet per read-only FTS den Titel
  Aussagenlogik. Ein Test ohne Fehler bestanden; dies ergänzt die archivierte
  167er-Gesamtabnahme und ersetzt keine Geräteprüfung.
- Mehrkorpus-RRF vereinigt semantische Kandidaten über vergleichbare normalisierte
  Scores aller kompatiblen Pakete. Ein Regressionstest mit fünf Communities
  verhindert, dass alphabetisch spätere Pakete dauerhaft aus Top-3 verschwinden.

Der erste unisolierte Windows-Robolectric-Gesamtlauf stürzte im nativen SQLite-
Finalizer ab. Klassen laufen auf Windows jetzt in getrennten Testprozessen;
Assertions und natives SQLite bleiben aktiviert. Weitere Windows-Befunde:
Unix-absolut aussehende Pfade explizit ablehnen, gemappte Testdateien nicht
in-place verändern, Runtime-Patch-EOLs vor abschließender Byteprüfung behandeln.

CI am 24.10.2026 zeigte zusätzlich eine Race beim Sampling kurzlebiger SQLite-
Journaldateien. Der Sampler toleriert deren Verschwinden und beendet bei eigenen
Fehlern seinen Importprozess, bevor Dateien freigegeben werden. Zwei Regressionen
decken beide Fälle ab. Kleine Shard-/IVF-Fixtures wählen explizit null zusätzliche
Reserve; benötigte Dateibytes werden weiterhin geprüft. Die produktive API behält
5 GiB Mindestreserve, der gemeinsame Index-Einstieg standardmäßig 20 GiB und
`--reserve-gib` für größere Produktionsreserven. Diese Änderung beseitigt die
Abhängigkeit winziger Tests von 5 GiB freiem CI-Speicher. Zusätzlich wurden
kleine Adapter-, Reparatur-, Paket- und Downloadfixtures entsprechend isoliert;
50 RAG- plus 17 Adapter-/Downloadtests bestehen gemeinsam bei simulierten
4 GiB freiem Speicher. Ein eigener Test bestätigt, dass der produktive
Standard weiterhin bei unterschrittener 5-GiB-Reserve abweist.

## Lokal signierte Vorschau-APK

`Bonsai-Offline-0.5.2-db-preview-arm64-CPU.apk`, Versionscode 16, wurde aus
Commit `0a7e4e59200c1fb1c7492de2667fb34935594ca0` gebaut. 9.272.052 Bytes,
SHA256 `6cc60323d12162663facd3eba57a1385a3f10ea0b4e4c8b4f2dda777ad3b2b97`.
Die tatsächliche v3-Signatur und 16-KiB-ZIP-Ausrichtung sind verifiziert;
Zertifikat-SHA256 `d45ceaf8f512e9a1e774a8278480a83ead37f693add7ffa342064556d317720d`
entspricht der bisherigen Release-Identität. ARM64-CPU-Release, App-ID
`de.bonsai.offline.debug` wie bisher, keine Internetberechtigung. Die Endung
der App-ID ist kein Nachweis einer debuggbaren APK. Schlüssel und Passwort
bleiben außerhalb des Repositories. Kein Release auf GitHub veröffentlicht;
Installation und Signaturvergleich mit einer tatsächlich auf dem A55
installierten APK bleiben offen. Nachfolgende Host-/Dokumentationsänderungen
gehören nicht zum APK-Quellcommit.

## Noch offen / laufend

1. Nach bestandenem 100k-Gate und vollständiger offizieller Dumpprüfung läuft
   der Wikipedia-Vollimport mit Checkpoints und 80-GiB-SSD-Reserve. Seine
   endgültige Artikel-/Chunkzahl, vollständige Vektorisierung und Index fehlen.
   Die zuvor beobachteten HTTP-429 wurden durch begrenzten Wiederanlauf
   überwunden; sie sind kein noch bestehender Downloadblocker.
2. Der FP32-GPU-Export des 10k-Bestands und sein Testpaket sind vollständig.
   Produktionspräzision und Cluster-/nprobe-Wahl für große Bestände offen.
3. Wikibooks ist vollständig importiert und geprüft; Embeddings/IVF/Paket fehlen.
4. Vollständiges Wiktionary-Paket ist geprüft; physische Android-16-Abnahme offen.
5. Electronics, Physics, Super User und Mathematics sind als Textkorpora geprüft;
   Stack-Overflow-Auswahl läuft. Alle fünf Archive und das zusätzliche
   Stack-Overflow-Users-Archiv sind vollständig geprüft. Embedding-/IVF-Pakete und das gemeinsame
   gemessene 6-GB-Budget fehlen noch.
6. Wikidata: datierter offizieller 103-GB-Rohdump wird heruntergeladen;
   noch keine Vollprüfsumme des empfangenen Dumps. Breite Auswahl unter 7 GB,
   Ontologie-/Referenzabdeckung und echte strukturierte Sollabfragen fehlen.
8. Repräsentative Retrievaltests, Nichttreffer, Aktualitätsprüfung des OFF-
   Bestands und paketübergreifende Antwortabnahme bleiben erforderlich.
9. Kein A55 in `adb devices -l`. SAF-Import auf physischem Gerät, Flugmodus,
   Bonsai-Toolwahl, gleichzeitiger RAM, Query-Ladezeit, Latenzen und Thermik offen.
10. APK-Version 0.5.2-db-preview / Code 16 ist als CPU-Release gebaut und mit
    der bisherigen Release-Identität signiert/verifiziert. Die A55-Installation
    bleibt offen. Keine erfundenen Gerätebenchmarks.

## Reproduktion

```powershell
& $py -m unittest discover -s rag/tests -v
& $py -m unittest discover -s wikidata/tests -v
& $py -m unittest discover -s database_pipeline/tests -v
& $py -m unittest discover -s tests -v
$env:BONSAI_TEST_PACKAGE = 'ABSOLUTER_PFAD_ZUM_REALEN_1K_V2_PAKET'
$env:BONSAI_TEST_PACKAGE_DIR = 'ABSOLUTER_PFAD_ZUM_PAKETVERZEICHNIS'
$env:BONSAI_TEST_EVIDENCE_DIR = 'ABSOLUTER_PFAD_ZU_BENCHMARKS'
.\gradlew.bat --no-daemon --max-workers=2 :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

Ohne `BONSAI_TEST_PACKAGE` entfällt der echte RAG-Pilotintegrationstest;
ohne `BONSAI_TEST_PACKAGE_DIR` der zusätzliche große Strukturpakettest.
Die übrigen Importtests erzeugen eigene Fixtures. Die 167er-Abnahme hatte beide
Paketvariablen gesetzt und übersprang keinen Test.
Python-/Windows-Einstieg, Quellbelege und Installationsschritte:
[DATABASE-PIPELINE.md](DATABASE-PIPELINE.md), [DATASET-SOURCES.md](DATASET-SOURCES.md),
[OFFLINE-PACKAGES.md](OFFLINE-PACKAGES.md).
