# Wikidata: Validierung am 22. Oktober 2026

## Vorprüfung von EmbeddingGemma

Basis ist `bdddacd16e0557c744226c3c2fa35c3dad36ad31`, PR
[#12](https://github.com/interfurz/bonsai-offline-a55/pull/12).
Diese Integration war zum Beginn noch nicht in `main`. Architektur, produktive
Java/JNI-Anbindung, Encoder-Auswahl in `KnowledgeSearch`, Abbruch, FTS-Fallback,
Profil-/Modellprüfung und die vorhandenen Nachweise wurden geprüft.

Vor den Wikidata-Änderungen erneut ausgeführt:

- **112 Android-/Robolectric-Tests**, davon sechs EmbeddingGemma-Tests, bestanden.
- **22 Python-RAG-Tests** und `scripts/check_project.py` bestanden.
- ARM64-CPU-Debug-APK und `lintDebug` erfolgreich.
- Die beiden GitHub-Android-Läufe auf demselben EmbeddingGemma-Commit waren
  ebenfalls erfolgreich: [39806226430](https://github.com/interfurz/bonsai-offline-a55/actions/runs/39806226430)
  und [39806156169](https://github.com/interfurz/bonsai-offline-a55/actions/runs/39806156169).

Kein verbleibender Blocker in diesen Prüfungen. EmbeddingGemma-Komponenten und
Wikipedia-Pipeline wurden im Wikidata-Branch nicht verändert. Der echte
EmbeddingGemma-Modelltest ist im vorhandenen
[`rag/VALIDATION-EMBEDDINGGEMMA2.md`](../rag/VALIDATION-EMBEDDINGGEMMA2.md) dokumentiert;
er wurde hier **nicht erneut mit Gewichten ausgeführt**. Die Vorprüfung belegt
keinen bereits erfolgten A55-Gerätelauf.

## Ergebnis dieser Implementierung

Umgebung: Linux x86_64, Python 3.12.14 / SQLite 3.53.1, Temurin JDK 21.0.10,
Java source/target 17, Gradle 8.11.1, SDK 35, NDK 28.2.13676358, CMake 3.22.1.
GitHub CI verwendet JDK 17; lokal wurde JDK 21 verwendet.

| Prüfung | Ergebnis |
| --- | --- |
| Neue Standardbibliothek-Pipeline | **14 Tests bestanden** |
| Android/Robolectric gesamt | **129 bestanden**, keine Fehler/übersprungenen Tests |
| Neue Wikidata-Android-Tests darin | **17 bestanden**, native Robolectric-SQLite |
| Bestehende RAG-/IVF-Python-Tests | **22 bestanden** |
| Weitere bestehende Python-Tests | **4 bestanden** |
| Tool-/Final-GBNF, realer nativer Parser | Alle sechs Wikidata-Operationen akzeptiert; falsche Aufrufe/Finalrunde abgewiesen |
| Vorhandene `suche`-GBNF | Unicode und negative Fälle bestanden |
| CPU-Referenzkernel / Hadamard-Fallback | Bestanden |
| `scripts/check_project.py` | Bestanden |
| ARM64-Debug-APK | Gebaut |
| `lintDebug` | 0 Fehler, 8 bestehende Warnungen, keine neue Wikidata-Warnung |
| ART-Gerätebenchmark | Java kompiliert und DEX-JAR gebaut; **nicht auf Gerät ausgeführt** |
| `git diff --check` | Bestanden |

Prüffälle: gzip/bz2/JSONL, zweite Label-Passage, Wiederaufnahme in beiden
Phasen, unveränderte Quelle/Filter, Rollback einschließlich Offset, Fehlerbudget,
überlange Zeilen, kaputte Kompression, fehlendes Array-Ende, Größenlimit,
Q/P-IDs, Unicode-Normalisierung, doppelte Aliasnamen, unbekannte/vertippte Namen,
Werte/Einheiten/Grenzen, Kalender/BCE/Präzision, Qualifikatoren, Referenzen,
`somevalue`/`novalue`, Ranks, dreistufige Pfade, Unterklassen, doppelte Kanten,
REAL-Gleichstände mit exaktem Dezimalvergleich, SQL-Injection-Versuche,
Read-only-Dateiunverändertheit, Abbruch, Dekompressions-/Antwortbudgets,
Tool-Rückgabe und deterministische Herkunft in der Endantwort.

## Messwerte und Rohdaten

Autorisierte kleine Fixture: 46 gespeicherte Q/P-Entities, 143 Terme,
33 Statements, **53.248 Bytes SQLite** aus 32.363 Bytes verfasstem Test-JSON.
SHA256 der gemeinsam genutzten Android-Fixture:
`73a2d08bb49b648e999f47b5c0bafafd2d85fcf7c90c55d3df7ac13ee2527022`.

- Produktiver Android-Java-Abfragepfad unter **Robolectric auf Host**, Japan/P36,
  3 Warmups, 20 Wiederholungen, DB-Öffnen/Metadaten/Auflösung/SQL/JSON enthalten:
  **p50 2,708 ms, p95 4,004 ms**. JVM-Heap-Schnappschüsse 329.252.864 →
  331.874.304 Bytes; enthalten Robolectric und die Testsuite, sind **kein Peak**
  und keine Aussage über den Android-RAM-Verbrauch.
- Frischer Python-Unterprozess, komplette kleine Pipeline: **15.466.496 Bytes
  Prozess-Peak-RSS**, 360.485 Bytes Python-tracemalloc-Peak, Import ca. 0,022 s.
  SQLite-C-Allokationen sind im RSS, aber nicht im tracemalloc-Wert enthalten.
- Warme reine Python/SQLite-Abfragen mit 3 Warmups und je 100 Wiederholungen:
  Einzelwerte, SQL-Abfragepläne und Ergebnisse in `host-python.json`.
  Das ist eine SQL-Mikromessung, kein Android-/LLM-End-to-End-Benchmark.

Keine kontrolliert kalten Dateicaches, keine Statistik über mehrere unabhängige
Prozessläufe. Ein einzelner kleiner Bestand belegt weder Skalierung auf 7 GB
noch das Einhalten eines Latenz- oder RAM-Ziels auf dem Galaxy A55.

Rohbelege:

- [`validation/android-wikidata.xml`](validation/android-wikidata.xml)
- [`validation/python-tests.txt`](validation/python-tests.txt)
- [`validation/native-tests.txt`](validation/native-tests.txt) — Fehlermeldung zu
  „invalid grammar“ stammt aus einem absichtlich ungültigen negativen Test.
- [`validation/checks.json`](validation/checks.json)
- [`validation/host-android.json`](validation/host-android.json)
- [`validation/host-python.json`](validation/host-python.json)

## Reproduzierbare Befehle

```sh
python3 scripts/fetch_runtime.py
python3 scripts/check_project.py
python3 -m unittest discover -s wikidata/tests -v
python3 -m unittest discover -s rag/tests -v
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 -m wikidata.fixtures --check
python3 -m wikidata.benchmark --output work/wikidata-benchmark.json
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

cmake -S app/src/main/cpp -B build/wikidata-host \
  -DBONSAI_HOST_SMOKE=ON -DBONSAI_GRAMMAR_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build/wikidata-host --target bonsai-grammar-test bonsai-reference-test --parallel 2
mkdir -p work/grammar-export
javac -d work/grammar-export app/src/main/java/de/bonsai/offline/ToolGrammar.java tests/ExportToolGrammar.java
java -cp work/grammar-export de.bonsai.offline.ExportToolGrammar work/grammar-export
build/wikidata-host/bonsai-grammar-test work/grammar-export
build/wikidata-host/bonsai-reference-test

bash wikidata/device/build_benchmark.sh
```

Das neue CI-Workflow prüft die Pipeline/Fixture ohne zusätzliche Pakete und
testet GBNF im echten nativen Parser. Das bestehende Android-Workflow baut
APK und prüft alle Java-/Robolectric-Tests sowie Lint.

## Noch auf Laptop und A55 zu prüfen

Kein vollständiger Dump wurde heruntergeladen/verarbeitet, kein Modell für
diese Arbeit geladen und kein Android-Gerät war angeschlossen. Die 7-GB-Grenze
ist eine implementierte Speichergrenze mit Test für Budgetüberschreitung,
kein nachgewiesener Größenwert eines produktiven Bestands.

Auf dem Laptop Auswahl, offizielle Prüfsumme, Datenstand, Themenabdeckung,
reale große Einzelentities, Dauer/Resume und endgültige DB-Größe prüfen. Auf dem
A55 interne Bereitstellung, Flugmodus, kalte/warme Abfragen, Klassenabfragen nahe
am Zeitlimit, PSS/RSS gemeinsam mit Bonsai, UI/Lebenszyklus und tatsächliche
Tool-Auswahl/Antwortqualität von Bonsai 2 4B prüfen. Der dokumentierte
Gerätebenchmark misst dieselbe Abfrageklasse; die Unit-Tests simulieren die
Generierung des Sprachmodells. Release-Provisionierung per neuer UI und
beliebige SPARQL-/SQL-Abfragen sind nicht Bestandteil dieser Änderung.
