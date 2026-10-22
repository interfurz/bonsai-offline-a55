# A55: fünf zusammenhängende Chat-Turns messen

In der App: **Benchmark öffnen → Benchmark starten → 5 Fragen als zusammenhängender Chat**.
Das zusätzliche Auswahlfenster bietet auch den bisherigen Einzelprompt-Benchmark,
Messoptionen und die letzte Chat-Messung. Die Beschreibung auf der bestehenden
Benchmark-Einstiegsseite bezieht sich weiterhin auf den bisherigen Einzelprompt-Modus;
für den Chat-Lauf die eigene Ergebnisansicht verwenden.

Nach dem Lauf erscheint eine eigene Ergebnisansicht mit **JSON exportieren**.
Alternativ lässt sich die letzte Datei über den bestehenden Exportknopf exportieren.
Vor einem zweiten Lauf die erste Datei unter einem anderen Namen sichern: Die App
ersetzt ihren internen letzten Bericht (`benchmark.json`). Der Benchmark verändert
keine Chatnachrichten. Er verwendet dieselbe Tool-Schleife wie normale Antworten.

## Messablauf

1. APK und Modell identisch halten, `n_ctx=4096`, GBNF, Vorwärmen und Antwortbudget
   und Systemprompt zwischen den Läufen unverändert lassen. Das JSON enthält App-Version/-Code,
   Runtime-Commit, Modell-SHA256 und Geräte-Fingerprint; `schema=3` und
   `benchmark=bonsai-chat-5-v1` kennzeichnen die neue Instrumentierung.
2. CPU in den Modelleinstellungen wählen. Unter **Messoptionen** zunächst Flash
   Attention und Q8 KV ausgeschaltet lassen. Modell laden, abkühlen lassen und
   den Chat-Benchmark ausführen. Als `cpu.json` exportieren.
3. Vulkan wählen (benötigt Vulkan-APK), Modell laden, unter vergleichbaren
   Bedingungen erneut ausführen und als `vulkan.json` exportieren. Möglichst
   mehrere Läufe mit vertauschter Reihenfolge machen. Ladezustand, angeschlossenes
   Ladegerät und Umgebungstemperatur ebenfalls festhalten.
4. Optional Microbatch 1/8/32/128, Flash Attention und Q8 KV einzeln vergleichen.
   Die Optionen werden gespeichert, das Rechenprofil lädt das Modell neu.
   Q8 für K **und** V verlangt Flash Attention. Alle Optionen lassen sich wieder
   ausschalten. Der Kernel Optimizer prüft angeforderte Varianten mit denselben
   festen Token-Replays, CPU-Referenzlogits und Wiederholungsmessungen wie bisher.
   Nur gültige, bestätigte Profile werden gespeichert. Größere Microbatches können
   größere Rechenpuffer benötigen; das ist ein bewusst gewähltes Experiment.
5. Vergleich auf dem Host:

   ```bash
   python3 benchmark/compare.py cpu.json vulkan.json > vergleich.json
   ```

   Das Skript prüft identisches Modell, Kontext, Fragen, Seed, Budgets, Runtime,
   GBNF und Vorwärm-Einstellung. Es verwirft unvollständige Läufe und gibt pro
   Turn A, B und die Differenz B minus A aus. Unterschiedliche Geräte/App-Versionen
   stehen in den Metadaten und müssen bei der Interpretation berücksichtigt werden.

   Seit dem anpassbaren Systemprompt enthält der Chat-Export zusätzlich
   `system_prompt_sha256` und `system_prompt_customized`; der Prompttext wird
   nicht exportiert. Vorwärmen, Generierung und Cache-Commit verwenden denselben
   Prompt. Sobald einer der Exporte den Hash enthält, verlangt das Vergleichsskript
   ihn in beiden Exporten und identische Werte. Zwei alte Exporte ohne Hash bleiben
   vergleichbar; deren Promptidentität lässt sich damit nicht nachweisen.

## Werte richtig lesen

- `ttft_ms`: Wall-Zeit bis zum ersten nichtleeren finalen Text-Callback über alle
  Tool-Runden. Verdeckte Tool-Ausgabe zählt nicht. Bei fehlendem finalem Token null.
- `tokens_per_second`: Alle nativ generierten Tokens einschließlich Reasoning und
  Tool-Aufrufen / Summe der Generierungszeiten. Prefill, Cleanup und Commit sind
  separat. Unterschiedliche Antwort-/Reasoning-Längen zwischen Backends können
  den Vergleich beeinflussen; Antworten werden deshalb ebenfalls exportiert.
- `reused_prompt_tokens`, `decoded_prompt_tokens`, `reasoning_tokens`, `prefill_ms`:
  Summen sämtlicher Tool-Runden. Wiederverwendete Tokens können dabei in mehreren
  Runden gezählt werden. Die einzelnen Originalstatistiken stehen in `rounds`.
- `cleanup_ms`, `cleanup_decoded_tokens`: natives Entfernen von Reasoning nach
  jeder Generierung; `commit_ms` und `commit_decoded_tokens`: abschließendes
  Angleichen an den gespeicherten Verlauf ohne interne Tool-Runden. Finale
  `cached_tokens`/`kv_positions` stammen **nach** diesem Commit.
- `cache_resets`, `cache_reset_reason`, `cached_before`: Cache-Verwerfungen erkennen.
  Der Reset-Zähler gilt für die Lebensdauer des nativen Handles, nicht pro Turn.
  Prefix-Abweichungen können auch ohne vollständigen Reset einen Suffix-Neuaufbau
  verursachen; dann helfen `reused_prompt_tokens` und die Rundendetails.
- `battery_celsius_before/after`: Android-Batterietemperatur aus dem Sticky Broadcast,
  ohne weitere Berechtigung, null falls nicht vorhanden. Das ist keine CPU-Temperatur.
- `thermal_status_before/after`: Android PowerManager-Status (0–6, -1 vor API 29).
  Korrelation mit sinkenden t/s belegt allein noch kein thermisches Throttling.
- `rss_kb_before/after` je Turn und `rss_peak_kb` (250-ms-Stichproben) sind Prozess-RAM,
  kein vollständiger GPU-Speicherzähler.
- `prefix_warmup_stats` und `prefix_warmup_wall_ms`: Vorwärmen separat vom ersten
  Turn. Der Chat-Lauf leert den Kontext genau einmal vor Turn 1. Es gibt keinen
  zusätzlichen Generierungs-Warm-up. Ab Turn 2 werden die vorigen Antworten benutzt.
- `requested_profile` enthält den Wunsch. Tatsächlich angewandte Microbatch-/KV-
  Einstellungen stehen pro Turn/Runde in `micro_batch`, `kv_cache_type` und
  `flash_attention_requested`. Ein BackendGuard-Fallback kann vom Wunsch abweichen.

**Messoptionen → Turn-Statistik in Logcat** aktiviert `BonsaiTurn`-JSON pro normalem
Turn und pro Benchmark-Turn. Standardmäßig aus. Logs enthalten keine Prompts oder
Antworttexte; die exportierte Benchmarkdatei enthält Fragen und Antworten.

Es gibt weiterhin einen KV-Cache und eine Sequenz. `n_ctx` wird nicht erhöht.
Vorwärmen nutzt denselben Cache. Bei Rechenprofilwechsel wird der vorherige Kontext
vor dem neuen freigegeben. F16 KV, Flash Attention aus und Vulkan-Microbatch 1
bleiben die Standardwerte; eine Beschleunigung auf dem Galaxy A55 ist damit nicht
behauptet.
