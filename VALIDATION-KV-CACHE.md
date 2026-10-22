# KV-Cache und begrenztes Reasoning

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
