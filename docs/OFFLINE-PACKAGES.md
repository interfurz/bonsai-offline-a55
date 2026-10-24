# Offline-Paketvertrag v1

`.bonsai` ist ein ZIP64-Container. Erster Eintrag: UTF-8 `manifest.json`, maximal
256 KiB. Danach ausschließlich deklarierte flache Dateien: `knowledge.sqlite`,
`knowledge.ivf`, optional `vectors.i8`, `sources.json`, `LICENSES.txt`.
Der Host schreibt ZIP_STORED und streamt große Dateien; Android begrenzt jede
extrahierte Datei auf ihre deklarierte Größe und prüft SHA256 und ZIP-CRC.
Keine importierte Properties-Datei kann Pfade oder ausführbare Tools definieren.

Pflichtfelder:

```json
{
  "format": "bonsai-offline-package",
  "format_version": 1,
  "package_id": "dewiki-de",
  "version": "20261001-pilot-1k-v2",
  "type": "rag",
  "schema_version": 1,
  "source": {"dataset":"dewiki", "corpus":"wikipedia", "url":"https://dumps.wikimedia.org/dewiki/20261001/", "date":"2026-10-01", "license":"CC-BY-SA-4.0"},
  "embedding": {"model":"google/embeddinggemma-2", "revision":"914f7f89142e33e77833254d9c9b90c3cef7303b", "profile":"embeddinggemma2-search-v1", "dimension":256, "query_gguf_sha256":"2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135"},
  "counts": {"documents":1000, "chunks":17285, "records":1000},
  "integrity": {"integrity":"passed"},
  "files": [{"name":"knowledge.sqlite", "bytes":0, "sha256":"REQUIRED_ACTUAL_SHA256"}]
}
```

Das JSON illustriert Felder, ist kein installierbares Manifest. Größen/Hashes
werden ausschließlich vom Paketbauer ermittelt. Typen: `rag`, `wikidata`,
`dictionary`, `tables`. Strukturierte Pakete enthalten keine Vektoren.
`source.corpus` routet Textpakete nach `wikipedia`, `wikibooks`, `stackexchange`.

Zusätzlich bindet `lineage` den SHA256 der unveränderten Quell-DB, die
Embedding-Identität und den tatsächlichen Indexhash aneinander. `assemble`
verifiziert die zusammengesetzten Bytes gegen die bestätigten Shards und
schreibt `assembly.json`; der Indexbauer bindet dieses Ergebnis in `.ivf.json`.
Ohne diese Kette lehnt der Paketbauer einen RAG-Export ab. Gleiche Chunk-IDs
allein belegen keine Zugehörigkeit eines Vektors zum richtigen Text.

Die Chunk-Identität lautet `(package_id, chunk_id)`. Der Host prüft beide
Richtungen der DB-/Index-ID-Mengen, Duplikate und Anzahl. IVF bleibt `BRAGIVF1`:
256D INT8, Float32-Zentroiden, globaler Scale, positive signed-int64-IDs.
Ein Modellname allein genügt nicht: Profil, HF-Revision und Query-GGUF-Pin
müssen gemeinsam passen. Android akzeptiert derzeit genau das nachgewiesene Paar.

RAG-Pakete können `"search":{"nprobe":64}` deklarieren. Zulässig sind ganze
Zahlen von 1 bis 256 und höchstens die tatsächliche Clusterzahl. Ohne dieses
optionale Feld bleiben bisherige Pakete bei 16. Android übernimmt den geprüften
Wert pro Paket; es werden keine Pfade oder ausführbaren Einstellungen importiert.
Paketbau: `package ... --nprobe 64`. Die Wahl gilt nur für den gemessenen Index.

Der portable FTS4-Pfad sortiert seit dieser Erweiterung nach `fts4-bm25-v1`
statt nach Chunk-ID. Bis zu 32 Anfragewörter, deutsche/englische Funktionswörter
ausgenommen, werden anhand FTS4-Statistiken geordnet; höchstens zwölf gehen in
die lexikalische Kandidatensuche ein. Maximal 65.536 Kandidaten werden gestreamt,
ein Heap hält nur die besten 20. Dokumentlänge und Termhäufigkeiten kommen aus
`matchinfo('pcnalx')`; die Dokumentfrequenz pro Term wird als Maximum der drei
Spalten angenähert. Das ist kein identischer FTS5-BM25-Algorithmus. Formatquelle:
https://www.sqlite.org/fts3.html#matchinfo. Host- und Android-Algorithmus teilen
Normalisierung, Stopwörter, Grenzen, Formel und stabile ID-Auflösung.

## Paket bauen

Die vollständigen Befehle sind über `python -m database_pipeline --help`
und `scripts/database.ps1` verfügbar. Beispiel nach Import/Embedding/Index:

```powershell
& $py -m database_pipeline package $database $package --id dewiki-de --version 20261001-pilot-1k-v1 --kind rag --source-json $sourceJson --ivf $index --embedding-json $embeddingJson
```

`embeddingJson` stammt aus dem tatsächlich fertiggestellten Shard-Export und
enthält zusätzlich `query_gguf_sha256` wie oben. Sein Corpus-SHA256 muss zur
unveränderten Eingabe-DB passen. Der Paketbauer kopiert die DB, ergänzt
Paket-/Embedding-Metadaten und verdichtet eine Android-kompatible FTS4-Version.
Der Host-Korpus behält FTS5 und FTS4. Damit benötigt der Release-Importer keine
auf allen Android-Geräten vorhandene FTS5-Erweiterung. FTS4-Suchergebnisse
verwenden den bestehenden Android-Fallback; repräsentative Android-Qualität
bleibt gesondert zu messen.

Daneben entstehen Manifest und SHA256-Verifikationsbeleg. Vorhandene fertige
Pakete werden nicht überschrieben. Die SHA256 des gesamten Archivs ermöglicht
die Prüfung nach USB-Kopie. Hashes belegen Integrität, keine Herausgeberidentität.

## Installation ohne ADB/run-as

Benötigt wird eine APK aus diesem Featurestand mit dem Paketmenü. Die bereits
veröffentlichte 0.5.1.1 enthält diese Erweiterung noch nicht.

1. `.bonsai` und für Textsuche das geprüfte Q8_0-GGUF per USB auf das A55 kopieren.
2. Bonsai: Seitenmenü → **Offline-Wissenspakete** → **Wissenspaket importieren**.
3. Lokale `.bonsai`-Datei im Android-Dateidialog auswählen. Während großer
   Importe die Importansicht im Vordergrund lassen.
4. **EmbeddingGemma-2-Query-Modell importieren** und das oben gepinnte GGUF
   auswählen. Das generative Bonsai-Modell wird dadurch nicht ersetzt.
5. Einzelne Pakete über ihre Schalter aktivieren/deaktivieren. Flugmodus
   aktivieren und Tools/RAG gemäß Validierungskatalog prüfen.

Die App fordert keine INTERNET- oder allgemeine Speicherberechtigung an.
SAF stellt den ausgewählten Stream bereit. Es gibt keine Root-/Debug-Voraussetzung.
Ein Quellstand-Release wurde lokal gebaut; Signieren mit der bestehenden
Updateidentität und die tatsächliche A55-Abnahme sind eigene offene Schritte.
Keine Deinstallation empfehlen, um ein Signaturproblem zu umgehen: sie würde
private Modelle, Chats, Gedächtnis und installierte Pakete löschen.

## Transaktionale Aktivierung

Jeder Import bekommt ein neues internes UUID-Verzeichnis mit `.importing`-Marker.
Nur `knowledge/active.json` entscheidet, welche Generation sichtbar ist. Größen,
freier Speicher (inklusive ID-Baum und Reserve), Hashes, Schema, SQLite-Integrität,
FTS-/Chunk-IDs und IVF-/Chunk-IDs werden vor Aktivierung geprüft. ID-Prüfung
verwendet einen temporären SQLite-Baum, keine vollständige Java-ID-Menge.
Zusätzlich werden die für die festen Laufzeitabfragen nötigen Tabellen und
Spalten vor Aktivierung kompiliert. Korrekte Datei-Hashes und eine behauptete
Schema-Version allein reichen nicht. Fehlende Spalten verhindern das Update;
die alte aktive Generation bleibt erhalten.

FTS4 klassifiziert seine interne Integritätsoperation als Schreiboperation. Nur
die noch nicht veröffentlichte Staging-Datei wird dafür schreibbar geöffnet;
anschließend wird ihr SHA256 nochmals gegen das Manifest geprüft. Leser öffnen
aktivierte Datenbanken ausschließlich read-only. Kein Modell kann SQL liefern.

Erst danach wird die fsync-geschriebene Registry mit einem verpflichtend atomaren
Dateiumbenennen ersetzt. Keine schwächere Delete-then-Rename-Rückfalllösung.
Generation-Dateien werden niemals in-place ersetzt. Alte Generationen bleiben
erhalten, auch wenn ein Import abbricht oder fehlschlägt. Die Staging-Dateien
bleiben zur Diagnose; über **Unvollständige Importe entfernen** können sie nach
ausdrücklicher Bestätigung entfernt werden. Registrierte Generationen sind davon
ausgenommen. Ein Prozessabbruch setzt den Streamimport nicht automatisch fort;
der nächste Import beginnt eine neue Generation, die bisherigen Pakete bleiben.

Die Aktivierung ist nicht mit der Freigabe der Datenqualität gleichzusetzen.
Ein gekennzeichneter Wikipedia-Pilot bleibt ein Pilot, auch nach bestandenem
Hash-/Integritätscheck. Ein veralteter OFF-Snapshot bleibt veraltet.

Die Release-Anbindung wurde auf dem Host zusätzlich mit dem vollständigen
4,59-GB-Wörterbuch und dem 492-MB-OFF-Paket geprüft. Paketgrößen werden durch
ZIP64 und Streaming verarbeitet; es gibt keine 2-/4-GB-Dateigrenze im Vertrag.
Der physische A55-Versuch bleibt erforderlich.

## Toolvertrag und importierte Inhalte

- `suche`: `query`, optional danach `corpus` (`all`, `wikipedia`, `wikibooks`, `stackexchange`).
- `wikidata`: vorhandene sechs feste Operationen und Abdeckungsgrenzen.
- `woerterbuch`: `word`, optional `language`; exakte normalisierte Wörter und Formen.
  Explizite Schreibvarianten/Weiterleitungen sind begrenzt auflösbar. Ein
  unstrukturierter Rohtexttreffer trägt einen eigenen Status und behauptet
  keine vollständig geparste Definition.
- `tabellen`: `op=constant|food` plus `name`, oder `op=convert` plus `value,from,to`.

Alle Argumente sind Strings. GBNF, Java-Validierung und Standardprompt sind
gemeinsam erweitert. SQL ist fest vorgegeben und parametrisiert; Antwortgrößen,
Zeiten und Datenmengen sind begrenzt. Ergebnisse bleiben Daten: Grenzmarkierungen
werden in JSON escaped, und nach Wissensabrufen kann die Toolrunde keine
Gedächtnisschreiboperation ausführen. Das ist eine konkrete zusätzliche
Schranke, kein allgemeiner Beweis gegen jede semantische Prompt Injection.
