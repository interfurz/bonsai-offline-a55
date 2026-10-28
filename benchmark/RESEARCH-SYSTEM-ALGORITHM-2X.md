# Neue Wege zum 2×-Ziel: geprüfte Hypothesen

## Korrektur der Recherche

Die ursprüngliche Recherchefassung enthielt überzogene Ausschlüsse und wird hier ersetzt.
**Spekulation ist weder allgemein noch auf dem A55 als unmöglich nachgewiesen.**
Der bisherige Original-PTQ-/Cache-Hostvergleich beträgt 1.919128×. Das 2×-Ziel muss
in einem frischen Original-/Endkandidatenvergleich bestehen; historische Faktoren
werden nicht multipliziert. Die angeforderte v6-Basis und der A55 bleiben nicht verfügbar.

Der bereits ausgeführte AVX2-Versuch ist abgeschlossen: 8.106811 → 5.516792 Tok/s,
0.680513× bei bitgleichen Logits. Deshalb bleibt automatisches VNNI gewählt.
Das ist kein isolierter Vergleich der SIMD-Breite und kein Frequenznachweis.
Daten: [simd-width-validation.json](simd-width-validation.json).

## 1. Speicherverkehr ist noch nicht gemessen

201 unterschiedliche profilierte Matrixeinträge enthalten zusammen 7,929,331,712
Gewichtkoordinaten, entsprechend nominell 2,106,228,736 Cachebytes bei 34 B/128 Werten.
Das ist Layoutarithmetik, kein gemessener DRAM-Verkehr. Die Dot-/Dispatchphase
beansprucht 99.5805 % der innerhalb des Recode-Consumers erfassten summierten
Workerzeit. Diese überlappenden Intervalle sind keine serielle Wandzeit.
Eine Division nomineller Bytes durch Workerzeit ergibt keine gemessene Bandbreite;
Größen-/Zeitkorrelation beweist weder Compute- noch Speicherlimit.

Quelle: [recode-profile-validation.json](recode-profile-validation.json).
Zur Entscheidung sind Hardwarecounter oder getrennte Streaming-/Compute-Proben
auf derselben Hardware nötig. Prefetch, andere SIMD-Breiten und exakte LUTs
werden nicht pauschal aufgrund dieser Zahlen ausgeschlossen.

## 2. Spekulation: explizites Kostenmodell statt erfundener Akzeptanz

Exakte spekulative Stichprobenziehung kann die Targetverteilung erhalten;
veröffentlichte Beschleunigungen anderer Modelle sind kein Bonsai-Nachweis.[2]
Unsere Probe misst verarbeitete Eingabetokens, nicht akzeptierte/emittierte Tokens.
Sie enthält keinen produktiven Drafter oder Sampler; die Akzeptanzrate ist unbekannt.

Der neue Rechner `speculation_economics.py` macht die Annahmen prüfbar:

- Bei `gamma` Draft-Tokens und konstanter unabhängiger Akzeptanz `alpha` beträgt
  die angenommene Emission inklusive Korrektur/Bonus `E=sum(alpha**i, i=0..gamma)`.
- Normierte Verify-Kosten `c` und sämtliche Zusatzkosten `h` ergeben das
  hypothetische Verhältnis `E/(c+h)`, keine gemessene Rate.
- Die Zuordnung `width=anchor+gamma` ist ein ausdrücklich hypothetischer Ablauf.
  Die gemessene Batchbreite allein beweist diese Zuordnung nicht.
- Die Rechnung setzt zunächst `h=0`. Draft, Sampling, Restore, Snapshotkosten und
  eine snapshotfreie serielle Basis müssen noch real gemessen werden.

Alle vier vorhandenen Prozesse wurden einzeln ausgewertet, Varianten getrennt,
gemeldete Mediane gegen sämtliche `pass_ms` geprüft. Für die zwei kanonischen Prozesse:

| Breite | normierte Passkosten c | bedingte Decke bei alpha=1 und h=0 |
|---|---:|---:|
| 2 | 1.7775–1.8051 | 1.1079–1.1251× |
| 4 | 3.4255–3.5576 | 1.1244–1.1677× |
| 8 | 6.2192–6.8969 | 1.1599–1.2863× |

Diese kurzen Hostdaten unterstützen keinen zusätzlichen 2×-Gewinn durch den
unveränderten Verify-Pfad unter diesem Kostenmodell. Sie schließen weder kleinere
Zusatzgewinne noch andere Backends, Breiten, Fusionsverfahren oder das übergeordnete
2×-Ziel relativ zum Original aus. Es gibt keinen Nachweis einer „realistischen“
Akzeptanz von 0.6/0.7 und keinen daraus gemessenen Verlust von 30–70 %.

Rechner: sechs Tests RED→GREEN; Ergebnis ausschließlich aus bestehenden Messdaten:
[speculation-economics-validation.json](speculation-economics-validation.json).

## 3. Recurrent-Rollback und Draftmodelle

Im gepinnten `vendor/llama.cpp/common/common.h:393–398` fordert `need_n_rs_seq()`
Snapshots für die aufgezählten `draft-*`-Typen, nicht für `ngram-*`.
`common/speculative.cpp:3817` warnt im Draft-Kontext vor stale Positionen bei
Teilakzeptanz ohne Rollbackfenster. Das beweist nicht, dass jeder N-Gramm-Pfad
bereits fehlerhaft ist; geeignete Target-/Draftkonfiguration und Teilakzeptanz
müssen aber vor Freigabe explizit geprüft werden.

Die bestehende Probe belegt Anker plus vollständige Draftablehnung und serialisierte
Pending-Restores, nicht Teilakzeptanz, wiederholte Zyklen oder Samplerzustand.
Serialisierte Bytes sind nicht RSS, abgeleitete Snapshotebenen keine gemessene
Geräte-Speichernutzung. Ein fremder MTP-Head oder ein Konverterflag beweist weder
Hidden-State-Kompatibilität noch hinreichende Akzeptanz am ternären Target.
Es wurde kein weiterer Checkpoint geladen und kein produktiver Sampler aktiviert.

## 4. Nächste ARM-Hypothese: gemeinsame, lane-erhaltende Biaskorrektur

T-MAC ist eine relevante LUT-/Tiling-Quelle, enthält im beschriebenen optimierten
Verfahren aber Tabellenquantisierung; optionale Fast-Aggregation fügt weitere
Genauigkeitsverluste hinzu. Unveränderte Übernahme erfüllt unsere Bitgleichheit nicht.[1]
BitNets gelesener TL1-Generator verwendet `per_tensor_quant` statt unseres
Q8_0-Vertrags mit unabhängigen 32er-Skalen und wird deshalb nicht still übernommen.[3]

Die eigene neue Hypothese ist enger: Planarcodes 0..3 unverändert dotten und die
passenden Integer-Aktivierungssummen einmal je Q8_0-Block gemeinsam abziehen.
Jede ursprüngliche SDOT-Lane muss separat korrigiert werden, nicht nur die Summe
über 32 Werte. Vier Integer-/Float-Lanes je Ausgabezeile, Skalenprodukt,
FMA-Reihenfolge und finale paarweise Reduktion bleiben unverändert.

Der erste Recherchevorschlag, vier Ausgabezeilen auf vier Float-Lanes zu reduzieren,
würde diese Rundungsstruktur ändern und wird verworfen. Ebenso sind statische
Quell-Instruktionszahlen keine Zyklusmessung oder Speedup-Obergrenze.

Der isolierte `ternary_arm_planar_bias.c` wird per Nullstub-RED und danach gegen
bisherigen Planarkernel, gepinnten Original-PTQ-Kernel und rationales Lane-/FMA-Orakel
geprüft: alle Codes, -128/127, irreguläre Skalen, Cancellation, lange Zeilen und
exakte Speichergrenzen. Erst danach ist eine opt-in Integration vertretbar;
ARM-Emulation ist kein A55-Geschwindigkeitsnachweis.

## Priorität

1. Lane-erhaltende ARM-Biaskorrektur numerisch und im erzeugten Code prüfen.
2. Bei verfügbarem Gerät Cluster-/Streaming-/Compute-Diagnose, danach echtes
   Modell-A/B mit Thermik-/Taktprotokoll. Keine Hostbandbreite auf A78/A55 übertragen.
3. Spekulation erst nach belastbarer Passkosten-/Akzeptanzmessung und Teilakzeptanz-
   Rollback weiter integrieren, nicht pauschal ausschließen oder voreilig freigeben.

Der Maßstab bleibt der vollständige 2×-Nachweis, nicht die Zahl erfolgreicher Mikrotests.

## Sources

[1] https://arxiv.org/html/2407.00088v2
[2] https://arxiv.org/abs/2211.17192
[3] https://raw.githubusercontent.com/microsoft/BitNet/main/utils/codegen_tl1.py
