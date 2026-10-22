# Bonsai Offline v0.5.1

Version **0.5.1**, Versionscode **14**, Paket `de.bonsai.offline.debug`,
ARM64 mit CPU und experimentellem Vulkan. Enthält den in `main` gemergten PR #20.

- **Systemprompt bearbeiten:** Menü → Modell → Antworten anpassen. Eigene
  Vorgaben sind direkt bearbeitbar, Tool-Anweisungen erst nach einer Warnung.
  Lokale Speicherung, Standard-Reset und Entwurfserhalt bei Drehung sind enthalten.
- **Live-Zeile unter Bonsai:** aktueller Thinking-Text oder Prompt-Cache-Fortschritt
  und gemessene Prefill-Tokens/s, jeweils einzeilig. Die Endantwort wird wieder
  normal mehrzeilig angezeigt. Thinking bleibt vorübergehend und wird nicht
  als Chatantwort oder in Logs gespeichert. Ohne Modell-Thinking kein erfundener Text.
- **Vergleichbare Messungen:** Chat-Benchmark exportiert den Systemprompt-Hash,
  ohne eigene Vorgaben offenzulegen; abweichende Prompts werden erkannt.

Die vorherige Featurevalidierung bestand 151 lokale Host-Tests und den
CPU/Vulkan-Build. Release-spezifische APK-, Signatur- und Buildnachweise werden
nach dem Build unter `release/0.5.1` ergänzt. Physische A55-Abnahme und persönliche
UX-Ergebnisse bleiben ausstehend; siehe [Testplan](../../A55-ECHTWELT-TESTPLAN.md).

Die Signierung erfolgt mit dem bereitgestellten Release-Schlüssel. Er entspricht
v0.5 und 0.4.3/0.4.4. Direkte Updates von 0.4.5/0.3.0 sind wegen eines anderen
Zertifikats weiterhin nicht möglich. Bestehende Daten nicht durch vorschnelle
Deinstallation löschen. [Signierhinweise](../../SIGNIEREN.md).

Modellgewichte, RAG-/Wikidata-Datenbanken und Embedding-Gewichte sind nicht
Bestandteil der APK. Die NPU wird nicht unterstützt; Vulkan bleibt experimentell.
