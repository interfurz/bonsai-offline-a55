# IVF-INT8, Format v1

Die Pipeline erzeugt einen Inverted-File-Index aus den **Float32-Vektoren** des
Embedding-Schritts (`BRAGF001`, optionales `embed --reference`). Sie übernimmt
nicht die bereits pro Vektor quantisierte alte Datei `BRAGV001`. Alle nativen
Modelldimensionen ab Position 256 werden verworfen, dann werden die ersten 256
Dimensionen erneut L2-normalisiert. Nullvektoren bleiben null.

K-means arbeitet mit diesen Float32-Vektoren und euklidischer Distanz. Die
Zentroiden bleiben **Float32**, ohne zusätzliche Normalisierung: eine erneute
Quantisierung würde die Clusterwahl verändern. 4096 × 256 × 4 Bytes ergeben
4 MiB Zentroidendaten (Formatgröße, keine RAM-Messung). Die Distanz zur Anfrage
wird als `dot(c,c) - 2*dot(c,q)` berechnet; `dot(q,q)` ist für alle Zentroiden
konstant. Gleiche Distanzen werden nach Cluster-ID aufgelöst.

Alle Dokument- und Query-Vektoren verwenden **denselben globalen Float32-Scale**,
standardmäßig `float32(1/127)`: normalisierte Komponenten liegen in [-1,1].
Dieser feste Wertebereich verhindert Sättigung bei spärlichen Queries, deren
Komponenten größer sein können als das gemessene Maximum des Korpus. Es gibt
keine individuellen Scales pro Vektor. Dies nutzt ggf. weniger INT8-Auflösung
als die alte Quantisierung und muss mit echten Modell-Queries bewertet werden.

Quantisierung: `clip(sign(x/scale) * floor(abs(x/scale)+0.5), -127, 127)`.
Rekonstruierte Komponente: `q8*scale`, Skalarprodukt: `int32_dot*scale²`.
Für die Rangfolge reicht das ganzzahlige Skalarprodukt. Der Maximalbetrag der
Akkumulation ist `256*127²` und passt in int32. Ranggleichstände: kleinere
Chunk-ID zuerst. Die Top-k-Auswahl nutzt einen Heap. Die Tool-Suche behält nur
positive Skalarprodukte; die Qualitätsmessung und beide Benchmarks lassen auch
Null-/Negativwerte zu, damit Top-10-Vergleiche wohldefiniert bleiben.

## Binärlayout

Eine Datei, Little Endian. Keine nativen Alignment-Annahmen. Header genau 128
Bytes; Version und Magic unterscheiden IVF ausdrücklich vom alten Vollscanformat.

| Byteoffset | Typ | Inhalt |
| ---: | --- | --- |
| 0 | char[8] | `BRAGIVF1` |
| 8 | uint32 | Version = 1 |
| 12 | uint32 | Headerlänge = 128 |
| 16 | uint32 | Dimension = 256 |
| 20 | uint32 | Clusterzahl K, mindestens 1 |
| 24 | uint64 | Vektorzahl N |
| 32 | float32 | Globaler Scale, endlich und > 0 |
| 36 | uint32 | Flags = 1: normalisierte Ausgangsvektoren oder Nullvektoren |
| 40 | uint64 | Offset der Cluster-Offset-Tabelle = 128 |
| 48 | uint64 | Offset der Float32-Zentroiden |
| 56 | uint64 | Offset der Chunk-ID-Tabelle |
| 64 | uint64 | Offset der INT8-Vektoren |
| 72 | uint64 | Gesamte Dateigröße |
| 80 | uint64 | Seed des Indexbaus |
| 88 | byte[40] | Reserviert, null |

Sektionen in dieser Reihenfolge:

1. `(K+1)` uint64-Positionen: `offsets[0]=0`, monoton, `offsets[K]=N`.
   Cluster i enthält Positionen `[offsets[i],offsets[i+1])`. Leere Cluster sind gültig.
2. `K*256` Float32-Werte in Cluster-/Dimensionsreihenfolge.
3. `N` int64-Chunk-IDs: Zuordnung **sortierte Position → ursprüngliche Chunk-ID**.
4. Nullpadding bis zur nächsten 4096-Byte-Grenze.
5. Exakt `N*256` signierte INT8-Werte, nach Cluster sortiert, ohne Record-Header.

Absolute Sektionenoffsets und Dateigröße werden aus N/K zusätzlich nachgerechnet;
Header, Flags, Scale, monotone Clustergrenzen und endliche Zentroiden werden beim
Öffnen geprüft. Es gibt keinen Payload-Checksum; die Validierung erkennt keine
beliebigen Bitfehler in plausiblen Vektordaten. Vollständige ID-/Vektorvalidierung
würde beim Öffnen einen Vollscan erfordern und findet daher nicht statt.

Python liest per `numpy.memmap` **read-only**. Android mappt Metadaten sowie
256-MiB-Vektorfenster mit `FileChannel.MapMode.READ_ONLY`. Eine 256-Byte-Zeile
überschreitet keine Fenstergrenze; Datenpositionen und Dateioffsets bleiben long.
Die Java-Metadatenmap ist auf `Integer.MAX_VALUE` Bytes begrenzt; größere Indizes
werden mit einer verständlichen Fehlermeldung abgewiesen. Die gesamten Vektoren
werden nicht in den Java-Heap geladen. Der Scan erzeugt keine Objekte pro Vektor;
Heap, Query- und Probe-Arrays entstehen einmal pro Anfrage. Die Mappings werden
von der JVM verwaltet und nicht durch nichtöffentliche Unmap-APIs freigegeben.

## Bau und Suche

```sh
pip install -r rag/requirements-ivf.txt
# Für große Korpora zusätzlich eine zur CPU/CUDA-Installation passende Faiss-Version.
# CPU-Beispiel: pip install faiss-cpu
mkdir -p rag/output
python3 -m rag embed rag/output/wiki.sqlite rag/output/wiki.i8 \
  --model google/embeddinggemma-300m --device cuda --reference rag/output/wiki.f32
python3 -m rag.ivf rag/output/wiki.f32 rag/output/knowledge.ivf \
  --clusters 4096 --train-size 262144 --backend faiss --device gpu --batch 8192
python3 -m rag search rag/output/wiki.sqlite 'Wie funktioniert Photosynthese?' \
  --ivf rag/output/knowledge.ivf --nprobe 16 --rrf-k 60
```

`--clusters` ist die gewünschte Clusterzahl. Bei N<K wird auf max(1,N) reduziert;
die tatsächliche Zahl steht im Header und im Bauprotokoll. `--train-size` begrenzt
die deterministisch ausgewählte Trainingsstichprobe und muss mindestens K sein.
Faiss empfiehlt deutlich mehr Trainingsvektoren als Zentroiden (typisch mindestens
39 pro Cluster); der 50-Artikel-Datensatz erfüllt das für große K nicht.
`--backend auto` versucht Faiss, sonst scikit-learn für höchstens 100.000
Trainingszeilen und 512 tatsächliche Cluster. Für größere Trainingsmengen ist
Faiss erforderlich. `--device cpu|gpu|auto` wählt die Faiss-Ausführung; GPU benötigt
eine passende CUDA-Faiss-Installation. scikit-learn ist ein CPU-Fallback, kein
GPU-Ersatz. GPU-Training wurde in dieser Arbeit nicht ausgeführt.

Normalisierte Floats werden temporär auf Disk gehalten (N×256×4 Bytes), zusätzlich
Trainingsstichprobe, Labels und Sortierpositionen. Die Assignment-Phase ist
gebatcht; Faiss nutzt einen L2-Index, der Fallback NumPy-Matrixprodukte.
`--batch` begrenzt die Zwischenmatrix und IO-Batches; `--seed` steuert Stichprobe
und Training. Die fertige Datei wird ohne Überschreiben per Hardlink publiziert;
Tempdatei und Ziel müssen auf demselben Dateisystem liegen. Beispiele sind für
Linux/WSL; der Lauf wurde auf Linux geprüft. Fehlgeschlagene Bauten publizieren
keinen halbfertigen Index.

`--nprobe` ist die Anzahl der nächsten Cluster, die gescannt werden. Größer ist
langsamer und meist vollständiger, kleiner ist schneller und kann relevante
Treffer verlieren. Werte über K werden auf K begrenzt; `nprobe=K` entspricht dem
INT8-Vollscan. Ein Einsekundenziel lässt sich daraus ohne Gerätemessung nicht
ableiten. `--rrf-k` steuert `sum(1/(rrf_k+Rang))`, Rang ab 1, Standard 60;
FTS5-/Vektorkandidaten werden damit wie zuvor zusammengeführt.

### Kleiner Lauf ohne Modell-Download

```sh
python3 -m rag ingest rag/data/articles.jsonl rag/output/ivf-demo.sqlite
python3 -m rag embed rag/output/ivf-demo.sqlite rag/output/ivf-demo.i8 \
  --model hash-v1 --reference rag/output/ivf-demo.f32
python3 -m rag.ivf rag/output/ivf-demo.f32 rag/output/ivf-demo.ivf \
  --clusters 32 --backend sklearn
python3 -m rag.evaluate_ivf rag/output/ivf-demo.sqlite rag/output/ivf-demo.f32 \
  rag/output/ivf-demo.ivf rag/data/ivf_questions.json \
  --nprobe 4 8 16 32 --output rag/output/ivf-quality.json
```

Die 100 eingecheckten Fragen entstehen aus zwei Themenvorlagen pro Fixture-Artikel.
Sie testen die Datenstrecke, keine paraphrasenreiche Wissenssuche. Die Tabelle
zeigt sowohl gelabelten Artikel-Recall@10 als auch Übereinstimmung mit den exakten
Float32-256D-Top-10; die Float-Zeile hat beim zweiten Wert definitionsgemäß 1.
Die Float-Baseline verwendet dieselben 256 Dimensionen, damit Dimensionsreduktion
nicht mit INT8-/IVF-Verlust vermischt wird. Höhere native Modelldimensionen können
weiterhin separat mit `rag.compare` geprüft werden.

## Android aktivieren

`<filesDir>/rag/knowledge.ivf` wird automatisch bevorzugt, wenn vorhanden. Ohne
IVF-Datei bleibt der bisherige Vollscan kompatibel. Explizite Konfiguration in
`<filesDir>/rag/config.properties`:

```properties
ivf=knowledge.ivf
nprobe=16
rrf_k=60
database=knowledge.sqlite
```

Fehlt ein **explizit konfigurierter** IVF-Index, kommt eine klare Tool-Fehlermeldung.
Ungültige Header, Versionen, Dateigrößen oder Clustergrenzen ergeben IOException
statt eines unbehandelten Fehlers. Query-Encoder und Datenbank müssen weiterhin
zum ursprünglichen Embedding-Lauf passen; `hash-v1` ist nur der Beispiel-Encoder.
FTS5 wird zuerst versucht; Android-Systeme ohne FTS5 verwenden den bisherigen
FTS4-Kompatibilitätsindex. Mehr als drei Treffer werden weiterhin nicht ausgegeben.

Ein einzelner unveränderlicher mmap-Snapshot wird nach kanonischem Dateipfad,
Größe und Änderungszeit wiederverwendet. **Eine gemappte Datei niemals in-place
überschreiben oder kürzen.** Neue Datenbank und Index als zusammengehöriges Paar
bereitstellen, atomar umbenennen und die App neu starten. Der Cache ersetzt keine
sichere Deployment-Strategie und prüft weder Modell-ID noch Dump-Zugehörigkeit.

## Skalierung und Geräteprüfung

```sh
# Kein Bestandteil der kleinen Tests: erzeugt etwa 1,6 GB Indexdateien.
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 python3 -m rag.benchmark_ivf \
  --directory rag/output/scaling --sizes 1000000 5000000 \
  --clusters 4096 --nprobe 16 --queries 10 --warmup 2 \
  --output rag/output/scaling.json
# Bereits generierte Dateien: denselben Befehl mit --reuse aufrufen.
```

Das Skript erzeugt gaußsche normierte Zufallsvektoren in gleichmäßig großen,
beliebigen Partitionen, **kein trainiertes k-means**. Das isoliert Scan- und
Zentroidenkosten, sagt aber nichts über echte Clustergrößen, Semantik oder Recall.
Die Benchmarks berichten Zentroidenwahl, Scan und gesamte Vektorsuche separat,
mit p50/p95 und Rohwerten. Mapping/Validierung wird zusätzlich separat gemessen.
Vollscan läuft vor IVF; OS-Cache ist dadurch eher warm. Pagefaults sind während
des gemessenen Scans möglich; Query-Embedding, FTS und RRF sind nicht enthalten.

Das Java-Hilfsprogramm verwendet exakt `KnowledgeSearch.IvfIndex`, keinen zweiten
Scanner. JDK 17 und Android SDK 35/Build-Tools 35.0.0 werden vorausgesetzt:

```sh
# ANDROID_HOME setzen; JAVA_HOME muss auf ein vollständiges JDK zeigen.
rag/device/build_benchmark.sh
adb push rag/output/device-benchmark/vector-benchmark.jar /data/local/tmp/
adb push rag/output/scaling/synthetic-5000000.ivf /data/local/tmp/
adb push rag/output/scaling/queries.f32 /data/local/tmp/
adb shell CLASSPATH=/data/local/tmp/vector-benchmark.jar app_process /system/bin \
  de.bonsai.offline.VectorBenchmark /data/local/tmp/synthetic-5000000.ivf \
  /data/local/tmp/queries.f32 16 2 1 > rag/output/a55-5000000.json
# Argumente hinter queries.f32: nprobe, Warmup-Anfragen, Wiederholungen.
# Für 1 Mio. die entsprechende Indexdatei kopieren und den Pfad ersetzen.
```

Es ist keine Installation der App und kein Zugriff auf deren private Daten nötig.
Der Querycontainer `BRAGQ001` besteht aus Magic (8 Bytes), uint32 Dimension (=256),
uint32 Anzahl und Anzahl×256 Float32-Werten. Damit erhalten Host und Gerät exakt
dieselben Anfragen. Die synthetischen Dateien vor Geräteversuchen vollständig
übertragen. Für echte Indizes/Fragen kann dasselbe Format mit dem passenden
Query-Encoder erstellt werden. DEX-Build und HotSpot-Lauf sind keine ART-/A55-
Validierung. Ohne A55-Messung ist weder das Einsekundenziel bestätigt noch ein
JNI-Bedarf belegt; deshalb wurde kein nativer Scanner hinzugefügt.

Tests: `python3 -m unittest discover -s rag/tests -v`, dazu Gradle
`:app:testDebugUnitTest :app:lintDebug` und `python3 scripts/check_project.py`.
Der mmap-Grenztest erzeugt eine spärlich belegte Datei über 2 GiB, lädt aber keine
Millionen Vektoren und belegt nur wenige tatsächlich beschriebene Seiten.
Rohmessungen und Validierungsumfang: [validation/ivf/README.md](validation/ivf/README.md).
