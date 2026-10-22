# Offline-Wissenssuche (Schema v1)

Die Erweiterung für Millionen Vektoren steht in [IVF.md](IVF.md): Indexformat,
K-means-Bau, konfigurierbares nprobe/RRF, Recall-Vergleich sowie Host- und
Geräte-Benchmark. Die folgenden Befehle beschreiben weiterhin den kompatiblen
ursprünglichen Vollscan.

Aus dem Repository-Stamm ausführen, Python >=3.10. Keine Downloads beim Import
oder in Tests. Die 50 eingecheckten, selbst verfassten Miniartikel sind **keine
Wikipedia-Extraktion**; `fixture://` kennzeichnet diese Testdaten. Der Hash-Encoder
ist nur für die technische Prüfung gedacht, nicht für semantische Suchqualität.

## Kleiner Lauf und Tests

```sh
python3 -m venv rag/.venv
. rag/.venv/bin/activate
pip install -r rag/requirements.txt
python3 -m unittest discover -s rag/tests -v
mkdir -p rag/output
python3 -m rag ingest rag/data/articles.jsonl rag/output/knowledge.sqlite
python3 -m rag embed rag/output/knowledge.sqlite rag/output/vectors.i8 \
  --model hash-v1 --reference rag/output/reference.f32
python3 -m rag search rag/output/knowledge.sqlite 'Photosynthese Pflanzen' \
  --vectors rag/output/vectors.i8
python3 -m rag.compare rag/output/knowledge.sqlite rag/output/vectors.i8 \
  rag/output/reference.f32 rag/data/questions.json > rag/output/comparison.json
```

`rag/sample.py` regeneriert die JSONL-Testdaten. Die Java-Testressourcen werden
mit denselben `ingest`/`embed`-Befehlen erzeugt (Ziel
`app/src/test/resources/rag/`, ohne `--reference`). Vor dem Regenerieren die alten
Ausgaben entfernen. Die Pipeline überschreibt vorhandene Ausgaben absichtlich
nicht. Bei einem abgebrochenen Lauf die unvollständigen Ausgaben löschen und
neu starten; erst vollständig erzeugte Dateien zusammen auf das Gerät kopieren.

## Echter Lauf auf dem Laptop / RTX 4060

Den aktuellen deutschen **pages-articles-multistream.xml.bz2**-Dump manuell von
<https://dumps.wikimedia.org/dewiki/> laden, Prüfsumme kontrollieren und Namen,
Datum sowie Prüfsumme für die spätere Bewertung aufbewahren. Kein automatischer
Dump-Download. XML und bz2 werden sequentiell verarbeitet; `--limit 50` beendet
den Parser nach 50 Hauptnamensraum-Artikeln, ohne den vollen Dump einzulesen.
Weiterleitungen und andere Namensräume werden übersprungen.

```sh
pip install -r rag/requirements-embeddings.txt
# Bei Bedarf PyTorch mit zur lokalen CUDA-Installation passendem Wheel installieren.
# Modellgewichte werden nur auf dem Laptop heruntergeladen, nie von der Android-App.
python3 -m rag ingest /daten/dewiki-DATUM-pages-articles-multistream.xml.bz2 \
  rag/output/wiki.sqlite --limit 50
python3 -m rag embed rag/output/wiki.sqlite rag/output/wiki.i8 \
  --model google/embeddinggemma-2 --device cuda --batch 16 \
  --reference rag/output/wiki.f32
python3 -m rag search rag/output/wiki.sqlite 'Wie entsteht Sauerstoff in Pflanzen?' \
  --vectors rag/output/wiki.i8
python3 -m rag.compare rag/output/wiki.sqlite rag/output/wiki.i8 \
  rag/output/wiki.f32 /daten/testfragen.json > rag/output/wiki-comparison.json
# Für den vollständigen Dump --limit weglassen; eigene neue Ausgabepfade benutzen.
```

Standard ist `google/embeddinggemma-2`, text-only mit Float32 und deaktivierten
Vision-/Audio-Encodern. Queries erhalten exakt `task: search result | query: `,
Dokumente das Format `title: {Titel} | text: {Text}`. Das Exportprofil
`embeddinggemma2-search-v1` wird in der Datenbank hinterlegt. Alte 300M- und
Hash-Indizes müssen für EmbeddingGemma 2 vollständig neu erzeugt werden.
Andere Modellnamen bleiben möglich (Sentence Transformers, `trust_remote_code=False`);
deren `encode_document` und `encode_query` verwenden modellspezifische Prompts.
Die ersten 256 Dimensionen werden
L2-normalisiert, pro Vektor symmetrisch auf [-127,127] quantisiert und samt Scale
gespeichert. Sinnvolle Dimensionsreduktion hängt vom Modell ab (Matryoshka).
Die optionale Referenz speichert alle nativen Dimensionen L2-normalisiert als
Float32; der Vergleich erfasst also sowohl Dimensionsreduktion als auch
Quantisierung. Für eine isolierte INT8-Bewertung wäre zusätzlich eine
256D-Float32-Referenz erforderlich. Keine Qualitätsaussage aus dem Fixture ableiten.
Bei Speichermangel `--batch` reduzieren. CUDA-/RTX- und Voll-Dump-Läufe sind
getrennt von den Host-Tests zu validieren.

Chunking erfolgt nach Überschriftenhierarchie. Kleine angrenzende Abschnitte
werden unter ca. 80 geschätzten Tokens zusammengeführt; Abschnitte über ca. 800
werden weiter geteilt und ein kurzer Rest nach Möglichkeit ausbalanciert.
Budgetschätzung: `ceil(UTF-8-Bytes / 4)`, **nicht** der Bonsai-/Gemma-Tokenizer.
Wikicode wird mit mwparserfromhell von Vorlagen, Referenzen und Datei-/Kategorie-
Links bereinigt. Tabellen, mathematische Notation und komplexe Vorlagen können
Information verlieren; vor einem produktiven Import Stichproben prüfen.
Wikipedia-Lizenz, Attribution und Versionsgeschichte müssen bei einer
Weiterverteilung berücksichtigt werden; Artikel-URLs sind keine vollständige
Lizenz- oder Revisionsdokumentation.

## Gemeinsames Dateiformat

SQLite `user_version=1`:

- `chunks(id, article_id, title, heading, source, text_zlib)`; IDs deterministisch
  nach Importreihenfolge, nicht über verschiedene Dumps hinweg stabil.
- `text_zlib`: UTF-8, zlib-Stream (Java `InflaterInputStream`).
- `chunks_fts`: FTS5, `content=''`, unicode61; ROWID entspricht Chunk-ID.
- `chunks_fts4`: FTS4-Kompatibilitätsindex für Android-Systeme ohne FTS5.
  Dieser enthält zusätzlichen Klartext und kostet Speicher. Mit
  `ingest --no-android-fts4` nur für garantiert FTS5-fähige Laufzeiten weglassen.
- `metadata`: Schema, Modellname, reduzierte Dimension.

INT8-Datei: Little Endian. Header `BRAGV001` (8 Bytes), uint32 Dimension (=256),
uint32 Anzahl. Pro Datensatz: int64 Chunk-ID, float32 Scale, 256 signierte Bytes.
Rekonstruierter normalisierter Wert: `int8 * scale`. Query identisch quantisieren;
Skalarprodukt mit beiden Scales. Java scannt die Datei mit einem Top-20-Heap und
behält nicht alle Vektoren im RAM. Das ist ein Beispiel-/Baseline-Verfahren;
Brute Force über den vollen Wikipedia-Index ist auf dem A55 nicht validiert.
Die Referenz verwendet `BRAGF001`, Dimension/Anzahl und je int64 ID + Float32-Werte.

Beide Suchen bilden zunächst bis zu 20 Kandidaten. Python nutzt FTS5/BM25, Android
versucht FTS5/BM25 und fällt bei SQLite-Fehlern auf den FTS4-Index in ROWID-Reihenfolge
zurück. Reciprocal Rank Fusion: `sum(1 / (60 + Rang))`; positive Vektor-Scores
werden berücksichtigt, Gleichstände nach Chunk-ID entschieden. Kein Mindestscore
ist kalibriert: ein positiver Vektor-Score garantiert keinen relevanten Treffer.
Ausgabe maximal drei Treffer, jeweils 600 Zeichen Text, 160 Zeichen Überschrift/
Titel, 240 Zeichen Quelle. Selbst dieses Budget garantiert bei langen Chats und
ungewöhnlicher Tokenisierung keine Einhaltung von n_ctx=4096; die bestehende
Kontextverwaltung bleibt erforderlich. Suchresultate sind Daten, keine Instruktionen.

## Android konfigurieren

Standard: `<filesDir>/rag/knowledge.sqlite` und `<filesDir>/rag/vectors.i8`.
Optional `<filesDir>/rag/config.properties` (Java-Properties-Format):

```properties
database=knowledge.sqlite
vectors=vectors.i8
# Standardwerte für den echten lokalen Query-Encoder:
encoder_model=google/embeddinggemma-2
encoder_path=embeddinggemma-2-Q8_0.gguf
encoder_threads=4
```

Relative Pfade gelten ab `<filesDir>/rag/`, absolute Pfade müssen im App-internen
Speicher liegen. Beispiel für einen per USB angeschlossenen **Debug**-Build:

```sh
adb shell run-as de.bonsai.offline.debug mkdir -p files/rag
adb exec-in run-as de.bonsai.offline.debug sh -c 'cat > files/rag/knowledge.sqlite' < rag/output/knowledge.sqlite
adb exec-in run-as de.bonsai.offline.debug sh -c 'cat > files/rag/vectors.i8' < rag/output/vectors.i8
adb exec-in run-as de.bonsai.offline.debug sh -c 'cat > files/rag/embeddinggemma-2-Q8_0.gguf' < /daten/embeddinggemma-2-Q8_0.gguf
```

DB und Vektordatei müssen aus demselben Import/Embedding-Lauf stammen. Die Dateien
sind nur lesbar geöffnet und werden nicht mit der App ausgeliefert. Fehlt die DB,
kommt eine klare Tool-Fehlermeldung. Ohne Vektordatei funktioniert reine FTS-Suche.
`EmbeddingGemma2Encoder` führt das Textmodell über JNI auf der CPU aus. Die
native Runtime ist um die offizielle Architektur `gemma-embedding2` ergänzt
(siehe [Runtime-Backport](../runtime/README.md)). Die GGUF-Datei enthält Gewichte,
Tokenizer und die trainierte 512→768-Projektion. Mean Pooling einschließlich
Query-Prompt und BOS/EOS liefert 768 Werte; die ersten 256 werden anschließend
erneut L2-normalisiert. Damit funktionieren sowohl INT8-Vollscan als auch IVF/RRF.
FP16-Attention ist deaktiviert, da dieses Modell dort überlaufen kann.

Geeignete Text-GGUFs sind bei
[ggml-org/embeddinggemma-2-GGUF](https://huggingface.co/ggml-org/embeddinggemma-2-GGUF)
verfügbar. Gewichte manuell herunterladen und Prüfsumme prüfen. Der getestete
Q8_0-Stand hat Revision `58faf90940886df2d62be555c8765d00dac362c5` und SHA256
`2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135`.
Q8_0 ist eine Gewichtsquantisierung; der Korpus-Export läuft mit Float32.
Die resultierende Suchqualität muss mit dem eigenen Datensatz bewertet werden.

Das Modell wird erst während einer Vektorsuche geladen und samt Kontext vor
der nächsten LLM-Runde wieder freigegeben. Es gibt keinen zusätzlichen dauerhaft
residenten Encoder. Jede Suche bezahlt dadurch erneut Modellladezeit; während
der Suche benötigen Bonsai und Encoder gemeinsam RAM. Ladefortschritt und
CPU-Berechnung prüfen den vorhandenen Abbruchstatus. Encoder-Threads sind von
den Einstellungen des generativen Modells getrennt (1–8, Standard 4).

Fehlen Gewichte, passt der Modellname nicht oder fehlt das Exportprofil,
liefert das Tool FTS-Treffer mit Warnung und verwendet keine inkompatiblen
Query-Vektoren. Encoder-Fehler werden ebenfalls als FTS-Warnung ausgegeben;
Abbruch wird weitergereicht. Der `SampleEncoder` bleibt für bestehende
`hash-v1`-Testindizes automatisch verfügbar; explizit ist `encoder_model=hash-v1`
möglich. Das Vorgängermodell `google/embeddinggemma-300m` wird auf Android nicht
als EmbeddingGemma 2 behandelt.

Modellspezifikation: [Google-Modellkarte](https://huggingface.co/google/embeddinggemma-2).
Einrichtung und Testnachweise: [EmbeddingGemma-2-Validierung](VALIDATION-EMBEDDINGGEMMA2.md).

Toolformat: `<tool>{"name":"suche","args":{"query":"Berlin"}}</tool>`.
Die JSON-Treffer enthalten Text, Quelle, Überschrift und eine stabile
`citation`-Nummer innerhalb der Antwort. Quellen aus tatsächlich zurückgegebenen
Treffern werden an die Endantwort angehängt: bei `[n]`-Zitaten unter „Quellen“,
sonst transparent unter „Suchquellen“. Höchstens drei Treffer pro Tool-Aufruf (bei zwei Suchen höchstens sechs
Quellen pro Antwort); Tool-Anfragen und rohe JSON-Ergebnisse bleiben aus dem sichtbaren und
persistierten Verlauf heraus. Die Quellenliste überlebt den Chat-Neustart als
Teil der Endantwort. Sie beweist keine inhaltlich korrekte Verwendung durch das LLM.

## Validierung und Messungen

`compare` schreibt tatsächliche Dateigrößen, Host-Laufzeiten der Vektorsuche
(ohne Query-Embedding) und Top-3-Übereinstimmung. Fragenformat:
`[{"query":"…", "article_ids":["123"]}]`; IDs sind Artikel-IDs, keine Chunk-IDs.
Recall wird nur für Fragen mit Labels berechnet. Für echte Fragen zum gewählten
Dump eine eigene gelabelte Liste verwenden. Fünf Fixture-Fragen sind kein
repräsentativer Qualitätsbenchmark. Cachezustand ist nicht kontrolliert;
Einzelmessungen sind keine belastbaren Geschwindigkeitsvergleiche.

Änderungen/PR nach **gemessen**, **nur auf dem Host getestet**, **nur gebaut**
trennen. Keine A55-, RTX-4060-, Speicher- oder Qualitätswerte behaupten, die nicht
auf diesen Geräten mit dem betreffenden Datensatz ermittelt wurden.
