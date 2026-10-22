# Cache-Audit

Ausgangsbasis: `9922f14` (main, Version 0.4.4). Die folgenden Fundstellen beziehen
sich auf diese unveränderte Basis; aktuelle Implementierungen stehen auf
`agent-a-perf`.

## Gefundene Fehler

| Datei und Zeile der Basis | Befund | Änderung / Prüfung |
| --- | --- | --- |
| `app/src/main/cpp/bonsai_jni.cpp:314–316` | `configure()` löscht Tokenliste und Kontext auch bei unverändertem Profil oder einer reinen Thread-Änderung. | KV bleibt bei gleichen Microbatch-/FA-/KV-Einstellungen erhalten; Threads werden mit `llama_set_n_threads` gesetzt. Reale Drei-Turn-JNI-Prüfung wendet zwischen Turns erneut das Profil an. |
| `app/src/main/java/de/bonsai/offline/BonsaiApp.java:346–350` | Antwortdetails übernehmen nur die letzte Tool-Runde; TTFT und Prefill können die eigentliche Wartezeit erheblich unterschätzen. | Gesamter Turn wird aggregiert, TTFT am ersten finalen Stream-Callback gemessen; einzelne Runden bleiben prüfbar. Robolectric prüft die Summen. |
| `app/src/main/cpp/bonsai_jni.cpp:478` mit `BonsaiApp.java:345–346` | Native Cache-Zahlen entstehen vor dem abschließenden `commitTurn`; nach Tool-Runden beschreiben sie den temporären Tool-Verlauf statt des endgültigen Caches. | `commitTurn` liefert finale KV-Positionen, Tokenzahl und seine zusätzlichen Decode-Kosten zurück. |

Keiner dieser Befunde beweist die Ursache des gemeldeten A55-Verlaufs. Im normalen
Send-Pfad wird `configure()` nicht zwischen jeder Frage aufgerufen. Es wurde keine
pauschale Cache-Löschung zwischen normalen Turns oder zwischen Tool-Runden gefunden.
Die vorhandene Reasoning-Bereinigung wurde erhalten; ihre tatsächlichen Kosten sind
jetzt sichtbar.

## Sämtliche Verwerfungs- und Abgleichpfade

- **BonsaiApp:** `resetConversationCache()` bei neuem/gewähltem Chat sowie vor
  Regeneration als eigener Verlauf. Löschen/Archivieren des aktuellen Chats läuft
  über `newChat()`. Absichtliche Kontextgrenzen; auf dem Worker geordnet. Danach
  wird nur das Systempräfix für die aktuelle GBNF-Variante vorgewärmt.
- **Modellladen/-entladen/-importieren, BackendGuard:** Modell-/Backend-/Kontextwechsel
  geben den vorherigen Kontext frei. BackendGuard lädt zunächst die skalare
  CPU-Referenz und führt Probes aus, dann das angeforderte Profil bzw. einen
  geprüften Fallback. Vorwärmen erfolgt erst **nach** diesen Prüfungen.
- **KernelOptimizer:** Microbatch-/FA-/KV-Wechsel benötigen einen neuen Kontext.
  Jede native Probe leert zu Beginn und Ende; ein normaler Turn ruft keine Probe
  auf. Nach erfolgreicher Optimierung wird erneut vorgewärmt.
- **Modelltest und bisheriger Benchmark:** jeder unabhängige Messprompt leert
  absichtlich den Cache. Der neue Chat-Benchmark leert nur vor Turn 1.
- **ToolLoop (unverändert):** erweitert den temporären Verlauf um Tool-Aufruf und
  Ergebnis, ohne `clearCache`. Native `generate()` synchronisiert das gemeinsame
  Tokenpräfix. Der finale App-Commit entfernt interne Tool-Runden, wodurch der
  Suffix ab der ersten Abweichung neu dekodiert werden muss.
- **`sync_cache`:** behält das gemeinsame Präfix; nur das abweichende Suffix wird
  entfernt. Falls die Runtime keine Suffix-Entfernung unterstützt, sicherer
  Vollaufbau (`suffix_removal_failed`). Bei vollständig gleichem Generierungsprompt
  wird das letzte Token für aktuelle Logits erneut dekodiert.
- **Kontextfenster:** `fit_prompt` entfernt ganze alte Turns unter Erhalt von
  Systemprompt und aktuellem Turn. `retain_window` reproduziert diese Auswahl beim
  nächsten vollständigen Verlauf. Ein unpassender anderer Verlauf setzt die
  Fensterzählung zurück (`conversation_window_changed`).
- **Reasoning-Cleanup:** nach jedem nativen Generate wird die Antwort ohne Reasoning
  gerendert und gegen den Cache abgeglichen. Das ist ein Suffix-Neuaufbau, kein
  zusätzlicher KV-Cache. `commitTurn` bereinigt auch angelieferte Assistant-Texte.
- **Fehler/Abbruch:** native Generate-/Commit-/Warmup-/Probe-Fehler und Stops verwerfen
  den Cache. Ein Java-Toolformatfehler lässt keinen Turn im Verlauf zurück; das
  nächste Generate gleicht das Tokenpräfix wieder ab.
- **GBNF an/aus:** unterschiedliche Systemtexte; kein Snapshot und kein Cache pro
  Variante. Vorwärmen verwendet exakt `ToolLoop.systemPrompt(gbnfTools())`.
  Die native Funktion schneidet auf ein Tokenpräfix, das mehrere vollständige
  Prompt-Renderings gemeinsam haben. Beim eigentlichen Generate wird jedes Token
  erneut auf Gleichheit geprüft. Zeitabhängige Templates verwenden weiterhin
  denselben beim Modellladen eingefrorenen Zeitpunkt.

Die Host-Prüfung benutzt SmolLM2-135M Q4_K_M, nicht Bonsai 2 4B PQ2_0. Für das
konkrete Bonsai-Template und Vulkan-Treiber bleibt der Fünf-Turn-Lauf auf dem A55
maßgeblich. Reproduktionsbefehle und Messgrenzen stehen in `README.md` und
`PR-DESCRIPTION.md` in diesem Verzeichnis.
