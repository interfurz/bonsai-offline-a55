# Validierung am 22.10.2026

Umgebung: Linux x86_64, Python 3.12.14, Temurin JDK 17.0.20.1,
Gradle 8.11.1, Android SDK 35, Robolectric 4.14.1. Keine Ausführung auf einem A55
oder einer RTX 4060; kein Wikipedia-Dump und kein EmbeddingGemma-Modell geladen.

## Gemessen

Der selbst verfasste Beispieldatensatz enthält 50 Artikel und 50 Chunks.
Die tatsächlich erzeugten Dateien haben folgende Größen:

| Datei | Bytes |
| --- | ---: |
| SQLite inkl. FTS5 und FTS4 | 94.208 |
| 256D INT8 inkl. IDs und Scales | 13.416 |
| 768D Float32 inkl. IDs | 154.016 |

[fixture-comparison.json](fixture-comparison.json) enthält die Rohmessungen für
fünf Fragen: Vektorsuche pro Frage, ohne Query-Embedding, ein Lauf, kein
kontrollierter Cachezustand. Alle fünf gelabelten Artikel erscheinen in beiden
Top-3-Listen. Das ist **kein Qualitätsnachweis für semantische Modelle**:
`hash-v1` ist eine lexikalische Testfunktion und belegt auch im 768D-Referenzvektor
nur die ersten 256 Dimensionen. Die Übereinstimmung prüft hier die Datenstrecke;
sie misst keinen realistischen Qualitätsverlust durch Dimensionsreduktion.
Keine Hochrechnung dieser Größen oder Laufzeiten auf Wikipedia oder Mobilgeräte.

## Nur auf dem Host getestet

- 13 Python-Tests: bz2/XML-Streaming mit Limit, Namensräume/Weiterleitungen,
  Wikicode-Bereinigung, hierarchische Überschriften, Zusammenführen/Teilen,
  FTS5/Hybrid, Kompression, ID-Zuordnung, Begrenzungen und Vergleichsskript.
- 88 Java-/Robolectric-Tests insgesamt, davon 14 neue Such-/UI-Tests; keine Fehler,
  keine übersprungenen Tests. Python-Export unverändert als Testressource verwendet;
  sieben Query-Ranglisten inklusive Unicode und leerem Vektor gegen Python geprüft.
- Robolectric mit nativer SQLite-Laufzeit: FTS5 fehlt dort, der FTS4-Fallback wurde
  tatsächlich ausgeführt. Python prüft FTS5; Android-FTS5 bleibt geräteseitig offen.
- Quellen in UI und gespeichertem Chat geprüft; Tool-JSON bleibt verborgen.
- `lintDebug` und `scripts/check_project.py` erfolgreich.
- Vorhandene Hosttests für CPU-Kernel und GBNF bestanden. Zusätzlich
  `tests/rag_grammar_test.cpp`: echtes GBNF akzeptiert `suche` mit Unicode-Query,
  lehnt falsche Argumente sowie Tools in der letzten Runde ab.

## Nur gebaut

Android-Debug-Java-Klassen wurden mit Gradle kompiliert. Kein APK-Build in dieser
Validierung, kein JNI-/LLM-Lauf auf einem Android-Gerät. Die C++-Testprogramme
wurden gebaut **und auf dem Host ausgeführt**; sie stehen daher im Abschnitt oben.
Der Sentence-Transformers-Adapter wurde über einen Testadapter auf Query-/Dokument-
Promptwahl geprüft; Modellladen, CUDA-Inferenz und Voll-Dump-Verarbeitung wurden
nicht ausgeführt. Android enthält zunächst ausschließlich den Beispiel-Encoder;
bei anderen Modellmetadaten erfolgt FTS-Suche mit Warnung.

## Reproduktion

Im Repository-Stamm, mit vollständig installiertem JDK und Android SDK:

```sh
pip install -r rag/requirements.txt
python3 -m unittest discover -s rag/tests -v
python3 scripts/check_project.py
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug
cmake -S app/src/main/cpp -B build/kernel-check -DBONSAI_KERNEL_TESTS=ON \
  -DBONSAI_GRAMMAR_TESTS=ON -DBONSAI_HOST_SMOKE=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build/kernel-check --target bonsai-reference-test bonsai-grammar-test --parallel 2
./build/kernel-check/bonsai-reference-test
mkdir -p build/grammar-java build/exported-grammar
javac -d build/grammar-java app/src/main/java/de/bonsai/offline/ToolGrammar.java tests/ExportToolGrammar.java
java -cp build/grammar-java de.bonsai.offline.ExportToolGrammar build/exported-grammar
./build/kernel-check/bonsai-grammar-test build/exported-grammar
g++ -std=c++17 -Wall -Wextra -Werror \
  -Ivendor/llama.cpp/src -Ivendor/llama.cpp/include -Ivendor/llama.cpp/ggml/include \
  tests/rag_grammar_test.cpp -o build/kernel-check/rag-grammar-test \
  build/kernel-check/prism/src/libllama.a build/kernel-check/prism/ggml/src/libggml.a \
  -ldl build/kernel-check/prism/ggml/src/libggml-cpu.a \
  build/kernel-check/prism/ggml/src/libggml-base.a -lm -pthread
./build/kernel-check/rag-grammar-test build/exported-grammar
```

Der echte Laptop-Lauf und der Vergleich werden in [../README.md](../README.md)
beschrieben. Testberichte liegen lokal unter `app/build/reports/tests/` und
`app/build/reports/lint-results-debug.*`.
