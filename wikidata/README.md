# Wikidata offline: SQLite und Knowledge Graph

Eigenständige Standardbibliothek-Pipeline (`python3 >= 3.10`), unabhängig von
Wikipedia, Vektorsuche und EmbeddingGemma. Android nutzt die vorhandene
`ToolLoop`-/GBNF-Schnittstelle. Es gibt keine HTTP-Aufrufe und keinen SQL-Modus
für das Modell. Das vorhandene Manifest hat keine Internetberechtigung.

## Kleine Daten zuerst

```sh
python3 -m unittest discover -s wikidata/tests -v
python3 -m wikidata.fixtures
python3 -m wikidata.fixtures --check
python3 -m wikidata.benchmark --output work/wikidata-benchmark.json
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Die Fixture enthält selbst zusammengestellte, realistische Beispielstatements
im offiziellen JSON-Format: Japan/Tokio, Einstein, neun Nachbarn Deutschlands,
Elemente, Einheiten, bevorzugte/veraltete Aussagen und einen kleinen Mondgraphen.
`2026-10-01` ist ihr **Test-Datenstand**, kein Datum eines heruntergeladenen Dumps.
Jupiter hat darin zwei, Saturn einen erfassten Mond. Das sind ausdrücklich keine
aktuellen Gesamtzahlen. Dieselbe Python-Datenbank wird unverändert in den
Android-/Robolectric-Tests gelesen; `--check` vergleicht Inhalte und Schema und
ignoriert die pfadabhängige Checkpoint-Identität. SQLite-Seitenbytes können sich
zwischen SQLite-Versionen unterscheiden.

## Offiziellen Dump auf dem Laptop verarbeiten

Quelle: <https://dumps.wikimedia.org/wikidatawiki/entities/>. Unterstützt werden
offizielle **zeilenorientierte Entity-JSON-Dumps** als `.json`, `.json.gz` und
`.json.bz2` sowie JSONL mit einem Entity-Objekt pro Zeile. RDF/N-Triples, XML,
Pretty-Print über mehrere Zeilen und ein minifiziertes Array auf einer einzigen
Zeile werden nicht unterstützt. Lexeme/Formen/Sinne werden übersprungen;
Q- und P-Entities werden unterstützt. Andere Entity-Werttypen bleiben in den
komprimierten Rohwerten erhalten, ohne Q/P-Graphkanten zu erzeugen.

Vollständige Dumps ausschließlich auf dem Laptop herunterladen. Offizielle
Prüfsumme separat verifizieren, Snapshot festhalten und Datei während des
Imports unverändert lassen. Es gibt bewusst keinen automatischen Downloader.

```sh
python3 -m wikidata /daten/wikidata-SNAPSHOT-all.json.bz2 /daten/bonsai-wikidata.sqlite \
  --dump-date YYYY-MM-DD --config wikidata/profiles/example.json \
  --max-bytes 7000000000 --batch 1000

# Nach Abbruch mit genau derselben Quelle, Konfiguration und Ressourcenlimits:
python3 -m wikidata /daten/wikidata-SNAPSHOT-all.json.bz2 /daten/bonsai-wikidata.sqlite \
  --dump-date YYYY-MM-DD --config wikidata/profiles/example.json \
  --max-bytes 7000000000 --batch 1000 --resume
```

`YYYY-MM-DD` durch den tatsächlichen offiziellen Snapshot-Tag ersetzen.
`example.json` ist eine Startauswahl für Geografie/Naturwissenschaften und die
Beispiele, keine garantierte vollständige Themenabdeckung. Größe und Auswahl
am Laptop prüfen und eingrenzen. Ohne Filter werden alle Items ausgewählt; das
7-GB-Limit ersetzt keine sinnvolle Auswahl.

Filterfelder:

| Feld | Bedeutung |
| --- | --- |
| `entities` | Explizite Q-IDs, deren Statements übernommen werden |
| `classes` | Items mit **direkter** P31-Zugehörigkeit zu einer dieser Q-IDs; ODER mit `entities` |
| `exclude_classes` | Direkte P31-Zugehörigkeit ausschließen; hat Vorrang |
| `properties` | Nur diese P-Properties; fehlende/leere Liste übernimmt alle |
| `qualifiers`, `references` | Original-Qualifikatoren/-Referenzen behalten; beide standardmäßig `true` |

Leere `entities` **und** `classes` wählen alle Items. Der Import berechnet keine
globale ontologische Klassenhülle. Relevante Unterklassen und ihre Definitionen
explizit auswählen. Für Klassenabfragen müssen P31, P279 und die jeweiligen
Klassendefinitionen erhalten bleiben. Zum Beispiel braucht ein Planet mit P31
„Gasplanet“ zusätzlich die lokale P279-Kette zum Planeten. Zeitqualifikatoren
werden unabhängig von der Hauptproperty-Auswahl beibehalten, sofern aktiviert.

Zwei Streaming-Durchläufe:

1. Gefilterte Entities, DE/EN-Labels/-Aliase/-Beschreibungen und Statements
   importieren. Referenzierte Q/P-IDs als kompakte Stubs auf Disk anlegen.
2. Labels, Beschreibungen, Aliase und Property-Datentypen dieser Stubs nachladen,
   auch wenn die referenzierte Entity im Dump früher steht. Ihre übrigen
   Statements werden dadurch **nicht** automatisch importiert.

Im RAM liegen jeweils eine Entity-Zeile und ein begrenzter SQLite-Cache
(32 MiB). Standard-Zeilenlimit: 16 MiB; `--max-line-bytes` ist konfigurierbar.
Sehr große Einzelentities werden standardmäßig mit klarer Fehlermeldung
abgewiesen. Komprimiertes Seek benötigt erneute Dekompression ab Dateianfang;
das spart RAM, kann bei Wiederaufnahme lange dauern. Unkomprimiertes JSONL
kann direkt zur unkomprimierten Byteposition springen.

Fortschritt erscheint als JSON auf stderr: Phase, unkomprimierter Offset,
gescannte/ausgewählte Datensätze, Fehler und DB-Größe. `.building` enthält den
Checkpoint. Entity-Änderungen und Offset werden in derselben Transaktion
committet. Quellpfad, Größe, mtime, Rand-Hashes, Datum und Filter müssen zur
Wiederaufnahme passen. Rand-Hashes ersetzen keine offizielle Vollprüfsumme.
Standardmäßig stoppt der erste fehlerhafte Datensatz. Mit `--max-errors N`
werden bis zu N Fehler toleriert und in `.errors.jsonl` protokolliert. Derselbe
kaputte Datensatz kann in beiden Durchläufen als Fehler zählen; nach Crash sind
doppelte Logzeilen möglich. Der DB-Checkpoint ist maßgeblich.

Nur der vollständig importierte, analysierte, per `quick_check` geprüfte und
verdichtete Bestand wird veröffentlicht. Bestehende Ausgabe wird am Start
abgewiesen. **Ein Importprozess pro Ausgabepfad.** SQLite `max_page_count`
begrenzt den DB-Bestand, der abschließend nochmals geprüft wird. Bei zu großer
Auswahl enger filtern und mit neuem Ausgabepfad starten. Limit umfasst die
publizierte DB, nicht Dump, Journale, Fehlerlog oder temporäre Dateien.
Für VACUUM/Journal zusätzlich etwa 2–3× DB-Größe an freiem Arbeitsvolumen
einplanen, zuzüglich Quelldump; unkomprimierte Dumps benötigen erheblich mehr.

## Schema und Speicherung

`schema.sql` versioniert den Container mit `application_id=1113017159`,
`user_version=1`. Positive Integer stehen für Q-IDs, negative für P-IDs, `0`
für einheitenlos. Dadurch entfallen wiederholte Q-/P-Strings in Indizes.

| Tabelle | Inhalt / Zugriff |
| --- | --- |
| `entity` | ID, Property-Datentyp, Quellrevision/-Änderungsdatum, Importtiefe |
| `term` | DE/EN-Label, Alias oder Beschreibung; WITHOUT ROWID, normalisierter Namensindex |
| `statement` | Subjekt, Property, Rank, Typ, Ziel-ID, exakter Dezimaltext + REAL-Suchindex, Einheit, Wikidata-Zeittext, Präzision, Kalender |
| `statement.detail` | zlib-komprimierte GUID, Mainsnak mit Originaldatentyp/-wert, optionale Qualifikatoren/Referenzen |
| `metadata` | Snapshot, CC0-Hinweis, Auswahl, Abschlussstatus, Checkpoint |

Indizes decken Namen, `(subject,property,rank)`, rückwärts gerichtete Entity-Kanten
und `(property,unit,number,subject)` ab. Wertebereiche, Dezimalstellen, Kalender,
Zeitzone, before/after und Datumspräzision bleiben im Originalwert erhalten;
BCE-/unvollständige Daten werden nicht in unpassende Unix-Zeitstempel umgewandelt.
`somevalue`/`novalue` bleiben getrennt. Deprecated-Statements werden gespeichert,
aber vom Tool nicht verwendet. Preferred verdrängt Normal pro Subjekt/Property.
Das ist Wikidata-Rank-Semantik, keine automatische Auswahl des neuesten Messwerts.

## Tool-Vertrag

Ein Aufruf bleibt im vorhandenen Format. Alle Argumente sind **Strings**.
Die GBNF-Grammatik verwendet die in der Tabelle genannte Reihenfolge.

```json
{"name":"wikidata","args":{"op":"facts","entity":"Japan","property":"P36"}}
```

| `op` | Argumente nach `op` | Bedeutung |
| --- | --- | --- |
| `resolve` | `entity` | Kandidaten aus deutschen/englischen Labels und Aliasen |
| `facts` | `entity`, `property` | Beste nicht veraltete Statements einer Entity |
| `path` | `entity`, `property` | Entity-Pfad, z.B. `P19/P17/P36` für Hauptstadt des Geburtslandes |
| `greater` | `entity`, `property`, `value`, `unit` | Numerische Statements der Klasse `entity` mit Wert strikt größer `value` |
| `largest` | `entity`, `property`, `unit` | Numerische Statements der Klasse absteigend |
| `count` | `entity`, `property`, `value` | Eingehende Kanten von Klasse `value` zu Klasse `entity`, verschiedene Subjekte zählen |

Beispiele für die übrigen Fragen:

```json
{"op":"facts","entity":"Albert Einstein","property":"P569"}
{"op":"facts","entity":"Deutschland","property":"P47"}
{"op":"greater","entity":"Q11344","property":"P1086","value":"50","unit":"1"}
{"op":"path","entity":"Q937","property":"P19/P17/P36"}
{"op":"count","entity":"Q634","property":"P397","value":"Q2537"}
```

Namen: NFKC, Unicode-Kleinschreibung und zusammengefasster Whitespace; keine
unsichere unscharfe Zuordnung. „Einstein“ kann mehrere Personen liefern. Dann
`ambiguous` samt Beschreibungen zurückgeben, Rückfrage stellen und erst mit
explizit geklärter ID fortfahren. Ein Tippfehler wird nicht automatisch korrigiert.
Fehlende Entity ergibt `not_found`, fehlende Aussagen `no_local_facts`, keine
Aussage über die Nichtexistenz in der Welt. Unterschiedliche Unicode-Versionen
von Python/Android können bei neuen Unicode-Zeichen abweichen; IDs bleiben stabil.

Jedes Ergebnis trägt `dataset_date` und `coverage=selected_local_subset`.
`count` zählt ausschließlich erfasste Beziehungen und behauptet **niemals**
die gesamte Zahl aktuell bekannter Monde. Unterklassen werden nur entlang
vorhandener P279-Ketten einbezogen. Der Dump-Stand ist kein Beobachtungsdatum;
Qualifikatoren wie P585 bleiben gesondert sichtbar. Die finale Antwort bekommt
zusätzlich einen deterministischen Wikidata-Herkunftshinweis mit Datenstand.

Numerische Vergleiche mischen keine Einheiten: `unit="1"` oder eine aufgelöste
Einheiten-Q-ID ist obligatorisch; automatische Umrechnung gibt es nicht.
Der REAL-Index erzeugt Kandidaten, BigDecimal prüft die strikte Grenze und
sortiert vollständige REAL-Gleichstandsgruppen exakt nach Dezimaltext.
Nicht endliche Float64-Indexwerte bleiben als exakte Fakten lesbar, sind aber
aus Bereichs-/Rangabfragen ausgeschlossen (`numeric_scope`). Vergleiche sind auf
100 signifikante Dezimalstellen und eine Dezimalskala von ±300 begrenzt.
Ranglisten liefern Statements; mehrere zeitliche Messungen derselben Entity
können mehrere Zeilen ergeben. Zeitliche Qualifikatoren bei der Antwort beachten.

## Android, Speicher und Grenzen

Fester Pfad: `files/wikidata/knowledge.sqlite` im internen App-Speicher.
Der Tool-Aufruf kann keinen Pfad wählen. Zugriff mit `OPEN_READONLY`, festem
SQL und gebundenen Parametern; Cache auf 4 MiB, temporäre SQLite-Strukturen auf
Dateien eingestellt. Keine großen Java-Sammlungen pro DB-Entity. Die Datenbank
darf während einer Abfrage nicht ausgetauscht/verändert werden.

Begrenzt auf 20 Zeilen/Kandidaten, 6.000 JSON-Zeichen pro Antwort, drei
Pfadschritte, 64 Kanten/Frontier-IDs, 128 Klassen und acht Klassenrunden.
`more=true` signalisiert gekürzte Ergebnisse. Breite Pfade/Klassen und mehr als
256 numerische Kandidaten einer Grenzgruppe führen zu einem erklärten Fehler.
Ein Abbruchsignal mit etwa zwei Sekunden Gesamtbudget unterbricht auch SQLite;
Budget und Abbruch werden zusätzlich zwischen Operationen geprüft. Das ist kein
hartes Echtzeitversprechen für jede Android-Version oder einen blockierten
Dateisystemzugriff. Auf dem A55 mit realem Bestand messen, bevor Budget verändert wird.

Komprimierte Details werden nur bis 32 KiB eingelesen und bis 16 KiB entpackt;
Qualifikatoren/Referenzen pro Feld bis 1.600 Zeichen ausgegeben. Auslassungen
werden gekennzeichnet; Originaldetails verbleiben in der DB. Statement-Metadaten
und Labels werden begrenzt, um Modellkontext und Heap zu schonen. Die native
Kontextprüfung bleibt aktiv; bei sehr langen Fragen, zwei großen Tool-Ergebnissen
oder kleinem Kontext kann ein größeres Kontextfenster/kleineres Antwortbudget
nötig sein. Vollständige Listen über dem Ergebnisbudget benötigen eine spezifischere
Fragestellung; keine automatische Pagination und kein allgemeines SPARQL.

Für eine **Debug-Installation** lässt sich die fertige DB lokal provisionieren:

```sh
adb shell am force-stop de.bonsai.offline.debug
adb push /daten/bonsai-wikidata.sqlite /data/local/tmp/wikidata.sqlite
adb shell run-as de.bonsai.offline.debug mkdir -p files/wikidata
adb shell run-as de.bonsai.offline.debug cp /data/local/tmp/wikidata.sqlite files/wikidata/knowledge.sqlite.new
adb shell run-as de.bonsai.offline.debug mv files/wikidata/knowledge.sqlite.new files/wikidata/knowledge.sqlite
adb shell rm /data/local/tmp/wikidata.sqlite
```

`run-as` setzt eine debuggable APK voraus. Es wird keine neue Importoberfläche
für nicht debuggable Release-APKs eingeführt. Für sehr große DBs ausreichend
freien internen Speicher für temporäre Kopie und Ziel vorsehen. App danach neu
starten und mit Flugmodus prüfen. Einheiten und Dump-Stand in Antworten prüfen.

Gerätemessung mit derselben produktiven Query-Klasse, ohne LLM:

```sh
bash wikidata/device/build_benchmark.sh
adb push work/wikidata-device/wikidata-benchmark.jar /data/local/tmp/wikidata-benchmark.jar
adb shell run-as de.bonsai.offline.debug cp /data/local/tmp/wikidata-benchmark.jar files/wikidata/benchmark.jar
adb shell run-as de.bonsai.offline.debug env CLASSPATH=/data/user/0/de.bonsai.offline.debug/files/wikidata/benchmark.jar \
  app_process /system/bin de.bonsai.offline.WikidataBenchmark \
  /data/user/0/de.bonsai.offline.debug/files/wikidata/knowledge.sqlite 30 > work/a55-wikidata.json
adb shell rm /data/local/tmp/wikidata-benchmark.jar
```

Das Hilfsprogramm misst Öffnen, Metadaten, SQL und JSON inklusive Klassen-/Aliasauflösung
mit drei Warmups und p50/p95. PSS sind zwei Momentaufnahmen des separaten Prozesses,
kein Peak und kein kombinierter Bonsai-/LLM-RAM-Wert. Erster Durchlauf bedeutet
keinen garantiert kalten OS-Cache. Die DEX-Variante wurde in Cloud gebaut, nicht
auf einem A55 ausgeführt. Prüfresultate und konkrete verbleibende Geräteprüfungen:
[VALIDATION.md](VALIDATION.md).
