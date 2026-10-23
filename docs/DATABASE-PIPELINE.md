# Lokale Datenpipeline

Ausgangspunkt: `main` 29fb2099b0518a4c11033f9f96ce7eb15a728c3c,
App 0.5.1.1. Die sechs vereinbarten Wissensbereiche bleiben getrennte Pakete.
Keine OSM-Integration. Fortschritt und Abnahme: [DATABASE-VALIDATION.md](DATABASE-VALIDATION.md).

## Technischer Ist-Bericht vor den Erweiterungen

`rag/corpus.py` hatte bereits Streaming, Prozesspool, komprimierte Originale,
FTS4/5, stabile IDs und transaktionale Import-Checkpoints. `rag/embeddings.py`
schrieb dagegen eine nicht wiederaufnehmbare Ausgabedatei. `rag/ivf.py` verwendete
zusätzlich eine vollständige normalisierte Float-Datei, Labels, Sortierpermutation
und temporäre Integer-Konvertierungen über den gesamten Bestand. Android las
einen einzelnen RAG-Bestand und Wikidata über feste interne Pfade. Ein
Release-Paketimport sowie Wörterbuch-/Tabellentools fehlten. Die vorhandene
Wikidata-Pipeline bleibt Grundlage; sie ist kein bereits erzeugter 7-GB-Bestand.

Echte Artikel zeigten verlorene Inhaltstabellen, Infoboxwerte, ZahlExp-Vorlagen
und zusammengezogene Exponenten. Der Konverter erhält diese jetzt einschließlich
komprimierter Originale und Vorlageninventar. Unbekannte Vorlagen werden nicht
remote expandiert; die Inventare machen verbleibende Extraktionslücken sichtbar.

## Windows-Einstieg

PowerShell im Repository-Stamm, Python 3.11 x64:

```powershell
python -m venv ../pipeline-venv
$py = (Resolve-Path ../pipeline-venv/Scripts/python.exe).Path
& $py -m pip install torch==2.14.1+cu130 torchvision==0.29.1+cu130 --index-url https://download.pytorch.org/whl/cu130
& $py -m pip install -r database_pipeline/requirements.txt
$env:BONSAI_DATA_ROOT = (Join-Path (Split-Path (Get-Location) -Parent) 'data')
.\scripts\database.ps1 -Python $py preflight
.\scripts\database.ps1 -Python $py download dewiki 20261001
.\scripts\database.ps1 -Python $py model
.\scripts\database.ps1 -Python $py status
```

Der Datenpfad liegt außerhalb des Git-Checkout. Alternativ `-DataRoot D:/Bonsai/data`.
Das Skript verändert keine persönlichen Daten und löscht keine Generationen.
Downloads verwenden HTTPS mit Zertifikatsprüfung, Resume und offizielle
Prüfsummen. Wikimedia-Snapshotmetadaten und Verifikationsbelege liegen in
`manifests/`. Python-Zertifikatsprobleme wurden mit Windows-curl umgangen,
ohne TLS-Prüfungen auszuschalten.

```powershell
$root = $env:BONSAI_DATA_ROOT
$tokenizer = "$root/models/embeddinggemma-2-914f7f89142e33e77833254d9c9b90c3cef7303b/tokenizer.json"
& $py -m database_pipeline import "$root/downloads/dewiki-20261001-pages-articles-multistream1.xml-p1p297012.bz2" "$root/staging/pilot.sqlite" --limit 1000 --workers 4 --tokenizer $tokenizer --dump-sha256 54a633868cbeb610eba877c307e8134799bcd7ab55b70a4271f65e71b1138327
# Gleiche Quelle/Optionen, höheres Gesamtlimit; keine Vektorisierung dieser DB vorher:
& $py -m database_pipeline resume "$root/downloads/dewiki-20261001-pages-articles-multistream1.xml-p1p297012.bz2" "$root/staging/pilot.sqlite" --limit 10000 --workers 4 --tokenizer $tokenizer --dump-sha256 54a633868cbeb610eba877c307e8134799bcd7ab55b70a4271f65e71b1138327
& $py -m database_pipeline embed "$root/staging/pilot.sqlite" "$root/embeddings/pilot" --model-path (Split-Path $tokenizer) --device cuda --batch 2
& $py -m database_pipeline assemble "$root/embeddings/pilot" "$root/indexes/pilot"
& $py -m database_pipeline index "$root/indexes/pilot/reference.f32" "$root/indexes/pilot/knowledge.ivf" --clusters 64 --backend faiss
& $py -m database_pipeline validate "$root/staging/pilot.sqlite" --ivf "$root/indexes/pilot/knowledge.ivf" --report "$root/benchmarks/pilot-validation.json"
```

Das ist der gemessene Pilotpfad. 64 Cluster sind keine Wahl für Voll-Wikipedia.
Import und Embedding niemals gleichzeitig auf dieselbe DB ansetzen. Ein neuer
Konverter-/Tokenizerstand erfordert eine neue Generation; kein Vermischen alter
Chunks und neuer Vektoren. Ein Importfehler verhindert die Paketfreigabe.

## Direkter Indexbau ohne zweite Float32-Vollkopie

Nach vollständigem Shard-Export kann `index` direkt dessen Verzeichnis lesen:

```powershell
& $py -m database_pipeline index "$root/embeddings/pilot" "$root/indexes/pilot-direct.ivf" --clusters 64 --backend faiss
$embedding = (Get-Content "$root/embeddings/pilot/export.json" -Raw | ConvertFrom-Json).identity
$ggufHash = (& $py -m database_pipeline hash "$root/models/embeddinggemma-2-Q8_0.gguf" | ConvertFrom-Json).sha256
$embedding | Add-Member -NotePropertyName query_gguf_sha256 -NotePropertyValue $ggufHash
$embedding | ConvertTo-Json -Depth 10 | Set-Content "$root/manifests/pilot-embedding.json" -Encoding utf8
```

Vier mmap-Shards bleiben gleichzeitig offen; Trainings-/Zuweisungsbatches sind
begrenzt. Der Herkunftsbeleg enthält den SHA256 derselben virtuellen BRAGF001-
Bytefolge, die `assemble` erzeugen würde. Der echte 17.285er-Faiss-Index war
auf beiden Wegen byteidentisch (SHA256 `0c2be3a052b258b6ed50bf01b4421577670a0655715683c2ecf246e3ce0b8129`).
Für diesen Weg sind weder eine zweite `.f32`-Vollkopie noch zusammengesetzte
`vectors.i8` nötig. Die ursprünglichen Shards bleiben für Resume und Prüfung.

## Weitere Adapter

- Wikibooks: derselbe Import mit `--wiki dewikibooks`; `book_structure` hält
  Buch und vollständigen Unterseitenpfad. Die URLs zeigen auf Wikibooks.
- Stack Exchange: `stack-download SITE`, `stack-extract ARCHIVE DIRECTORY`,
  `stack-select POSTS OUTPUT --site SITE`, `stack-export SELECTED OUTPUT --site SITE --users USERS`.
  Anschließend JSONL mit `--wiki stackexchange` importieren. Fragen mindestens
  Score 5, vorhandene akzeptierte Antwort, nicht geschlossen/gelöscht; höchstens
  konfigurierte Fragezahl nach Score/ID. Antworten: nicht negativ und akzeptiert
  oder Score mindestens 5. Stack Overflow zusätzlich technische Tag-Auswahl im
  Adapter. Codes/LaTeX bleiben erhalten, Urheber und ContentLicense pro Post.
  `stack-select` akzeptiert direkt die `.7z` anstelle von `Posts.xml`: zwei
  Streaming-Dekompressionen, begrenzte Queue, CRC-Prüfung und keine entpackte
  Vollkopie. `--users` beim Export akzeptiert ebenso das Archiv mit `Users.xml`.
- Wiktionary: `dictionary SOURCE OUTPUT --date YYYY-MM-DD`, Fortsetzung `--resume`.
  Hauptnamensraum, Sprache/Wortart/Bedeutungen, Flexionsformen, Beziehungen,
  Übersetzungen mit Bedeutungsmarkern; Rohseiten bleiben komprimiert.
  Alte/Schweizer Schreibweisen und Weiterleitungen werden als explizite Aliase
  aufgelöst; Ketten sind auf vier Schritte und 256 Kandidaten begrenzt. Seiten
  ohne strukturierbare Wortart bleiben über einen gekennzeichneten Rohtextzugang
  auffindbar. `dictionary-upgrade DUMP OLD_DB NEW_DB --dump-sha256 SHA256`
  erweitert eine ältere Generation, bei Unterbrechung mit `--resume`.
- NIST: `constants ALLASCII OUTPUT --date YYYY-MM-DD`.
- OFF: `nutrition JSONL_GZ OUTPUT --date YYYY-MM-DD`, Fortsetzung `--resume`.
  Deutscher Markt, keine Umdeutung fehlender Nährwerte zu Null.
- Wikidata: vorhandener Einstieg `python -m wikidata --help`. Auswahl,
  vollständige Dumpprüfung und 7-GB-Budget vor Produktivfreigabe prüfen.
  `wikidata-download 20261019` übernimmt den datierten offiziellen Entity-Dump,
  Resume und offizielle SHA1-Prüfung plus lokalen SHA256-Beleg.
  `wikidata-profile OUTPUT --date YYYY-MM-DD` prüft Klassen-IDs und bereitet
  deren explizite P279-Vorfahrenliste anhand offizieller API-Metadaten vor.
  Der eingecheckte Startbestand `wikidata/profiles/broad-de-v1.json` umfasst
  30 direkte P31-Klassen und 466 explizite Klassen-/Referenz-/Beispielentities.
  Fakten stammen anschließend aus dem datierten Dump; tatsächliche Auswahlgröße
  und Referenzabdeckung sind vor Freigabe zu messen.
  `wikidata-import SOURCE OUTPUT --config PROFILE --receipt RECEIPT --date YYYY-MM-DD`
  hält eine Schreibsperre, prüft Vollhash und Snapshotbeleg und verwendet die
  vorhandene Pipeline mit 7.000.000.000-Byte-Limit. Fortsetzung: `--resume`.
  Optional `--bzip2-threads 4` nutzt `indexed-bzip2==1.7.0` mit begrenzter
  Parallelität. Der zusätzliche `*.bz2-index.json` ist an die unveränderte
  Quelle gebunden und enthält eine Prüfsumme der Blockpositionen. Nur ein
  vollständiger Blockindex wird geladen: Die 1.7-API interpretiert einen
  Teilindex sonst als Dateiende. Vor Abschluss des ersten Durchlaufs wird
  ein Resume deshalb weiterhin ab Anfang dekomprimiert. Nach vollständigem
  ersten Durchlauf beschleunigt der Index den zweiten Pass und dessen Resume.
  Ein echter 256-MiB-Ausschnitt des verifizierten Wikipedia-Dumps war bei beiden
  Decodern bytegleich; gemessen 19,234 s Standard gegen 6,141 s mit vier Threads
  bei gleichzeitig laufenden Arbeiten. Kein daraus abgeleiteter Vollbestand-Benchmark.
  Bibliothek: https://github.com/mxmlnkn/librapidarchive.

Begleitende CSS-/JavaScript-Seiten im Hauptnamensraum werden mit ID, Titel und
Inhaltsmodell in `skipped_pages` protokolliert. Für einen alten, vollständig
beendeten Import mit genau solchen Fehlern erzeugt `repair-noncontent SOURCE
DATABASE NEW_DATABASE --wiki dewikibooks` eine eigene Generation: Quellhash,
identische Position/ID/Titel und konkrete CSS-/JS-Klassifikation müssen passen.
`repair_audit` erhält die ursprünglichen Fehler; die alte DB wird nicht verändert.

`database_pipeline.measure.measured` erfasst echte Unterprozess-Läufe, RSS-Summe,
Dateigrößen und Laufzeit. RSS ist abgetastet und zählt gemeinsame Seiten teilweise
mehrfach. Die GPU-Peaks der Embedding-Shards stammen aus PyTorch.

## Speicher und Wiederaufnahme

`downloads`, `raw`, `staging`, `embeddings`, `indexes`, `packages`, `manifests`,
`checkpoints`, `logs`, `benchmarks` sind separate Unterverzeichnisse. Vor Downloads,
Index- und Paketbau wird freier Speicher geprüft. Vor dem Vollimport ist zusätzlich
die aus 100k gemessene DB-/Chunk-Skalierung gegen die verbleibende SSD zu prüfen.
Der laufende Wikipedia-Gesamtdownload ist keine Bestätigung eines Vollimports.
`import`/`resume` prüfen an jedem bestätigten Checkpoint erneut die freie SSD-
Reserve (Standard 20 GiB, `--reserve-gib`). Bei Unterschreitung stoppt der Lauf
nach dem Commit und ist nach Freigabe von Speicher wiederaufnehmbar.
`embed` prüft dieselbe konfigurierbare Reserve vor jedem neuen Shard; bereits
bestätigte Shards bleiben bei Platzmangel gültig. Eine andere Speicherreserve
ändert die Embeddingidentität nicht.

Embedding-Shards werden erst nach fsync beider Dateien, Hashprüfung und atomarem
Checkpoint wiederverwendet. Änderungen der Corpus-Datei, Modellrevision oder
Shardgröße werden abgewiesen. Fehlerlogs und nicht bestätigte Teildateien bleiben
zur Diagnose erhalten. `embed` setzt automatisch fort; `assemble` überschreibt
keine vorhandenen fertigen Ausgaben. IVF trainiert mit begrenzter Stichprobe,
legt Labels auf Disk ab und sortiert nur einzelne Batches. Kein vollständiges
N-Array für die Sortierpermutation, keine temporäre normalisierte Vollkopie.

Nach einem abgeschlossenen, erneut vollständig gehashten Wikimedia-Download
können ausschließlich seine eigenen redundanten Downloadfragmente bereinigt
werden: `download-cleanup dewiki 20261001`. Der Befehl prüft vor jedem Löschen
zuerst den gesamten Plan: offizielle Vollquittung, Pfadgrenzen, Shardquittungen
und bytegleichen Abschnitt der erhaltenen vollständigen Datei. Ein veränderter
Teil verhindert die gesamte Bereinigung. Rohdump, Quittungen, Header und alle
Datenbank-/Embeddinggenerationen bleiben erhalten. Der konkrete Lauf gab
11.618.922.349 Bytes frei; sein zeitgestempelter JSON-Beleg liegt in `logs/`.
