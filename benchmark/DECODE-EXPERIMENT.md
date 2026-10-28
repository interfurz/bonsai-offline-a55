# Decode-Kernel-Experiment — noch kein 2×-Nachweis

**Das Ziel ist nicht erreicht.** Die Änderungen liegen auf `perf/decode-throughput`,
ausgehend von `main` (`c8d99884`). `perf/inference-ttft-v6` war weder in den
Remote-Refs von `interfurz/bonsai-offline-a55`, den PRs/Forks, den lokalen Refs und
Reflogs noch in den untersuchten lokalen Projekt-Repositories verfügbar. Ein
Vergleich mit diesem Stand ist deshalb nicht möglich.

## Änderungen und Freigabegrenze

- `ternary_dot.c`: ARM64/DOTPROD-PQ2×Q8_K verwendet bitweise Gewichtsebenen und
  deinterleavte Aktivierungen statt wiederholter Tabellen-Replikation und variabler
  Shifts. Die exakte Integerreduktion und Skalierung je 128 Gewichten bleiben erhalten.
- `ternary_ptq.c`: PTQ1-Trits werden direkt in SDOT verarbeitet, ohne zuerst acht
  vollständige Decode-Vektoren zu materialisieren. Zwei Vergleiche extrahieren
  jeden Trit exakt. Die ursprüngliche Integer-Lane-Zuordnung vor FMA bleibt erhalten.
  Der Host-Pfad kombiniert Haupt- und Restgruppen mit AVX2; die Korrektur für
  `code = weight + 1` erfolgt je Q8-Block in Integerarithmetik.
- Gewichte, Aktivierungsquantisierung, KV-Datentyp und Antwortbudget werden nicht
  geändert. Skalare Referenz und nicht unterstützte Pfade verwenden die bisherigen
  Kernel. Für `nrc != 1` greift weiterhin der vorhandene Pfad.
- Die experimentellen Kernel sind **standardmäßig aus**. Nativ:
  `-DBONSAI_FAST_TERNARY=ON`; im Android-Gradle-Build:
  `-PbonsaiFastTernary=true`. Letzteres setzt sowohl CMake als auch
  `BuildConfig.FAST_TERNARY`. Benchmark-/Optimizer-Berichte kennzeichnen die
  Variante; gespeicherte Optimierungsprofile werden nicht zwischen Varianten
  übernommen. Der unveränderte skalare Referenzbericht bleibt wiederverwendbar.
- Die gepinnte Runtime bleibt unverändert, abgesehen vom bestehenden, durch
  `fetch_runtime.py` verifizierten EmbeddingGemma-Backport. Es wird kein
  unprotokollierter Vendor-Patch benötigt.

Eine zunächst stärker umgeordnete PTQ-FP-Akkumulation bestand kleine Dense-Dot-Tests,
wich aber beim echten Modell bei den Logits ab. Diese Variante wurde verworfen,
nicht durch größere Toleranzen freigegeben. Die verbleibende Variante hält auch
die ursprüngliche FP-Reihenfolge des x86-Pfads ein.

## Tatsächlich ausgeführte Messungen

Windows-x86-Host, AMD mit AVX2/AVX512/VNNI, je vier Decode-/Batch-Threads,
Kontext 4096, Microbatch 512, F16-K/V, Flash Attention aus. Pro Modell 64 feste
Prompt-Tokens, vier nicht gemessene Warm-up-Decodes und drei Messläufe mit je
16 festen Decode-Tokens. Kein Sampling, EOS-Abbruch oder Logitvergleich im Timer.

| 9B-Modell | Baseline Tok/s (Median) | Kandidat Tok/s (Median) | Verhältnis |
|---|---:|---:|---:|
| PQ2_0 | 11.973742 | 11.327726 | 0.946047× |
| PTQ1_0 | 4.194906 | 4.407996 | 1.050797× |

**Kein Ergebnis davon belegt 2×.** Der ARM-PQ2-Kernel läuft auf diesem Host gar
nicht; dessen Zeitdifferenz darf ihm nicht zugeschrieben werden. Auch der kleine
PTQ-Abstand aus kurzen Host-Läufen ist kein belastbarer allgemeiner Speedup.
Die Messung ersetzt weder isolierte, abwechselnde A/B-Läufe noch eine A55-Messung.

Für beide Modelle wurden alle **3.973.120 Logitwerte** des ersten Messlaufs
verglichen: bitgleich, maximale absolute Abweichung null. Alle Top-Tokens und die
Signaturen aller drei Messläufe stimmen ebenfalls überein. Modell- und
Bericht-SHA256 stehen in [decode-validation.json](decode-validation.json).
Rohberichte und vollständige Logits liegen lokal unter `build/` und werden nicht
als große Binärdateien versioniert.

## Reproduktion des nativen Benchmarks

Zunächst `python scripts/fetch_runtime.py`. Dann zwei Release-Builds derselben
Runtime und derselben Compiler-/Architekturkonfiguration erstellen:

```sh
cmake -S benchmark/native -B build/decode-base -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=ON -DBONSAI_FAST_TERNARY=OFF
cmake --build build/decode-base --target bonsai-decode-bench -j 8
cmake -S benchmark/native -B build/decode-fast -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=ON -DBONSAI_FAST_TERNARY=ON
cmake --build build/decode-fast --target bonsai-decode-bench bonsai-reference-test -j 8
ctest --test-dir build/decode-fast --output-on-failure
```

Mit dem hier vorhandenen MinGW GCC 13.2 waren für **beide** Build-Konfigurationen
zusätzlich `-DCMAKE_C_FLAGS=-D_WIN32_WINNT=0x0601` und
`-DCMAKE_CXX_FLAGS=-D_WIN32_WINNT=0x0601` erforderlich: dessen Windows-Header
enthalten die von der Runtime verwendeten neueren Thread-Throttling-Deklarationen
nicht. Das ist ein Host-Build-Workaround, keine Android-Änderung. Beim Start muss
das zum Compiler passende DLL-Verzeichnis vor den Git-DLLs im `PATH` stehen.

Die beiden Programme nacheinander, ohne konkurrierende Builds, ausführen
(unter Windows mit `.exe`):

```sh
build/decode-base/bonsai-decode-bench "$GGUF" 4 16 3 64 base.json base.f32
build/decode-fast/bonsai-decode-bench "$GGUF" 4 16 3 64 fast.json fast.f32
python benchmark/compare_decode.py base.json fast.json \
  --baseline-logits base.f32 --candidate-logits fast.f32 --require-target
```

Exitcode 0: kompatibel und Ziel erreicht; 1: korrekt verglichen, aber weniger als
2×; 2: unvollständiger, inkompatibler oder numerisch abweichender Vergleich.
Die Hashidentität der Gewichtsdatei vor den Läufen separat sichern: Der native
Bericht enthält Pfad, Parameterzahl und Größe, berechnet aber keinen Modell-SHA256.
Für eine belastbare Abnahme längere Replays, mehrere Kontextlängen und wechselnde
A/B-Reihenfolge verwenden. Ein einzelner schneller Kernel-Dot ist kein Nachweis
für schnelleren vollständigen Decode.

Für Android kann dasselbe `benchmark/native` mit dem NDK-Toolchainfile,
`ANDROID_ABI=arm64-v8a` und `ANDROID_PLATFORM=android-28` gebaut werden.
Die CPU-Architektur wird dabei auf `armv8.2-a+dotprod` gesetzt. Dieser Cross-Build
und die Ausführung auf dem A55 wurden hier **nicht** verifiziert.

## Korrektheitsprüfungen

```sh
python -m pip install -r tests/requirements-arm-kernels.txt
# BONSAI_ARM_CC auf clang setzen, falls clang nicht im PATH liegt; lld wird benötigt.
python -m unittest discover -s tests -p 'test_arm_*.py' -v
python -m unittest discover -s tests -p test_decode_compare.py -v
python scripts/check_project.py
```

Die acht ARM-Testfälle kompilieren und linken echte AArch64-Kernel und führen sie
mit Unicorns `MAX`-CPU aus (die Standard-A57-CPU unterstützt SDOT nicht). Sie
prüfen sämtliche 256 Bytecodes, Aktivierungen einschließlich -128/127, zufällige
Zeilen/Skalen, ungültige Breiten und leere Dots gegen unabhängige Dense-Referenzen.
**Emulationszeit wird nicht als Hardwareperformance verwendet.**

Zusätzlich bestanden: acht Vergleichsskript-Testfälle, der native CPU-Referenztest
(inklusive skalarer Dots und dichter Hadamard-Referenz), Runtime-Backportprüfung,
Projekt-/Streaming-Prüfungen und Syntaxprüfung aller 43 Java-Dateien. Android-
Typprüfung, APK-Bau und A55-Performance bleiben gesonderte Freigabeschritte.

Die relevanten Gradle-Tests für PerformanceOptions, BenchmarkChat,
BenchmarkMetrics und BackgroundInference wurden angefordert, liefen aber nicht:
Die Projektkonfiguration bricht mit **`SDK location not found`** ab. Der vorhandene
JDK-Syntaxcheck ersetzt diese Tests nicht. Ein nutzbarer A55-/ADB-Zugriff war in
dieser Umgebung ebenfalls nicht eingerichtet. Deshalb bleiben die Kernel opt-in
und das Ziel offen.

## Nachprüfung: persistente CPU-Worker

Die JNI-Engine übergab bislang keinen expliziten Threadpool. In der gepinnten
Runtime erzeugt `ggml_graph_compute` dadurch Worker je Graph und beendet sie
danach wieder. Das Entfernen dieser Arbeit war ein gesonderter Versuch, keine
aus dem Quellcode ableitbare Beschleunigungszusage.

`cpu_threadpool.h` ergänzt wiederverwendbare Worker mit Kapazität
`max(decode_threads, batch_threads)`. Neue Worker starten pausiert; KV bleibt bei
reinen Threadzahlwechseln erhalten. Eine neue Pool-Allokation erfolgt vor dem
Abhängen des bisherigen Pools; der Kontext wird vor den Workern freigegeben.
Generierung, Probe und Prefix-Warm-up pausieren Worker auch bei Exceptions.

Die erste Variante mit GGMLs `poll=50` und eine zweite mit `poll=0` wurden jeweils
in **A–B–B–A-Reihenfolge** mit dem echten 9B-PQ2-Modell gemessen. A verwendet
wegwerfbare, B wiederverwendbare Worker. Je Prozess: drei Läufe mit 32 festen
Decode-Tokens nach 64 Prompt-Tokens, vier Threads; alle experimentellen
Quantisierungskernel waren ausgeschaltet. Aggregiert wurden je sechs Lauf-Raten
pro Variante, nicht die besten Einzelwerte:

| Versuch | A-Median Tok/s | B-Median Tok/s | B/A |
|---|---:|---:|---:|
| Persistenter Pool, poll=50 | 11.132229 | 9.937147 | 0.892647× |
| Persistenter Pool, poll=0 | 10.475146 | 9.997598 | 0.954411× |

**Auch dieser Ansatz hat hier keine Beschleunigung nachgewiesen.** Die absoluten
Werte der getrennten Versuche sind wegen unterschiedlicher Messzeitpunkte nicht
direkt gegeneinander auszuspielen. Das Standard-Polling beschäftigt Worker
zwischen Graphen mit Millionen Poll-Iterationen; die verbleibende experimentelle
Implementierung verwendet deshalb `poll=0`. Eine Kausalzuordnung sämtlicher
Zeitunterschiede oder eine A55-Aussage folgt daraus nicht.

Der Pool bleibt **standardmäßig aus**: Android-Gradle-Schalter
`-PbonsaiPersistentCpuPool=true`, im JNI-CMake-Projekt `app/src/main/cpp`
`-DBONSAI_PERSISTENT_CPU_POOL=ON`.
Der Schalter ist unabhängig von `bonsaiFastTernary`, wird in Berichten protokolliert
und trennt gespeicherte Optimierungsprofile. Im nativen Replay aktiviert ein
zusätzliches letztes Argument `1` den Pool; `0` beziehungsweise Weglassen ist A:

```sh
build/decode-base/bonsai-decode-bench "$GGUF" 4 32 3 64 pool-a.json pool-a.f32 0
build/decode-base/bonsai-decode-bench "$GGUF" 4 32 3 64 pool-b.json pool-b.f32 1
```

Alle 7.946.240 Logitwerte je verglichenem erstem Lauf waren bitgleich. Zusätzlich
bestand `bonsai-threadpool-test` die deterministische Ressourcen-/Fehlerprüfung.
`bonsai-threadpool-model-test` wechselte mit dem echten Modell die Decode-/Batch-
Threadzahlen, prüfte erhaltene KV-Positionen, Pause/Fortsetzung und Abbruch mit
anschließender Wiederverwendung: maximale Logitabweichung **0**.

```sh
cmake --build build/decode-base --target bonsai-threadpool-test bonsai-threadpool-model-test
build/decode-base/bonsai-threadpool-model-test "$GGUF"
```

Vollständige aggregierte Messdaten und Rohberichtpfade:
[threadpool-validation.json](threadpool-validation.json). Die JNI-Übersetzung wird
zusätzlich auf dem Host gegen die echten Runtime-/JDK-Header syntaxgeprüft; das
ersetzt weiterhin keinen Android-Build.

## Verlustfreier PTQ-Ladecache mit Vierzeilen-GEMV

`ptq_recode.cpp` und `ptq_recode_kernels.c` ergänzen einen app-lokalen CPU-Buffer:
PTQ1-Gewichte werden beim Laden einmalig in Zwei-Bit-Codes umgepackt. Alle
Gewichtswerte und FP16-Skalenbits bleiben erhalten; die GGUF-Datei wird nicht
verändert. Vier Ausgabezeilen teilen Aktivierungsdaten, mit getrennten ARM-DOTPROD-,
AVX2- und AVX512-VNNI-Kerneln. Die Aktivierungsquantisierung bleibt **Q8_0 mit
denselben 32-Wert-Skalen**, nicht der Q8_K-Pfad eines gewöhnlichen PQ2-Tensors.

Die registrierte Buffer-Variante wird vor dem ersten Modellladen ausgewählt.
Unterstützt werden zusammenhängende zweidimensionale PTQ1-Gewichte, Breite durch
128 und Zeilenzahl durch vier teilbar, F32-Aktivierungen und F32-MUL_MAT-Ausgabe.
Andere Tensoren bleiben bei der normalen Buffer-Auswahl. Zusätzlich verweigert
der Buffer selbst die Initialisierung ungültiger Gewichte, Views und Zeilenreste:
Nur eine Dispatch-Prüfung hätte einen zu kleinen Vierzeilen-Cache nicht gegen
direkte Uploads geschützt. Originale Blockoffsets werden auch bei Teiluploads in
das verschachtelte Cache-Layout übersetzt. Generischer Hostzugriff wird nicht
angeboten, da Cache-Bytes keine standardmäßigen PTQ1-Bytes sind.

### Gemessene vollständige A–B–B–A-Reihe

9B-PTQ1, vier Threads, 64 feste Prompt-Tokens, 32 feste Decode-Tokens je Trial,
drei Trials je Prozess. Kontext 4096, Microbatch 512, F16-K/V, Flash Attention
aus, persistenter Pool aus und die früheren experimentellen Dot-Wrapper aus.
Ausgewertet werden **alle sechs Trial-Raten pro Variante**:

| Variante | Median Tok/s | Trial-Spanne Tok/s |
|---|---:|---:|
| A: native PTQ1 | 3.328737 | 2.937681–3.775171 |
| B: PTQ-Ladecache | 6.388274 | 6.235007–6.825308 |

Das ergibt **1.919128×**, also **kein bestandener 2×-Nachweis**. Ein isolierter
Vergleich gegen die frühere A2-Baseline lag über 2×; die abschließende A3-Baseline
ist schneller und muss ebenfalls berücksichtigt werden. Die absoluten Hostzeiten
schwanken; der separate Korrektheitslauf nach der Speicherabsicherung gehört
nicht zur A–B–B–A-Reihe und darf deren Ergebnis nicht ersetzen.

Je verglichenem erstem Trial wurden **7.946.240 vollständige Logitwerte** geprüft:
bitgleich, maximale absolute Abweichung null. Top-Tokens und Signaturen stimmen
in allen Trials überein. Der abschließende Modelllauf nach der Speicherabsicherung
lief ebenfalls erfolgreich und lieferte dieselben vollständigen Logits.
Rohberichtpfade, SHA256, einzelne Trial-Raten und Grenzen stehen in
[ptq-recode-validation.json](ptq-recode-validation.json).

### Kosten und Freigabe

- Der Cache benötigt **34 statt 28 Byte pro 128 Gewichten**. Das betrifft nur
  ausgewählte Gewichte, nicht sämtliche Modell-/Kontextdaten.
- Der Runtime-Log meldet für den Kandidaten **2008.66 MiB Cache-Allokation** und
  **1682.24 MiB CPU-Mapped-Bufferbereich**; die Baseline meldet 1894.42 MiB
  CPU-Mapped. Gemappte Adressbereiche und Allokationen sind **keine RSS-Messung**.
  Sie dürfen nicht pauschal zum residenten RAM addiert werden. Peak-RSS und
  Speichereignung auf dem A55 sind noch nicht geprüft.
- Modellladen dauerte in den vier Prozessen etwa **0.99–1.00 s ohne** und
  **5.69 s mit Cache**. Der Median des separaten 64-Token-Prefills betrug
  **15143.09 → 6085.02 ms**. Das ist kein gemessener App-TTFT: Initialisierung,
  Sampling und UI sind darin nicht enthalten.
- Der neue Pfad bleibt **standardmäßig aus**. Nativ:
  `-DBONSAI_PTQ_RECODE=ON`; Android: `-PbonsaiPtqRecode=true`. Unabhängig von
  `bonsaiFastTernary` und `bonsaiPersistentCpuPool`. BuildConfig, Profilidentität
  und Benchmark-/Optimizer-/JNI-Berichte kennzeichnen die Variante.
- Die vier nativen CTest-Ziele bestehen, darunter jeweils 72 Graphfälle für den
  normalen SIMD-Dispatch und erzwungenes AVX2. Zusätzliche Tests prüfen
  Teiluploads über Zeilen-/Gruppengrenzen, Speicher-Canaries und ungültige
  Shapes/Views. Zwölf kompilierte ARM-Kerneltests bestehen einschließlich des
  Vierausgabe-Kernels. Das ist weiterhin **kein ARM-Modell-/Graphnachweis und
  keine A55-Performancemessung**. APK-/Android-Typprüfung bleibt SDK-blockiert.

### Reproduktion und aggregiertes Abnahme-Gate

Die oben beschriebenen Release-Build-Einstellungen für beide Varianten gleich
halten. In A `BONSAI_PTQ_RECODE=OFF`, in B `BONSAI_PTQ_RECODE=ON`; jeweils
`BONSAI_FAST_TERNARY=OFF`. Keine konkurrierenden Builds/Tests während der Messung.
Vier Prozesse in dieser Reihenfolge ausführen (unter Windows mit `.exe`):

```sh
build/decode-base/bonsai-decode-bench "$GGUF" 4 32 3 64 a1.json a1.f32 0
build/decode-recode/bonsai-decode-bench "$GGUF" 4 32 3 64 b1.json b1.f32 0
build/decode-recode/bonsai-decode-bench "$GGUF" 4 32 3 64 b2.json b2.f32 0
build/decode-base/bonsai-decode-bench "$GGUF" 4 32 3 64 a2.json a2.f32 0
python benchmark/compare_decode.py a1.json b1.json \
  --baseline-extra a2.json --candidate-extra b2.json --require-target
```

Das Gate poolt alle Trials und prüft die Metadaten und Signaturen jedes Berichts;
`--baseline-extra` und `--candidate-extra` sind wiederholbar. Für die vollständigen
Logits jede weitere `.f32` separat gegen `a1.f32` mit `--baseline-logits` und
`--candidate-logits` vergleichen. Ein solcher Einzelvergleich ersetzt **nicht**
das aggregierte Speed-Gate. Für die vorliegende Reihe endet dieses Gate korrekt
mit **Exitcode 1**. Der gewünschte v6-Branchvergleich und die A55-Abnahme bleiben
offen, unabhängig von diesem Host-Ergebnis.

## Weitere Versuche nach dem Ladecache

Zwei getrennte A–B–B–A-Reihen vergleichen neue Varianten **gegen den bisherigen
VNNI-Ladecache**, nicht gegen die ursprüngliche PTQ-Baseline. Je Variante und
Versuch wieder sechs Trials mit identischen Replayparametern:

| Versuch | bisheriger Cache Tok/s | Kandidat Tok/s | zusätzlicher Faktor |
|---|---:|---:|---:|
| VBMI-Multishift statt Shift/OR-Entpackung | 10.498975 | 10.077841 | 0.959888× |
| SIMD-Auswahl je Graph und inkrementelle Task-Koordinaten | 9.989563 | 9.201965 | 0.921158× |

**Keiner dieser Versuche belegt weitere Beschleunigung.** Die Zeiten schwanken
zwischen und innerhalb der Reihen; die Faktoren sind nicht mit der älteren
1.919128×-Messung zu multiplizieren. Beide Varianten sind ausgeschaltet:
`BONSAI_RECODE_VBMI=OFF`, `BONSAI_RECODE_GRAPH_DISPATCH=OFF`. Die nativen
Benchmark-CMake-Schalter erlauben gezielte Wiederholungen, ohne sie in der App
einzuschalten. Der normale Pfad nutzt weiterhin den bisherigen direkten
SIMD-Dispatch. In jeder Reihe sind die vollständigen 7.946.240 Logitwerte des
ersten Trials aller vier Prozesse bitgleich zur ursprünglichen PTQ-Baseline.
Rohberichte, Trialwerte und SHA256: [decode-followup-validation.json](decode-followup-validation.json).

Die zusätzlichen Arbeitsagenten lieferten ausführbare Prüferweiterungen:

- **18 Comparator-Tests bestanden.** A-/B-Einstellungen dürfen zwischen den
  Varianten differieren, müssen aber innerhalb einer Variante stabil sein.
  Teilweise fehlende optionale Metadaten, ungültige Schwellen und nichtendliche
  abgeleitete Messwerte werden zurückgewiesen. Neue Reports benennen den
  ausgewählten Recode-Kernel; Legacy-Reports bleiben auswertbar.
- **Sieben ARM-Recode-Tests bestanden**, einschließlich bitgenauem Vergleich
  mit den aus der gepinnten Runtime extrahierten und kompilierten originalen
  ARM-PTQ-Funktionen. Unregelmäßige FP16-Mantissen, Subnormale, Vorzeichen, Null,
  starke Auslöschung und Zeilen bis 16384 Elemente sind abgedeckt. Ein unabhängiges
  rationales FP32-Modell prüft die ursprünglichen SDOT-Lanes und FMA-Rundung;
  Negativkontrollen erkennen getrenntes MUL+ADD und vorzeitige Lane-Reduktion.
  Der Test serialisiert den Cache unabhängig; der vollständige ARM-Lade-/Graphpfad
  und echte ARM-Modelllogits bleiben gesondert zu prüfen.
- **Sechs native CTest-Ziele bestanden**, einschließlich AVX2-, VNNI-, VBMI- und
  Graph-Dispatch-Varianten. Dies ist keine Freigabe ihrer Geschwindigkeit.
