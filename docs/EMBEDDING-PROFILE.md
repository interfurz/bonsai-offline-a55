# EmbeddingGemma 2: eingefrorener Pilotvertrag

- Modell: `google/embeddinggemma-2`, nicht `embeddinggemma-300m`.
- HF-Modell und Tokenizer: `914f7f89142e33e77833254d9c9b90c3cef7303b`.
- `model.safetensors`: SHA256 `197a32965d4b1105faf060417baa899e193fb73cd401f42ec9295234d5553d79`.
- `tokenizer.json`: SHA256 `4d777ef5bdc1aa36227abdfb77c3e49e7b9c892d16e1b6bda41c393504828be4`.
- `tokenizer.model`: SHA256 `e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03`.
- Profil: `embeddinggemma2-search-v1`.
- Dokument: `title: {Titel} | text: {Text}`.
- Query: `task: search result | query: {Frage}`.
- Text-only, HF Float32, Mean Pooling inklusive Prompt, trainierte Projektion
  auf 768D. Erste 256 Dimensionen nehmen, danach L2-normalisieren.
- Android-GGUF: Revision `58faf90940886df2d62be555c8765d00dac362c5`,
  `embeddinggemma-2-Q8_0.gguf`, 309.855.456 Bytes, SHA256
  `2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135`.

Alle HF-Dateien werden am selben Pin geladen und in `model-lock.json` einzeln
gehasht. LFS-Dateien werden gegen upstream SHA256, kleine Dateien gegen ihre
Git-Blob-ID geprüft. Vor lokaler Modellinferenz werden sämtliche Lock-Dateien
erneut gehasht; die bekannten Gewicht-/Tokenizerpins werden zusätzlich geprüft.
APK und `.bonsai` enthalten keine Modellgewichte. Der SAF-Import
des Query-Modells akzeptiert nur die oben verifizierte GGUF-Generation.

## Tatsächliche Prüfungen

CPU-/CUDA-FP32-Cosinus für acht echte Dokumente: Minimum größer als
0,999999999999. Eine deutsche Query gegen den gepinnten Windows-GGUF-CLI-Pfad
des identischen PrismML-Backports: Cosinus der normalisierten 256D-Ausgaben
0,9999207552. Das ist noch kein JNI-/A55-Paritätsnachweis über viele Fragen.

Zusätzlich wurden alle 40 echten Pilotfragen in einem nativen CLI-Lauf mit
dem gepinnten Backport geprüft: Minimum 0,9997629503, Mittel 0,9998891654
Cosinus in 256D, maximale absolute Komponentenabweichung 0,0042866414.
Im selben Pilotindex blieb Artikel-Recall@10 bei 0,975 und Hybrid-Recall@10
bei 1,0; mittlere Top-10-Nachbarüberlappung der Query-Pfade 0,9625.
Belege: `query-parity-40.json` und `query-parity-retrieval-40.json` im lokalen
Benchmarkverzeichnis. Dies erweitert den Hostnachweis, nicht die Geräteabnahme.

Der rohe Tokenizer-JSON-Pfad stimmt bei vier Unicode-/Prompt-Proben mit
AutoTokenizer überein. Der echte 1k-Korpus enthält 17.285 Chunks; sämtliche
Dokumentprompts sind höchstens 800 Tokens lang. Zuvor lag das Maximum der
Byte-Schätzung bei 1.726 tatsächlichen Tokens. Kein stilles Abschneiden am
Modellkontext: der Export lehnt Überlänge ab.

RTX 4060: PyTorch 2.14.1+cu130. Batch 16 mit unbegrenztem Cache reservierte
mehr VRAM als physisch vorhanden; Lauf kontrolliert beendet. Die Pipeline
begrenzt den CUDA-Allocator jetzt auf 72 % und gibt überschüssigen Cache frei.
Der FP32-Pilot mit Batch 2 erzeugte 17.285 Vektoren in 1.867,594 s einschließlich
Modellladen; die abgetastete Prozess-RSS-Summe lag bei 2.802.061.312 Bytes.
Die einzelnen Shards dokumentieren CUDA-Allocated-/Reserved-Peaks.

BF16 ist laut offizieller Modellkarte zulässig; FP16 nicht. Ein zusätzlicher
Test gegen 128 echte FP32-Dokumente erzielte mindestens 0,99973148 Cosinus in
256D. Er lief neben anderen Arbeiten und belegt keinen isolierten Speedup.
Die produktiven Pilotvektoren bleiben FP32-basiert; keine BF16-Shards beigemischt.
`embed --compute-dtype bfloat16` ist ein gesonderter Optimierungsweg: die
Rechengenauigkeit wird in der unveränderlichen Exportidentität fixiert.
Ein FP32-Resume in eine BF16-Generation wird vor dem Modellladen abgewiesen.
Die `.f32`-Datei bezeichnet dann das Speicherformat der BF16-Ergebnisse,
keine unabhängige FP32-Inferenzreferenz. Vor Produktionswahl ist der vollständige
Pilotvergleich gegen die vorhandenen echten FP32-Vektoren erforderlich.

Dieser vollständige Vergleich ist inzwischen durchgeführt: 17.285 BF16/CUDA-
Vektoren, Batch 8, 922,390 s gegenüber 1.867,594 s des FP32/Batch-2-Laufs,
jeweils einschließlich Modellladen und Schreiben. Abgetastete RSS-Summe
1.852.174.336 Bytes; PyTorch-Peaks 1.344.935.424 Bytes belegt und
2.160.066.560 Bytes reserviert. Unterschiedliche Batches und gleichzeitig
laufende Prozesse gehören zum Messkontext; keine isolierte Kernelmessung.
Alle Dokumentprompts lagen weiterhin bei höchstens 800 tatsächlichen Tokens.
256D gegenüber der echten FP32-Referenz: minimale Kosinusähnlichkeit
0,9995618944, Mittel 0,9999700312, RMSE 0,0004838709, maximaler absoluter
Komponentenfehler 0,005137895. Mit den 40 nativen Q8-Queries bleiben exakter
Recall@10 0,975 und MRR@10 0,8827381 gleich; Nachbarüberlappung@10 0,9875.
BF16-IVF64/nprobe16 Hybrid erreicht im selben kleinen Test Recall@10 1,0
und MRR@10 0,9666667. Beleg: `benchmarks/bfloat16-pilot-quality.json`.
Damit werden weitere Korpora als getrennte BF16-Produktionskandidaten
exportiert. Deren eigene Retrieval- und Paketabnahme bleibt erforderlich;
der bestehende veröffentlichte 1k-Pilot wird nicht überschrieben.

40 passende menschliche GermanQuAD-Fragen über sechs Themen: 768D FP32
Recall@10 0,975 / MRR@10 0,94107; 256D FP32 0,975 / 0,88274;
Hybrid mit 64-Clustern, INT8 und nprobe 16: 1,0 / 0,95417. Das belegt
Pilotfunktion, keine repräsentative Freigabe des Vollbestands. ANN-Recall gegen
256D-FP32 und gegen INT8-Vollscan werden getrennt ausgewiesen.

Vor Vollfreigabe: mehr unabhängige Themen und Nichttreffer, Query-Parität über
viele Fragen, tatsächlicher A55-RAM und Laufzeiten sowie 100k-/Vollskalierung.
Die Referenz bleibt vollständig als Float32 erhalten, damit diese Prüfungen
wiederholbar sind. Neue Pins benötigen einen neuen validierten Profilvertrag.
