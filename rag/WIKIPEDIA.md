# Deutsche Wikipedia: Streaming-Import für Offline-RAG

Die Erweiterung nutzt `python -m rag ingest` und das bestehende SQLite-Schema v1.
Sie ändert keine Android-Klasse, Modell-Runtime, JNI- oder Embedding-Implementierung.
XML/bzip2 lesen, Markdown-Konvertierung und SQLite-Schreiben laufen ohne einen
vollständigen Korpus im RAM. Es gibt keinen Download im Importer oder in Tests.

## Installation und kleiner Test unter Windows

Python 3.10 oder neuer mit SQLite FTS5/FTS4 installieren, vorzugsweise 64 Bit.
Im Repository-Stamm in PowerShell:

```powershell
$env:PYTHONUTF8 = "1"
py -3.12 -m venv rag/.venv-wiki
rag/.venv-wiki/Scripts/python.exe -m pip install -r rag/requirements.txt
rag/.venv-wiki/Scripts/python.exe -m unittest discover -s rag/tests -p test_wikipedia.py -v
rag/.venv-wiki/Scripts/python.exe -m rag ingest rag/data/wikipedia/complex.xml rag/output/example.sqlite --workers 2
rag/.venv-wiki/Scripts/python.exe -m rag search rag/output/example.sqlite Lichtreaktion
```

Die UTF-8-Umgebungsvariable hält auch ältere RAG-Hilfstools und umgeleitete
CLI-Ausgaben unabhängig von der Windows-Codepage.

Für alle bestehenden RAG-/IVF-Tests zusätzlich
`pip install -r rag/requirements-ivf.txt`; für RSS-Messungen und den zugehörigen
optionalen Test `pip install -r rag/requirements-benchmark.txt`. Linux/macOS nutzen entsprechend
`python3` und die virtuelle Umgebung `rag/.venv-wiki/bin/python`.
Die vorgebaute [Beispieldatenbank](data/wikipedia/example.sqlite) lässt sich
sofort durchsuchen; die [Fixture-Beschreibung](data/wikipedia/README.md) erklärt
Datenherkunft und Abdeckung. Es sind selbst verfasste Testtexte.

## Späterer Lauf auf dem Windows-Laptop

Den offiziellen **dewiki-DATUM-pages-articles-multistream.xml.bz2** unter
<https://dumps.wikimedia.org/dewiki/> manuell beziehen. Auch unkomprimiertes XML,
aufgeteilte pages-articles-XML-Dateien (je ein eigener Import/DB) und normale
`.xml.bz2`-Dateien sind lesbar. Keine multistream-index.txt-, HTML-, SQL-,
Stub- oder Versionsgeschichtsdumps verwenden. Mehrere Revisionen einer Seite
werden als Fehler abgewiesen. Hauptnamensraum `ns=0` wird importiert.
Das Multistream-Indexfile wird nicht benötigt; verkettete bzip2-Streams werden
von Pythons `bz2` sequentiell gelesen.

Die veröffentlichte Prüfsumme separat kontrollieren, etwa mit
`Get-FileHash D:/Wikipedia/dewiki-DATUM-pages-articles-multistream.xml.bz2 -Algorithm SHA256`
und dem für diesen Snapshot veröffentlichten SHA256-Wert vergleichen. Falls dort
nur eine andere Prüfsummenart bereitsteht, deren Algorithmus für die Prüfung nutzen.
`--dump-sha256 HEX` speichert optional den **vom Nutzer überprüften** SHA256-Wert
zur Herkunftsdokumentation; `ingest` prüft diesen Wert nicht selbst.

Zuerst nur einen kleinen Ausschnitt verarbeiten:

```powershell
rag/.venv-wiki/Scripts/python.exe -m rag ingest D:/Wikipedia/dewiki-DATUM-pages-articles-multistream.xml.bz2 rag/output/dewiki.sqlite --limit 50 --workers 4 --commit-every 500
```

Erst später auf dem Laptop, nach Prüfung von Stichproben und freiem SSD-Speicher,
den gleichen Lauf ohne `--limit` fortsetzen:

```powershell
rag/.venv-wiki/Scripts/python.exe -m rag ingest D:/Wikipedia/dewiki-DATUM-pages-articles-multistream.xml.bz2 rag/output/dewiki.sqlite --resume --workers 4 --commit-every 500
```

`--limit` zählt insgesamt versuchte Inhaltsartikel einschließlich fehlerhafter
Artikel, ohne Weiterleitungen/andere Namensräume. Es ist kein zusätzlicher Batch
pro Wiederaufnahme. `--limit 50 --resume` bei bereits 50 Artikeln arbeitet daher
nicht weiter. Ein Import bis zum Limit kann noch fehlende Weiterleitungsziele haben.

Für 32 GB RAM mit **4 Workern, 64 MiB ausstehenden Eingabetexten und 8 MiB
maximalem Artikeltext** anfangen; dies sind konservative Startwerte, keine auf
diesem Windows-Laptop validierten Optima. Kleine Tests sind mit einem Worker
schneller. RTX 4060/CUDA wird beim Textimport nicht benötigt. Die GPU steht dem
separaten Embedding-Schritt zur Verfügung. Genügend freien SSD-Speicher für Dump,
Datenbank, WAL, temporäre SQLite-Dateien und spätere Vektoren vorhalten; aus den
stark wiederholten Fixtures lässt sich keine Voll-Dump-Größe ableiten.

## Optionen, Fortschritt und Wiederaufnahme

| Option | Standard / Wirkung |
| --- | --- |
| `--workers` | 1; >1 startet einen Prozesspool mit Windows-kompatiblem `spawn` |
| `--commit-every` | 500 Quellseiten/Zeilen; zusätzlich Checkpoint nach ca. 5 s bei abgeschlossener Seite |
| `--min-tokens`, `--max-tokens` | 80 / 800, UTF-8-Byte-Schätzung, kein Modell-Tokenizer |
| `--max-inflight-mib` | 64; begrenzt rohe Texte ausstehender Aufträge, höchstens `2 * workers` Aufträge |
| `--max-page-mib` | 8; größere Artikel als Fehler protokollieren, Original im Dump belassen |
| `--no-android-fts4` | FTS4 weglassen, nur bei nachgewiesener FTS5-Unterstützung der Zielgeräte |
| `--error-log` | Standard `DATABASE.errors.jsonl`; Spiegel des dauerhaften Fehlerjournals |
| `--quiet` | Fortschrittszeilen abschalten; JSON-Endbericht bleibt auf stdout |
| `--resume` | Nur zur gleichen Quelle und denselben Konvertierungs-/FTS-Optionen |
| `--dump-sha256` | Optionaler Herkunftsnachweis; bei Resume identisch angeben |

Es gibt einen Writer. SQLite nutzt einen 32-MiB-Seitencache, temporäre Dateien,
WAL, `synchronous=FULL` und begrenzte Transaktionen. Es gibt keinen abschließenden
riesigen FTS-`optimize`-Lauf; SQLite führt seine normalen Segment-Merges aus.
Am erfolgreichen Ende werden WAL-Daten eingecheckt und der Journalmodus auf
DELETE zurückgesetzt, damit die DB als einzelne Datei offline kopierbar ist.
Während des Imports keine Leser/Embedding-Prozesse auf dieselbe DB ansetzen.
Eine OS-Dateisperre `DATABASE.lock` verhindert zwei konkurrierende Importer;
bei Prozessabbruch wird die Sperre automatisch freigegeben, die kleine Datei bleibt.

RAM hängt von Seitencache, größter geparster XML-Seite und begrenzten Worker-
Aufträgen ab, nicht von der Artikelgesamtzahl. Das MiB-Auftragsbudget ist **kein
hartes Gesamt-RAM-Limit**: XML-Baum, Parserobjekte, Python-Unicode, Prozesskopien,
Markdown, komprimierte Daten und Ergebnisse benötigen zusätzlichen Speicher.
Eine Seite muss zuerst geparst werden, bevor ihr Text gegen `--max-page-mib`
geprüft werden kann. Ein bösartig riesiger XML-Eintrag ist damit nicht vollständig
gegen hohe Speichernutzung abgesichert. Normale offizielle Dumps sind das Ziel.

Fortschritt geht auf stderr (Seitenposition, Artikel, Weiterleitungen, Chunks,
Fehler, Artikel/s); stdout enthält den maschinenlesbaren JSON-Endbericht.
Für Logs z. B. `... 1> rag/output/ingest.json 2> rag/output/ingest.log` verwenden.
Bei Wiederaufnahme wird XML/bzip2 ab Anfang bis zur gespeicherten Seitenposition
**erneut gelesen/dekomprimiert**, aber nicht erneut konvertiert/indexiert.
Replay-Fortschritt wird ebenfalls angezeigt. Das ist bei späten Abbrüchen teuer;
es gibt keine falsche Behauptung eines beliebigen bzip2-Seeks. Manuelle offizielle
Teil-Dumps können diese Wiederanlaufzeit begrenzen; Datenbanken werden aktuell
nicht automatisch zusammengeführt.

Artikel, FTS-Zeilen, Chunk-Metadaten, Fehler und Checkpoint werden gemeinsam
committet. Bei Abbruch geht höchstens der letzte uncommittete Batch verloren.
Quellpfad, Dateigröße, mtime und SHA256-Stichprobe von Anfang/Ende schützen gegen
versehentlich falsche Resume-Dateien; das ersetzt **keine vollständige Prüfsumme**.
Workerzahl, Commitintervall und Auftragsbudget dürfen beim Resume geändert werden.
Konvertierungsoptionen, Textgrößenlimit, Quelle und Pipelineversion müssen passen.
Alte v1-Datenbanken ohne Import-Checkpoint lassen sich nicht migrieren/fortsetzen.
Nach einem bereits durchgeführten Embedding wird Resume zum Schutz der
ID/Vektor-Zuordnung abgelehnt; einen neuen Korpus in einer neuen DB aufbauen.

`ingest_errors` ist das maßgebliche dauerhafte Fehlerjournal. Der JSONL-Spiegel
wird nach dem Commit geschrieben; ein harter Abbruch dazwischen kann dort Zeilen
fehlen lassen. Wiederaufnahme überspringt bereits protokollierte Fehler. Nach
Korrektur von Daten/Parseroptionen eine neue DB erstellen. Ungültiges XML/bzip2,
DB-/Dateifehler, doppelte Artikel-IDs, ID-Kollisionen oder ausgefallene Worker
brechen den Lauf ab, statt unbemerkt Datensätze zu verlieren. Tracebacks stehen
im stderr-Log. `complete` bedeutet Quelle gescannt, nicht automatisch fehlerfrei:
vor Übergabe immer Fehlerzahl/Journal prüfen.

## Markdown, Chunking und stabile Identität

`mwparserfromhell==0.7.2` erkennt Überschriften als Parserknoten. Überschriften
in Vorlagen oder nowiki erzeugen keine falschen Grenzen. Einleitung bleibt ein
eigener Abschnitt, auch unter 80 Tokens. Benachbarte kurze Folgeabschnitte werden
zusammengeführt, innerhalb verwandter Themenzweige, soweit der gemeinsame Text unter 800 Tokens bleibt. Markdown-
Überschriften und `section_paths` bewahren alle beteiligten Hierarchien. Große
Abschnitte werden bevorzugt an Absätzen/Sätzen, danach Wörtern geteilt; auch
ungebrochene Unicode-Wörter bleiben unter dem Budget. Ein kurzer letzter Rest
wird nach Möglichkeit mit dem Vorgänger ausbalanciert. Einleitung und kleine
isolierte Abschnitte dürfen unter dem Mindestziel bleiben.

Schätzung: `ceil(len(text.encode('utf-8')) / 4)`, einschließlich Markdown. Vor
einem echten Modelllauf dessen Tokenizer-Limit zusätzlich prüfen. Kein Import-
Chunking kann die Modellkontextlänge allein anhand dieser Schätzung garantieren.

Bilder/Datei-/Kategorielinks, Referenzen, Galerien, Tabellen, Navigations-/Infoboxen,
Kommentare und Bibliografie-/Weblink-Unterbäume werden entfernt. Interne Links
werden zu lesbaren Labels, externe HTTP(S)-Links zu Markdown; Hervorhebungen,
Listen, einfache Code-/Mathematik-Ausdrücke und ausgewählte Inline-Textvorlagen
bleiben erhalten. Templates werden nicht online expandiert. Unbekannte Vorlagen
und Tabellen können wertvolle Information enthalten, die der bereinigte Text
verliert; das komprimierte Wikitext-Original bleibt deshalb vorhanden.
Lang geteilte Markdown-Ausdrücke/Codeblöcke können an Budgetgrenzen aufbrechen.
Dies ist kein vollständiger MediaWiki-Renderer; vor dem Voll-Lauf reale
Artikel-Stichproben einschließlich Fachartikeln prüfen.

`chunks.id` ist jetzt ein positiver, inhaltsbasierter **signed int64**, kein
laufender Zähler. Der volle SHA256-Schlüssel steht in `chunk_metadata.stable_key`.
Er enthält Konvertierungsversion, Artikel-ID, vollständige Abschnittspfade,
Chunk-Text und Vorkommensnummer für identische Wiederholungen. Die ID ist der
positive 63-Bit-Präfix (0 wird 1). Gleichbleibende Chunks behalten bei anderer
Importreihenfolge/Workerzahl/Resume ihre ID. Änderungen an Text, Pfaden oder
Merge-Grenzen können neue IDs erzeugen; keine Stabilitätsgarantie bei geänderten
Chunking-Parametern. 63-Bit-Kollisionen werden durch den Primärschlüssel erkannt
und führen zum Abbruch. Parser-/Konvertierungsänderungen erfordern eine neue
Pipeline-/Chunkversion. IDs nie als JavaScript-Gleitkommazahlen speichern;
den SHA256-String oder int64/BigInt verwenden.

## Datenformate und Übergabe an den Embedding-Agenten

SQLite `PRAGMA user_version=1`; vorhandene Leser bleiben kompatibel:

| Tabelle | Inhalt |
| --- | --- |
| `chunks` | Unverändert `id, article_id, title, heading, source, text_zlib`; Quelle bei XML ist die Revisions-URL `oldid`, soweit vorhanden |
| `chunks_fts` | Contentless FTS5/unicode61; ROWID = Chunk-ID |
| `chunks_fts4` | Optionaler Android-Fallback; enthält zusätzlichen Klartext und braucht erheblich mehr Platz |
| `articles` | `id, title, title_key, source, revision_id, timestamp, revision_source, history_source, license, sha1, redirect_target, wikitext_zlib, markdown_zlib` |
| `redirects` | `article_id, target_title, target_fragment`; Ziel für indexierte Titelabfragen normalisiert |
| `chunk_metadata` | `chunk_id, stable_key, ordinal` (ab 0 je Artikel), `section_paths` (JSON-Liste vollständiger Pfade), `estimated_tokens` |
| `ingest_errors` | absolute Quellposition, Artikel-ID, Titel und Fehlermeldung |
| `metadata` | Schema, Pipelineversion, Herkunft/Fingerprint, Optionen, Checkpoint, Status und Vektorziel |

Alle komprimierten Felder: UTF-8 in einem zlib-Stream, kompatibel zu
`zlib.decompress` und Android `InflaterInputStream`. Artikel enthalten den exakten
XML-Textwert als Wikitext (XML-Entities dekodiert) sowie sauberen gesamten Markdown.
`sha1` ist die vom Export gelieferte Revisions-Prüfsumme, unverändert gespeichert.
`title_key` normalisiert Unterstriche/Leerzeichen und den ersten Buchstaben nach
der üblichen deutschen MediaWiki-first-letter-Regel. Es ist kein allgemeiner
mehrsprachiger MediaWiki-Titelnormalisierer.

Weiterleitungen behalten eigene ID, Titel, Original und Quelle, bekommen aber
keine duplizierten Inhaltschunks/Vektoren. `rag.wiki.resolve_redirect(db, title)`
löst Ketten mit indexierten SQLite-Lookups auf und meldet `resolved`, `missing`,
`cycle` oder `depth_exceeded`; Fragmente bleiben erhalten. Alias-Suche ist damit
für nachfolgende Tools vorbereitet, wird nicht zusätzlich in den bestehenden
Android-FTS-Suchpfad eingebaut.

Das bisherige JSONL-Format bleibt nutzbar (`.jsonl`/`.jsonl.bz2`): ein Objekt je
Zeile mit Strings `id`, `title`, `text` (Wikitext), `source`; optional Strings
`revision_id`, `timestamp`, `revision_source`, `history_source`, `license`, `sha1`,
`model` (= `wikitext`), `redirect_target`. IDs müssen innerhalb der Quelle eindeutig
sein. Die Pipeline ergänzt daraus dieselben Artikel-/Chunk-Tabellen. JSONL ist
kein notwendiges Zwischenformat für XML und wird beim Dump-Lauf nicht erzeugt.

Der bestehende Embedding-Leser kann unverändert streamen:

```sql
SELECT id, title, text_zlib FROM chunks ORDER BY id;
```

`metadata.vector_target_dimension=256` und `vector_target_dtype=int8` beschreiben
nur das Ziel. Es werden weder Vektordateien noch `embedding_model` oder echte
Modellmetadaten erzeugt. Der separate Agent kann direkt die bestehenden
`rag embed`-/IVF-Schnittstellen verwenden. Erst bei `ingest_status=complete` und
geprüftem Fehlerjournal vektorisieren; danach DB und Vektoren gemeinsam einfrieren.
Ein technischer Kompatibilitätstest nutzt ausschließlich den bereits vorhandenen
`hash-v1`-Fixture-Encoder, keine EmbeddingGemma-Integration.

Artikel-/Revisions-/Versionsgeschichts- und CC-BY-SA-Links sind für Attribution
vorhanden; eine einzelne aktuelle Revision enthält nicht die vollständige
Autorengeschichte. Bei Weitergabe Lizenzbedingungen und Attributionsanforderungen
für den konkreten Snapshot gesondert erfüllen. Die Pipeline ersetzt das nicht.

## Tests und reproduzierbare Messungen

```sh
python -m unittest discover -s rag/tests -v
python -m rag.benchmark_ingest --articles 60 300 --workers 1 4 --work-dir work/wiki-benchmark --output rag/output/host-ingest.json
```

Der Benchmark schreibt kleine synthetische XML-Replicas inkrementell und startet
jeden Lauf in einem neuen Prozess. Er misst Laufzeit inkl. Start/FTS-Schreiben,
Artikel/s, MiB/s, DB-Größe, komprimierte Nutzdaten, beobachteten Platzbedarf
inkl. WAL und alle 20 ms die RSS-Summe aus Importer und Kindprozessen. Geteilte
Seiten werden je Prozess gezählt, kurze Spitzen können fehlen; Cachezustand ist
unkontrolliert. Der Generator lädt nur die drei Fixture-Vorlagen, keinen großen
Korpus. Vor Wiederholung neue Ausgabepfade verwenden.

Der Workflow `.github/workflows/rag-pipeline.yml` führt dieselben kleinen Tests
und einen CLI-Import/Resume auf Ubuntu und Windows mit Python 3.12 aus. Seine
Ergebnisse sind getrennt von den hier dokumentierten lokalen Host-Messungen.

Gemessene Resultate und Grenzen stehen in
[validation/wikipedia/README.md](validation/wikipedia/README.md), die Einzelwerte
in [host-ingest.json](validation/wikipedia/host-ingest.json). Keine Voll-Wikipedia-,
Windows-, A55- oder RTX-4060-Ausführung wurde für diese Änderung behauptet.
