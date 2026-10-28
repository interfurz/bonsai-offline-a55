# Weitere Decode-Ansätze: Recherche und Prüfgrenzen

Die Recherchen liefern Hypothesen, keinen zusätzlichen gemessenen Speedup.
Der bisherige vollständige Hostvergleich liegt bei 1.919128×, nicht bei einer
bestandenen 2×-A55-Abnahme. Fehlgeschlagene Folgevergleiche sind in
[decode-followup-validation.json](decode-followup-validation.json) erfasst.

## Hardware richtig zuordnen

Das Galaxy A55 verwendet den Exynos 1480 mit **vier Cortex-A78 (2.75 GHz)** und
**vier Cortex-A55 (2.0 GHz)**. Die Modellbezeichnung A55 ist nicht die Architektur
sämtlicher CPU-Kerne. Cortex-A55-Issue-/TBL-/Load-Durchsatz darf weder als
Flaschenhals des gesamten Geräts noch als Beschreibung der schnellen A78-Kerne
behandelt werden. Aktuell gibt es keine Geräteprofilierung, die Compute- gegen
Bandbreitenlimitierung nachweist.

Primärquelle, vom Parent abgerufen:
https://semiconductor.samsung.com/processor/mobile-processor/exynos-1480/

## 1. ARM-Fixed-Shift-Layout statt wiederholtem TBL/variablen Shifts

Hypothese: Den bestehenden verlustfreien Zwei-Bit-Cache so anordnen, dass NEON
Codes mit konstanten Bitshifts extrahieren kann. Cachegröße, Originalgewichte und
Q8_0-Skalen müssen gleich bleiben. Eine Permutation allein genügt nicht: Vor der
FP32-FMA müssen **dieselben vier Integer-Lanes wie im originalen ARM-PTQ-Kernel**
rekonstruiert werden. Das frühe Zusammenlegen von Lanes ist mathematisch ähnlich,
aber nicht rundungsgleich und kann später die Aktivierungsquantisierung ändern.

Ein isolierter Prototyp und Tests werden separat erstellt, ohne ihn in der App
zu registrieren. Prüfgates: sämtliche Codes und -128/127, unregelmäßige FP16-Skalen,
Cancellation, lange Zeilen, bitgenaue Original-ARM-Kernelreferenz; anschließend
Lade-/Graphintegration und echte Geräte-A/B-Messung. Emulationslaufzeit und
statische Instruktionszahlen sind keine Hardwaremessung.

### Tatsächlich geprüfter erster Prototyp

`app/src/main/cpp/ternary_arm_planar.c` und `tests/test_arm_planar.py` liegen
separat vor. Der Parent hat **neun neue Tests und die gesamte ARM-Suite mit
24 Tests erfolgreich ausgeführt**. Der kompilierte Prototyp stimmt bitgenau mit
dem originalen gepinnten ARM-PTQ-Kernel und dem rationalen Lane-/FMA-Modell
überein. Dazu gehören Cancellation und Zeilen bis 16384; Byte-genaue Speicherhooks
prüfen Überlesen/Überschreiben und erkennen absichtlich verkürzte Testpuffer.

Der Layoutvertrag bleibt 136 Byte je Vierzeilen-/128er-Block: vier FP16-Skalen,
dann je 32er-Teilblock acht Bytes pro Zeile. Byte `j` enthält jetzt die Codes
für `j`, `j+8`, `j+16`, `j+24`, nicht vier benachbarte Gewichte. Das Payload ist
deshalb **nicht** mit dem bisherigen Ladecache austauschbar; keine produktive
Registrierung oder Transkodierung wurde eingeschaltet.

Clang für `armv8.2-a+dotprod`, `-O3 -Wall -Wextra -Werror`, kompiliert erfolgreich.
Das erzeugte Assembly enthält SDOT/FMLA und konstante Shifts, **keine TBL- oder
variablen SHL-Instruktionen**. Das bestätigt nur die gewünschte Codeform, nicht
weniger Gerätezyklen. Lade-/Graph-/vollständige ARM-Modellprüfung und A55-Timing
fehlen weiterhin.

### Opt-in-Integration des Planar-Ladecaches

Der Prototyp ist inzwischen an den echten Loadcache angeschlossen, weiterhin
**standardmäßig aus**: `BONSAI_PTQ_PLANAR=ON` verlangt `BONSAI_PTQ_RECODE=ON`.
Im Android-Build entsprechend beide Properties setzen:
`-PbonsaiPtqRecode=true -PbonsaiPtqPlanar=true`. Beide CMake-Einstiege und Gradle
weisen die ungültige Kombination Planar ohne Recode ausdrücklich zurück.

`bonsai_ptq_recode_blocks()` gibt unverändert kanonische PQ2-Blöcke aus. Erst der
Upload in den Vierzeilen-Cache ordnet die Codes planar an. Skalare Referenz,
AVX2-, VNNI-/VBMI-Prüfpfade und ARM-Verbraucher berücksichtigen denselben
Layoutschalter. ARM benutzt einen typkorrekten void-Adapter für den bool-Kernel,
keinen Funktionszeigercast. Ohne DOTPROD wird der optionale ARM-Quelltext leer
kompiliert und der skalare Planar-Verbraucher bleibt verfügbar. Cache-/Kernelnamen,
JNI-/Benchmark-/Optimizer-Berichte und Profilidentität unterscheiden beide Layouts.

TDD-Gates wurden tatsächlich rot und danach grün ausgeführt: Positionserhalt beim
Produktions-Upload, ursprüngliche Grapharithmetik, Produktions-ARM-Upload plus
ausgewählter Kernel, Vergleichsmetadaten, ARM-Baseline-Compile ohne DOTPROD und
die skalare Layoutkennung. Zusätzlich wurde die ARM-Produktions-C-Verkabelung als
separate Übersetzungseinheiten freestanding gelinkt. Das ersetzt keinen vollständigen
ARM-GGML-Graph oder Android-Build.

Der echte 9B-PTQ1-Festtoken-Replay wurde mit kanonischem und planarem Cache in
A–B–B–A-Reihenfolge ausgeführt. Je Prozess drei Trials à 32 Decode-Tokens nach
64 Prompt-Tokens; dieselben vier Threads, Kontext 4096, F16-K/V, Microbatch 512,
keine anderen experimentellen Kernel-/Pool-/Column-Tile-Optionen:

| Hostvariante | Median Tok/s | Spanne der sechs Trials |
|---|---:|---:|
| kanonischer Vierzeilen-Cache | 8.217107 | 7.338639–8.982446 |
| planarer Vierzeilen-Cache | 8.596568 | 7.627285–8.845384 |

**1.046179× auf diesem x86-Host**, mit deutlich überlappenden Streubereichen.
Das ist keine belastbare allgemeine Freigabe und insbesondere **kein A55-Speedup**:
Der Host führt den angepassten x86-Prüfkernel aus, nicht den ARM-Fixed-Shift-Kernel.
Der Faktor wird nicht mit historischen, separat gemessenen Faktoren multipliziert.
Gewichtsdaten/Skalen/Kontext bleiben gleich; Cachegröße weiterhin 34 Byte je
128 Gewichten. Laden schwankte hier bei A 2.85–5.70 s, bei B 4.61–5.41 s; der
Prefill-Median betrug 5329.19 bzw. 5416.60 ms. Keine zusätzlichen RAM-/TTFT-Zusagen.

Alle **7.946.240 vollständigen Logitwerte** des jeweils ersten Trials aller vier
Prozesse waren bitgleich zur ursprünglichen PTQ-Baseline; Signaturen und Top-Tokens
aller Trials ebenfalls. Die tatsächliche Cache-Ausführung ist per Zähler belegt.
Messdaten, Programm-/Modell-/Bericht-Hashes:
[planar-validation.json](planar-validation.json).

Der erneute Android-Unit-Testversuch mit Planarflag blieb bei **SDK location not
found** stehen. APK, vollständige ARM-Modelllogits und A55-Zeitmessung bleiben offen;
der bisherige Standardpfad bleibt ausgewählt.

Weiterführende Primärquellen:
- T-MAC-Paper: https://arxiv.org/html/2407.00088v2
- Implementierung: https://github.com/microsoft/T-MAC
- BitNet TL1-Generator: https://raw.githubusercontent.com/microsoft/BitNet/main/utils/codegen_tl1.py

Der Parent hat Paper und Generator abgerufen. Deren unveränderte Übernahme ist
**nicht** freigegeben: Der TL1-Generator enthält per-Tensor-Aktivierungsquantisierung,
die nicht dem bestehenden Q8_0-Pfad entspricht. LUT-Varianten sind nur dann exakte
Alternativen, wenn Integerwerte, Skalenbereiche und Float-Akkumulation erhalten
bleiben. Veröffentlichte Headline-Speedups gegen andere Baselines sind nicht auf
unseren SDOT-/VNNI-Pfad übertragbar.

## 1b. x86-Butterfly-Entpackung: numerisch exakt, kein Zusatzgewinn

Ein Smart-Agent implementierte einen getrennten VNNI-Kandidaten in
`app/src/main/cpp/ptq_recode_butterfly.c`. Statt drei Shift/OR-Verzweigungen
verteilt er zunächst Nibbles und danach Zweibitpaare. Alle 256 Eingabebytes werden
gegen eine einfache Extraktionsreferenz geprüft. Die naheliegende Multiplikations-
Abkürzung wurde vorher verworfen: überlappende Teilprodukte erzeugen Überträge und
liefern nicht für alle Bytes dieselben Codes.

`tests/ptq_butterfly_test.cpp` prüft je Layout 8696 zusätzliche bitgleiche Fälle
gegen den bisherigen kompilierten VNNI-Pfad und eine unabhängige skalare Referenz.
Canonical und Planar sind separat gebaut; der Kandidatenschalter bleibt für die
Vergleichsreferenz ausdrücklich OFF. Aktivierungsendpunkte, unregelmäßige Skalen,
Subnormale, ±0, lange Zeilen und Canaries sind enthalten. Der Parent hat die
Tests selbst über CTest ausgeführt und zusätzlich den tatsächlichen Graphdispatch
und vollständige 9B-Logits geprüft. Eine kompilierte Recording-Stub-Probe sichert
Selektor, öffentlichen Dot-Aufruf, ungültige Breiten und Kernelnamen ab.

Im vollständigen **A–B–B–A mit je sechs Trials** ergab sich:

| Hostvariante | Median Tok/s | Spanne |
|---|---:|---:|
| bisheriger VNNI-Cache | 8.546949 | 7.472291–8.780446 |
| Butterfly-VNNI-Cache | 8.382551 | 8.352562–9.615090 |

**0.980765×**, kein zusätzlicher Durchsatzgewinn nachgewiesen. Alle 7.946.240
gespeicherten Logitwerte je Prozess waren bitgleich zur ursprünglichen PTQ-Baseline;
Signaturen und Top-Tokens aller Trials stimmten ebenfalls überein. Gewichte,
Q8_0-Grenzen, FP32-FMA-Reihenfolge und Cachegröße bleiben unverändert.

Der native Versuchsschalter `BONSAI_RECODE_BUTTERFLY` bleibt **OFF**, verlangt
PTQ-Recode und x86_64 und wird nicht mit VBMI kombiniert. Es gibt keine Android-
Integration und keinen ARM-/A55-Geschwindigkeitsanspruch. Der bisherige Pfad wurde
wieder gebaut und mit einem weiteren vollständigen Modell-Logitvergleich geprüft;
dieser letzte Lauf dient nur der Korrektheit, nicht einer zusätzlichen Zeitmessung.
Reale Messdaten und Hashes: [butterfly-validation.json](butterfly-validation.json).

## 1c. Diagnoseprofil und Achtzeilen-GEMV

Statt weiterer algebraischer Entpackversuche wurde der tatsächlich ausgeführte
Recode-Pfad instrumentiert. `BONSAI_RECODE_PROFILE=ON` kompiliert die Diagnose;
zusätzlich schaltet das letzte optionale Decode-CLI-Argument `profile=1` sie ein.
Ohne Buildschalter existieren im Kernel keine Profilzeitstempel. Mit Buildschalter,
aber `profile=0`, bleibt der Collector deaktiviert. Es werden nur gemessene Decode-
Trials erfasst, nicht Prefill oder der vorgelagerte Warmup-Trial.

Collector und Produktionsverkabelung wurden per RED→GREEN geprüft. Der echte
Profil-Integrationstest vergleicht Profiling AN/AUS bytegenau über vollständige
Modelllogits und verlangt genau eine Probe pro Graph und Worker; alle Einträge
müssen Ein-Token-Spalten zeigen. Der Collector hat zusätzliche echte Concurrent-
Thread-, Reset-, Sortier- und Schlüsselfeldtests, ausschließlich mit Testfixtures.

Im realen 32-Token-9B-Lauf wurden **201 benannte Matrixeinträge und 6432 Decode-
Graphaufrufe** erfasst. Die summierten Workerzeiten innerhalb dieser Recode-
Operationen verteilen sich so:

| Erfasste Phase | Anteil der summierten Worker-Nanosekunden |
|---|---:|
| Q8_0-Quantisierung inklusive Korrektursummen | 0.1884 % |
| gemessene Vorbarriere | 0.2311 % |
| Dot-/lokale Dispatchphase | 99.5805 % |

Das sind **keine Anteile der gesamten Inferenz-Wandzeit**. Andere Graphoperationen,
die finale Runtimebarriere, Scheduler außerhalb des Consumers und Profil-Record-
Mutexkosten fehlen; Thread-Descheduling kann in den Dot-Zeiten enthalten sein.
Ohne Hardwarecounter ist Compute- gegen Bandbreitenlimit weiterhin ungeklärt.
`output.weight` ist der größte einzelne Eintrag; daraus folgt keine Erlaubnis,
Vokabularlogits zu beschneiden. Vollständige Rohprofile und Grenzen:
[recode-profile-validation.json](recode-profile-validation.json).

Der zweite Smart-Agent lieferte anschließend den isolierten Achtzeilen-Kandidaten
`ptq_recode_dot8.c`: zwei bestehende Vierzeilen-Gruppen teilen Aktivierungsload,
Broadcast, FP16-Konvertierung und Korrektursumme. Der Gewichts-Cache wird nicht
umgebaut oder vergrößert; jede der beiden ursprünglichen FMA-Ketten bleibt erhalten.
Der Parent prüfte je Layout **11985 zusätzliche bitgleiche Direktfälle** und
**108 Graphfälle**, darunter vierzeilige Tails, wenige Aufgaben bei vielen Threads,
Mehrspalten und den unabhängigen skalaren Referenzmodus. Ein Workerzähler belegt
tatsächlich ausgeführte Dot8-Batches statt nur einen gesetzten Buildschalter.

Die A–B–B–A-Reihe mit je sechs Trials pro Variante lief mit **Profiling vollständig
auskompiliert**, ansonsten denselben vier Threads, 32 Decode-/64 Prompt-Tokens und
unverändertem Modell/KV/Kontext:

| Hostvariante | Median Tok/s | Spanne |
|---|---:|---:|
| bisherige Vierzeilen-VNNI | 8.424107 | 8.290890–8.504337 |
| gepaarte Achtzeilen-VNNI | 7.639198 | 6.985377–7.820388 |

**0.906826×**, also kein Zusatzgewinn. Sämtliche gespeicherten vollständigen
Logits aller vier Prozesse sind bitgleich zur ursprünglichen PTQ-Baseline;
Top-Tokens und Signaturen sämtlicher Trials ebenfalls. Die Messung kombiniert
gepaarte Graphaufteilung mit dem Dot8-Kernel und isoliert deren Einzelkosten nicht.
Der Versuchsschalter `BONSAI_RECODE_DOT8` bleibt **OFF**, native-only/x86_64;
keine Android-/ARM-Integration. Datensatz: [dot8-validation.json](dot8-validation.json).

## 1d. Dispatch-Annahme prüfen: vorhandenes AVX2 gegen VNNI

Eine neue Hardwareabfrage identifiziert den Host als Ryzen 7 8845HS mit acht
physischen und 16 logischen Prozessoren. Statt aus dem CPU-Namen auf Downclocking
zu schließen, wurde der vorhandene AVX2-Recode-Kernel erstmals als eigener
nativer Modell-Build auswählbar gemacht (`BONSAI_RECODE_AVX2_ONLY`, default OFF).
Das ist kein neuer Entpackalgorithmus und kein globaler AVX512-Aus-Schalter:
Andere GGML-Operationen behalten ihre normale native Dispatchauswahl.

Der echte Modell-Integrationstest wurde zuerst mit dem tatsächlich falschen
VNNI-Kernelnamen rot und nach vollständiger CMake-/OBJECT-/Berichtsverkabelung
grün ausgeführt. Die vollständigen Logits bleiben bytegleich; die Metadatenprüfung
weist wechselnde AVX2-Einstellungen innerhalb einer Vergleichsseite zurück.

| Neuer Host-A–B–B–A-Vergleich, je sechs Trials | Median Tok/s | Spanne |
|---|---:|---:|
| automatisch ausgewähltes VNNI | 8.106811 | 7.439618–8.488924 |
| vorhandenes AVX2 erzwungen | 5.516792 | 4.746881–5.681677 |

**0.680513×**, deshalb bleibt die automatische VNNI-Auswahl bestehen. Das Ergebnis
vergleicht zwei unterschiedliche Integer-/Reduktionsimplementierungen und isoliert
die SIMD-Breite nicht; Frequenz- oder Bandbreitenursachen sind damit weder bewiesen
noch ausgeschlossen. Alle gespeicherten vollständigen Logits sind bitgleich zur
ursprünglichen PTQ-Baseline. Daten: [simd-width-validation.json](simd-width-validation.json).

## 2. Draftlose Mehrtoken-Verifikation / Prompt-Lookup

Mechanismus: Mehrere vermutete Tokens in einem Verify-Pass prüfen, um
Gewichtsarbeit zwischen Ausgabepositionen zu teilen. Die Eignung hängt von
Mehrzeilenkosten, Akzeptanzquote und Rollback ab; reine Unterstützung einer
Architektur beweist keine Beschleunigung.

Im gepinnten Runtime-Quellcode vom Parent nachgeprüft:
- `include/llama.h`: `llama_context_params.n_rs_seq` ist öffentlich.
- `src/llama-arch.cpp`, `llm_arch_supports_rs_rollback`: QWEN35 ist zugelassen.
- `src/llama-memory-recurrent.cpp`: CPU nutzt Snapshot-Planes. Der vorhandene
  Replaypfad ist unter anderem an SYCL gebunden; er ist kein verfügbarer CPU-Pfad.
- `common/common.h`, `common_params_speculative.need_n_rs_seq`: Die Drafttypen
  setzen Snapshots, die N-Gramm-Typen fehlen in dieser Auswahl.
- `tests/test-recurrent-state-rollback.cpp`: Vorhandene Runtime-Tests vergleichen
  Rollback/Replays mit Referenzkontexten. Ein vorhandener Test ist noch kein
  bestandener Test mit unserem GGUF und Backend.

Eine separate native Voraussetzungenprobe wird erstellt: Verify-Größen 1/2/4/8,
vollständige Logits gegen seriellen Decode und wirklicher Snapshot-Rollback gegen
frischen Referenzzustand. Noch kein produktiver spekulativer Sampler.

### Tatsächlicher 9B-PTQ1-Voraussetzungslauf

`benchmark/native/speculation_probe.cpp` wurde vom Parent mit
`-Wall -Wextra -Werror` gebaut und mit dem echten PTQ1-Modell ausgeführt.
Für 1/2/4/8-Token-Pässe waren alle Vokabularwerte über 16 Replaypositionen
bitgleich zum seriellen Pfad. Rollback von 1/2/4/8 Draft-Tokens und Wiederherstellung
des serialisierten pending Snapshots lieferten ebenfalls maximale Abweichung **0**
gegen den frischen Referenzzustand. Details: [speculation-validation.json](speculation-validation.json).

Eine wichtige Probe-Korrektur war nötig: Der Verify-Batch muss einen akzeptierten
**Anker plus k Draft-Tokens** enthalten. Der CPU-GDN-Pfad schreibt POST-Token-
Snapshots, nicht den Zustand vor dem Batch (`models/delta-net-base.cpp`,
`n_written = min(n_seq_tokens, K)`). Der erste, ankerlose Test hatte alle k Tokens
entfernt und einen nicht geschriebenen Snapshot angefordert; dessen Abweichungen
sind **kein nachgewiesener Runtimefehler**. Der korrigierte Ankertest besteht mit
unveränderten beziehungsweise gegenüber dem Comparator nicht erweiterten Grenzen.

Der kurze erste Lauf mit Snapshots in beiden Varianten erreichte etwa
8.39/8.60/8.94/9.36 Verify-Tokens/s für Breiten 1/2/4/8. Nur ein Sweep mit zwei
seriellen Flanken, kein Akzeptanzmodell, keine behauptete Samplerbeschleunigung.
Der folgende unabhängige Review fand zusätzlich einen Warmup-Aufbaufehler:
Die erneute Vorbefüllung zwischen Warmup und Messung konnte den Decode-Graph
wieder ersetzen. Diese historischen Zeiten sind deshalb **Kurzläufe inklusive
möglichem Graphwechsel**, keine reine Warm-Decode-Messung. Der Probe wurde
auf unmittelbar vorausgehende acht Warmup-Tokens derselben Breite ohne weitere
Vorbefüllung korrigiert (`timing_protocol=immediate-same-shape-warmup-v2`).
Neues Timing beginnt bei Position 72; historische Position-64-Zeiten werden nicht
damit vermischt. Recode-Ausführung wird nun zusätzlich über einen positiven
Graphzähler-Delta pro gemessener Tokenfolge geprüft, nicht nur über den Buildschalter.
Als Folgeexperiment wird die Gewichts-Wiederverwendung über benachbarte
Aktivierungsspalten geprüft (`BONSAI_RECODE_COLUMN_TILE`, standardmäßig aus).

### Ergebnis der korrigierten Spalten-Kachelung

Alle vier Prozesse der korrigierten A–B–B–A-Reihe bestehen die vollständigen
Numerik-/Anker-/Rollback-/Snapshotgates. Jede gemessene Tokenfolge weist einen
positiven Recode-Ausführungsdelta auf. Alle Samples werden **je Breite getrennt**
aggregiert, nicht über verschiedene Verify-Arbeiten gemittelt:

| Eingabetokens je Pass | bisheriger Cache Tok/s | Column Tile Tok/s | B/A |
|---|---:|---:|---:|
| 1 | 8.637258 | 8.693493 | 1.006511× |
| 2 | 9.595606 | 9.865324 | 1.028109× |
| 4 | 9.879006 | 10.509883 | 1.063860× |
| 8 | 10.587766 | 9.827341 | 0.928179× |

Kein belastbarer allgemeiner Zusatzgewinn: kurze 16-Token-Samples, ein Sweep pro
Prozess, deutliche Streuung und Regression bei acht Tokens. Ein Tooltimeout
unterbrach die Ausführung nach den fertigen A1/B1-Läufen; B2/A2 wurden mit den
gleichen vorhandenen Programmen fortgesetzt. Auch diese zeitliche Unterbrechung
ist ein Confound. **Column-Tiling bleibt OFF.**

Rohbericht-Hashes, sämtliche Einzelzeiten, Dispatch-Prüfung, Konfiguration,
Modellhash und Grenzen: [column-tile-validation.json](column-tile-validation.json).
Die historischen `build/column-*.json` vor Warmup-Korrektur bleiben erhalten,
werden aber nicht mit `build/column-warm-*.json` vermischt. Verify-Tok/s sind
weiterhin keine akzeptierten/generierten spekulativen Tok/s.

**Semantikgrenze:** `app/src/main/cpp/chat_sampler.h` verwendet Penalties,
Top-k 20, Top-p 0.8, Temperatur 0.7 und einen Dist-Sampler. Ihn still auf Greedy
umzustellen wäre eine Verhaltensänderung, keine verlustfreie Optimierung.
Gültige Spekulation muss die gewünschte Verteilung und gegebenenfalls Grammatik-/
Samplerzustände erhalten. Snapshot-RAM und Verify-Kosten müssen gemessen werden.

Algorithmische Primärquelle zur exakten spekulativen Stichprobenziehung:
https://arxiv.org/abs/2211.17192

## 3. Gerätepolitik: Affinität, Threadzahlen, Thermik

A78-only gegenüber gemischten Kernen sowie getrennte Decode-/Verify-Threadzahlen
sind sinnvolle A55-Experimente, keine hier belegten Verbesserungen. Prefix-Reuse
und Worker-Pool-Versuche existieren bereits; der letztere brachte auf dem Host
keinen Gewinn und bleibt aus. Eine zusätzliche Affinitätskonfiguration ohne
Gerätemessung wird nicht standardmäßig eingeschaltet.

Dauerläufe müssen thermischen Zustand und Streuung berücksichtigen. Android-
Performance-Hints sind Hinweise an das System, keine garantierten festen Takte.

Primärquelle zur Android-API:
https://source.android.com/docs/core/perf/performance-hint-api

## Entscheidung

Die erneute Recherche und ihre überprüften Grenzen stehen in
[RESEARCH-SYSTEM-ALGORITHM-2X.md](RESEARCH-SYSTEM-ALGORITHM-2X.md).
Ein getesteter Szenariorechner trennt gemessene Verify-Passkosten von unbekannter
Akzeptanz und hypothetischen Tokenraten:
[speculation-economics-validation.json](speculation-economics-validation.json).
Kein universeller Spekulationsausschluss und keine aus Workerzeit abgeleitete
DRAM-Bandbreite. Nächster enger ARM-Versuch ist eine lane-erhaltende gemeinsame
Biaskorrektur, nicht das Zusammenlegen ursprünglicher Float-Lanes.

Zunächst ARM-Fixed-Shift-Prototyp und Mehrtoken-/Rollback-Voraussetzungenprobe
numerisch ausführen. Produktintegration nur nach bestandenen Korrektheitsgates,
und Geschwindigkeitsfreigabe ausschließlich nach kompatiblen A/B-Messungen.
SDK-/A55-Zugriff und die angeforderte v6-Vergleichsbasis bleiben offene Grenzen.
