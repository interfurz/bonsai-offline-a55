# EmbeddingGemma 2: Validierungsstand

Stand: 22. Oktober 2026. CPU-Implementierung für `google/embeddinggemma-2`,
Text-Query → 768D Mean Pooling mit trainierter Projektion → erste 256 Werte →
L2-Normalisierung → vorhandener INT8-Vollscan oder IVF/RRF.

## Auf dem Host mit echtem Modell gemessen

`tests/EmbeddingGemma2Smoke.java` verwendet die produktive Java-Klasse und JNI
mit `ggml-org/embeddinggemma-2-GGUF`, Datei `embeddinggemma-2-Q8_0.gguf`,
Revision `58faf90940886df2d62be555c8765d00dac362c5`, SHA256
`2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135`.

- Ausgabe: 256 endliche Werte, quadrierte L2-Norm `1.00000001297348`.
- Deutsche Frage „Wie erzeugen Pflanzen mit Sonnenlicht Sauerstoff? 🌳“:
  Cosinus zum Photosynthese-Dokument `0.7979675060`, zum Berlin-Dokument
  `0.5169481116`. Dokumente werden ebenfalls mit den echten Gewichten berechnet.
- Abbruch vor dem Laden und während nativer Berechnung propagiert als
  `CancellationException`. Eine anschließende Anfrage liefert denselben Vektor
  (Cosinus > 0.99999).
- Optimierte/scalare Bonsai-Kernel stimmen nach dem Backport weiterhin mit
  unabhängigen dichten Referenzrechnungen überein.

Dies ist ein einzelnes Retrieval-Beispiel auf einer Host-CPU, kein Recall- oder
Latenzbenchmark. Eine numerische Paritätsmessung gegenüber dem ursprünglichen
Float32-Hugging-Face-Modell wurde nicht durchgeführt.

## Automatisierte Host-Tests und Android-Build

- 112 Android-Unit-/Robolectric-Tests, keine Fehler. Darunter sechs neue Tests
  zu Dimensionsreduktion, Profil-/Modellprüfung, Encoder-Konfiguration,
  Abbruchweitergabe, FTS-Fallback sowie Anbindung an Vollscan und IVF.
  Diese Unit-Tests verwenden keine Modellgewichte.
- 22 Python-RAG-Tests einschließlich Query-Prefix, Dokumenttitel und
  Exportprofil. Kein Modell-Download in den Tests.
- Runtime-Patch: saubere Anwendung, erneute Verifikation sowie Ablehnung
  zusätzlicher oder veränderter Dateien getestet.
- `scripts/check_project.py`: Manifest/XML, JNI-Einstiegspunkte,
  UTF-8-Streaming und Konversationszustand geprüft.
- ARM64-CPU-Debug-APK gebaut; `lintDebug` erfolgreich.

## Reproduktion

JDK 17, Android SDK/NDK wie in `app/build.gradle`, CMake, C++-Compiler und die
Python-Abhängigkeiten aus `rag/requirements.txt` werden vorausgesetzt.
Die GGUF-Datei ist separat herunterzuladen und zu verifizieren.

```sh
python3 scripts/fetch_runtime.py
python3 scripts/check_project.py
python3 -m unittest discover -s rag/tests -v
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
cmake -S app/src/main/cpp -B build/embedding-host \
  -DBONSAI_HOST_SMOKE=ON -DBONSAI_KERNEL_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build/embedding-host --target bonsai-jni bonsai-reference-test --parallel 2
./build/embedding-host/bonsai-reference-test
mkdir -p build/embedding-smoke
javac -cp "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:$ANDROID_HOME/platforms/android-35/android.jar" \
  -d build/embedding-smoke tests/EmbeddingGemma2Smoke.java
java -Djava.library.path=build/embedding-host \
  -cp "build/embedding-smoke:app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:$ANDROID_HOME/platforms/android-35/android.jar" \
  de.bonsai.offline.EmbeddingGemma2Smoke /daten/embeddinggemma-2-Q8_0.gguf
```

## Noch auf dem Gerät zu prüfen

Kein angeschlossenes Galaxy A55 wurde verwendet. APK-Installation,
gleichzeitiger RAM-Bedarf von Bonsai und Encoder, kalte/warme Ladezeit,
Query-Latenz, Thermik und Suchqualität mit echtem Korpus sind nicht gemessen.
Der vollständige Wikipedia-Export mit EmbeddingGemma 2 wurde nicht ausgeführt.
Für das Gerät Datenbank, Vektoren/IVF und Gewichte gemäß [README.md](README.md)
bereitstellen und das Modell-/Exportprofil gemeinsam aktualisieren.
