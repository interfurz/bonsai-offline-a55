# KV-Cache und begrenztes Reasoning

## Änderung am 25.10.2026: Abschluss ohne erneutes Prefill

Die Generierung und das abschließende `commitTurn` kürzen den KV-Cache jetzt nur auf das gemeinsame Präfix mit dem bereinigten Verlauf. Sie dekodieren nach der Antwort keine Tokens mehr. Reasoning, interne Tool-Runden und GBNF-Markierungen werden dabei weiterhin aus dem Cache des sichtbaren Chats entfernt. Ein fehlender kanonischer Suffix wird erst beim Prefill der nächsten Anfrage verarbeitet. Damit entfällt die Modellberechnung, während der nach der sichtbaren Antwort noch der Stoppbutton aktiv blieb; die nächste Anfrage kann entsprechend mehr Prefill benötigen.

`cleanup_decoded_tokens` und `commit_decoded_tokens` sind null. `cleanup_pending_tokens` und `commit_pending_tokens` geben an, wie viele Tokens beim jeweiligen Abgleich noch fehlen. `cached_tokens` und `kv_positions` beschreiben ausschließlich das tatsächlich erhaltene Präfix. Die unten dokumentierten vollständigen KV-Größen und Tokenzahlen stammen aus dem älteren Verfahren vom 22.10.

Der bestehende JNI-Test `CacheSmoke` prüft zusätzlich den Abschluss ohne Decode, ein kaltes Commit ohne Modellberechnung und identische warme/kalte Folgeantworten nach dem Entfernen interner Tool-Runden. Die Prüfung des 600-Token-Reasoning-Budgets und drei aufeinanderfolgender Turns bleibt enthalten. Ein A55-Nachtest mit Bonsai ist weiterhin erforderlich, um die konkrete Wartezeit auf dem Gerät zu bestätigen.

Ausgeführt am 25.10.2026:

- `python3 scripts/check_project.py` und `java scripts/ParseJava.java`: erfolgreich.
- `./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: erfolgreich; 173 Tests bestanden, zwei übersprungen. Lint: null Fehler, 20 Warnungen. ARM64-Debug-APK gebaut.
- `CacheSmoke` gegen die echte Host-JNI-Bibliothek und dasselbe unten beschriebene SmolLM2-GGUF: erfolgreich, einschließlich warmer/kalter Folgeantworten nach Tool-Bereinigung und Kontextfensterwechsel. Nach 600 Reasoning-Tokens: 32 gültige KV-Positionen, null beim Abschluss dekodierte Tokens, drei ausstehende kanonische Tokens und etwa 2,6 ms `cleanup_ms`. Im Folgeturn des Warm/Kalt-Vergleichs wurden 23 von 43 Prompt-Tokens wiederverwendet und 20 neu dekodiert. Diese Host-Zeit ist keine A55-Messung.

## Frühere Prüfung vom 22.10.2026

Geprüft am 22.10.2026 in einer Linux-Build-Umgebung. Kein Galaxy-A55-Benchmark und kein Bonsai-PQ2_0-Gerätetest.

## Ausgeführte Prüfungen

- `./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: 74 Java-/Android-Tests erfolgreich; Lint ohne Fehler, acht Warnungen zur Konfiguration und zu UI-Texten; ARM64-Debug-APK gebaut.
- `python3 scripts/check_project.py`: Manifest/XML, JNI-Namen, gebundene Runtime, striktes UTF-8-Streaming sowie Präfixabgleich und Reasoning-Filter über sämtliche Byte-Schnittstellen erfolgreich.
- Java-Tests prüfen Live-Streaming vor Abschluss, verborgene Tool-/GBNF-Markierungen, geteilte Emoji, Reasoning-Bereinigung neuer und alter SQLite-Zeilen, Worker-Reset bei neuem/ausgewähltem Chat, finales Cache-Commit ohne Tool-Runden sowie das gemeinsame Reasoning-Budget über mehrere Tool-Runden.
- `tests/CacheSmoke.java` läuft gegen die echte JNI-Bibliothek mit einem kleinen GGUF, ohne Mock des KV-Caches. Geprüft werden identische warme/kalte Antworten bei gleichem Seed, weniger neu dekodierte Prompt-Tokens, expliziter Cache-Reset, erzwungenes `</think>` bei erschöpftem Budget, lange Thinking-Ausgabe, bereinigte KV-Positionen sowie ältere Turns am Kontextlimit und anschließende Wiederverwendung.

## Echtes Host-Testmodell

Verwendet wurde `bartowski/SmolLM2-135M-Instruct-GGUF`, Datei `SmolLM2-135M-Instruct-Q4_K_M.gguf`, nicht Bonsai 2 4B. Download:

https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf

SHA256: `2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d`.

Der Test verwendet `n_ctx=1024`, zwei CPU-Threads und Seed 42. Im Folgeturn wurden 25 von 43 Prompt-Tokens wiederverwendet und nur 18 dekodiert. Warme und kalte Endantwort waren identisch. Ein künstlich per Grammatik erzeugter langer Thinking-Block erreichte genau 600 gezählte Reasoning-Tokens; `reasoning_capped=true` bestätigt das erzwungene Ende. Danach blieben 35 KV-Positionen für den bereinigten Verlauf. Die tatsächliche Anzahl der KV-Positionen stimmte mit der bereinigten Token-Liste überein. Modellgewichte und Host-Testabhängigkeiten werden nicht mit der App ausgeliefert.

Reproduktion mit JDK 17, CMake, Ninja bzw. Make, gebundenem Runtime-Checkout, einem lokalen Test-GGUF und einem lokalen `org.json`-JAR (im Test `org.json:json:20240303`):

```bash
cmake -S app/src/main/cpp -B build/grammar-check -DBONSAI_HOST_SMOKE=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build/grammar-check --target bonsai-jni --parallel 2
javac -cp /pfad/json.jar -d build/cache-smoke app/src/main/java/de/bonsai/offline/NativeEngine.java tests/CacheSmoke.java
java -Djava.library.path=build/grammar-check -cp build/cache-smoke:/pfad/json.jar de.bonsai.offline.CacheSmoke /pfad/testmodell.gguf
```

## Noch auf dem Gerät prüfen

Mit derselben Bonsai-PQ2_0-Datei auf dem A55 mehrere kurze Fragen hintereinander stellen und TTFT sowie `reused_prompt_tokens`/`decoded_prompt_tokens` vergleichen. Einen langen Chat bis zum Kontextwechsel fortsetzen, danach einen neuen Chat starten. Keine alten Inhalte oder Thinking-Blöcke dürfen in Antworten oder gespeicherten Verläufen wieder erscheinen. Der Nachfolgebuild zeigt aktuelles Thinking separat und vorübergehend in einer Statuszeile; diese darf nach Abbruch/Chatwechsel keine alten Gedanken übernehmen. Tests mit ausgeschalteter und eingeschalteter GBNF-Option wiederholen.

Es werden kein zweiter KV-Cache, kein größeres `n_ctx` und kein State-Snapshot angelegt. Die nötigen Token-IDs und kleinen Streaming-Puffer bedeuten geringe Verwaltungsdaten zusätzlich zum unveränderten KV-RAM. Ein unverändertes Systempräfix und unterstütztes `llama_memory_seq_rm` sind Voraussetzung für maximale Wiederverwendung; bei fehlender Suffix-Löschunterstützung wird sicher vollständig neu aufgebaut. Cache-Reset, Fensterwechsel, Thinking-Bereinigung und das Entfernen interner Tool-Runden können einmalig zusätzliches Decode erfordern. Die erreichten A55-Tokens/s und TTFT sind mit diesem Host-Test nicht nachgewiesen.
