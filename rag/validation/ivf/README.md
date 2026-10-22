# IVF-Validierung am 22.10.2026

Basis: `agent-b-rag` (`ce99e3a`), Linux x86_64, AMD EPYC 9V45,
Python 3.12.14, NumPy 2.3.5, scikit-learn 1.8.0, Faiss CPU 1.15.1,
Temurin JDK 17.0.20.1, Gradle 8.11.1 und Robolectric 4.14.1.

## Auf dem Gerät gemessen

Keine Gerätemessung. Kein A55/ART-Lauf, kein RTX-4060-/CUDA-Lauf. Das Ziel
„unter etwa einer Sekunde“ ist auf dem A55 offen. Es gibt keinen belegten
JNI-Bedarf; folglich wurden keine nativen Dateien geändert.

## Nur auf dem Host gemessen

Die folgenden Werte sind echte Rohmessungen, keine Hochrechnungen. Zehn Queries,
zwei Warmup-Anfragen je Modus, eine Wiederholung. Vollscan vor IVF, keine
Cold-Cache-Kontrolle. Einheit aller Zeiten: Millisekunden. Beide Vollscans
verwenden die global quantisierte Vektorsektion des neuen IVF-Formats; sie
messen nicht den alten Stream-Reader des BRAGV001-Formats. Der Host erfüllt
schon beim Vollscan das Einsekundenziel; dies ist kein Beleg für das A55.

### Python / NumPy

Ein BLAS-Thread, int32-Akkumulation, K=4096, nprobe=16, Top-k=20.
Synthetische Gauß-Vektoren in beliebigen, gleich großen Partitionen: kein
k-means-Qualitätsbenchmark und keine realistische Clusterverteilungsgarantie.

| Vektoren | Modus | Scan-Anzahl p50 | Zentroiden p50 ms | Scan p50 ms | Gesamt p50 ms | Gesamt p95 ms |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 1000000 | full | 1000000 | 0.000 | 181.943 | 182.080 | 187.150 |
| 1000000 | ivf | 3906 | 0.479 | 0.728 | 1.233 | 1.338 |
| 5000000 | full | 5000000 | 0.000 | 691.248 | 691.398 | 702.788 |
| 5000000 | ivf | 19530 | 0.482 | 2.654 | 3.178 | 3.321 |

Rohwerte: [host-python-scaling.json](host-python-scaling.json).

### Java / HotSpot

Exakt der Android-mmap-Scanner `KnowledgeSearch.IvfIndex`, auf dem Host mit
`-Xmx128m` gestartet. Dieselben Indexdateien und `BRAGQ001`-Anfragevektoren wie
Python. Ein Scan-Thread. Der gesamte Index passt dabei ausdrücklich nicht in
den Java-Heap; mmap-Seiten können trotzdem erheblichen RSS/Pagecache belegen.

| Vektoren | Modus | Zentroiden p50 ms | Scan p50 ms | Gesamt p50 ms | Gesamt p95 ms |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1000000 | full | 0.001 | 170.810 | 170.876 | 267.307 |
| 1000000 | ivf | 2.987 | 0.427 | 3.428 | 4.767 |
| 5000000 | full | 0.002 | 555.885 | 556.003 | 601.436 |
| 5000000 | ivf | 2.808 | 2.021 | 4.868 | 5.034 |

Rohwerte: [1 Mio.](host-java-1000000.json), [5 Mio.](host-java-5000000.json).
Zentroiden und Scan sind getrennt gemessen; „Gesamt“ umfasst zusätzlich Query-
Normalisierung, Quantisierung und Ergebnisaufbereitung. Mapping/Validierung
steht separat im JSON. Query-Embedding, FTS, RRF und LLM-Generierung fehlen.
HotSpot optimiert anders als Android ART: keine Umrechnung dieser Zeiten.

### Recall@10

50 selbst verfasste Fixture-Artikel, `hash-v1`, 32 tatsächliche k-means-Cluster,
scikit-learn, Seed 42. 100 Fragen aus zwei Themenvorlagen pro Artikel.

| Methode | Fragen | Recall@10 (Labels) | Recall@10 (Float-Nachbarn) |
| --- | ---: | ---: | ---: |
| Float32 full | 100 | 0.9600 | 1.0000 |
| INT8 full | 100 | 0.9600 | 1.0000 |
| IVF INT8 nprobe=4 | 100 | 0.7200 | 0.2900 |
| IVF INT8 nprobe=8 | 100 | 0.8600 | 0.5270 |
| IVF INT8 nprobe=16 | 100 | 0.9700 | 0.9040 |
| IVF INT8 nprobe=32 | 100 | 0.9600 | 1.0000 |

Die Float-Baseline verwendet ebenfalls 256 Dimensionen. „Float-Nachbarn“ ist
Set-Recall gegen die exakten Float-Top-10; die Float-Zeile ist definitionsgemäß 1.
„Labels“ misst die tatsächlich gelabelten Artikel. IVF kann zufällig einen
relevanten Artikel hereinholen, den die exakte Top-10 verdrängt; 0,97 bei
nprobe=16 belegt daher keine generelle Qualitätsverbesserung. Die spärliche
Hash-Funktion ist kein semantisches Modell. 32 Probes scannen hier alle Cluster.
Roh-Ranglisten je Frage: [quality.json](quality.json).

Faiss-CPU wurde zusätzlich mit dem 50-Chunk-Fixture und vier Clustern tatsächlich
ausgeführt: [faiss-build.json](faiss-build.json). Faiss warnt bei dieser kleinen
Stichprobe vor zu wenigen Trainingspunkten. GPU-Training wurde nicht ausgeführt.

### Nur auf dem Host getestet (keine Leistungswerte)

- 21 Python-Tests insgesamt: Format/Roundtrip, globaler Scale, read-only mmap,
  Ganzzahl-Top-k inklusive Gleichständen, nprobe=alle exakt wie Vollscan,
  leere Cluster/leerer Korpus, N<K, beschädigte Header/Offsets/Dateien,
  konfigurierbares RRF, 100-Fragen-Auswertung und kleiner synthetischer Benchmark.
- 95 Java-/Robolectric-Tests insgesamt, alle bestanden, keine übersprungen:
  sieben neue IVF-Tests einschließlich 21 Python-/Java-Rangfolgenvergleichen,
  Tool-Konfiguration, Fehler-/Abbruchfälle und Grenztest über 2 GiB.
- Der Grenztest verwendet eine spärlich belegte Datei; er scannt nur zwei Zeilen
  über zwei mmap-Fenster und lädt keine großen Testdaten.
- `lintDebug`: erfolgreich, 0 Fehler; Warnungen in unveränderten Bestandsdateien.
- `python3 scripts/check_project.py`: bestanden.

## Nur gebaut

Android-Debug-Java kompiliert; Geräte-Hilfsprogramm durch D8 in ein DEX-JAR
übersetzt. Kein APK-Build/keine App-Installation. Das Hilfsprogramm wurde auf dem
Host tatsächlich ausgeführt, seine DEX-/ART-Variante ausschließlich gebaut.
Faiss-GPU-Zweig und realer EmbeddingGemma-/Wikipedia-Lauf sind nicht validiert.

## Reproduktion

Befehle für Indexbau, Qualität und Skalierung sowie den A55-Start:
[../../IVF.md](../../IVF.md). Die vollständigen lokalen Testberichte liegen unter
`app/build/reports/tests/testDebugUnitTest/` und `app/build/reports/lint-results-debug.*`.

Für denselben Java-Scanner zusätzlich auf dem Host (nach `rag/device/build_benchmark.sh`):

```sh
# Kleine JSON-Laufzeit, kein Modell-/Datensatzdownload.
curl -fL https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar \
  -o rag/output/json.jar
classes=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
java -Xmx128m -cp "rag/output/json.jar:rag/output/device-benchmark/classes:$classes:$ANDROID_HOME/platforms/android-35/android.jar" \
  de.bonsai.offline.VectorBenchmark rag/output/scaling/synthetic-5000000.ivf \
  rag/output/scaling/queries.f32 16 2 1 > rag/output/java-host-5000000.json
```
