# Bonsai Offline v0.5.1.1

Version **0.5.1.1**, Versionscode **15**, Paket `de.bonsai.offline.debug`,
ARM64 mit CPU und experimentellem Vulkan. Enthält den in `main` gemergten PR #23.

- Weniger UI-Arbeit beim Streaming: Text-, Thinking- und Prefill-Updates ändern
  nur Antwort und Fortschritt; unveränderte Textpuffer und Metadaten bleiben erhalten.
- Die Lichteffekte bleiben auch während der Berechnung animiert. Weniger
  Zeichenschichten, begrenzte Highlightbereiche und ein gemeinsamer, an die
  Bildsynchronisation gebundener Taktgeber reduzieren den Aufwand.
- Langsame Hintergrundfelder werden seltener aktualisiert als aktive Konturen;
  Farbpaletten, Spotanzahl und Zustandswechsel bleiben erhalten.
- Der Sekunden-Timer aktualisiert nur den Status; Scroll-Aufträge werden
  zusammengefasst. Hintergrund/Wiederaufnahme und Pop-ups sind geprüft.

Im nativen Host-Zeichentest benötigen Kontur und Antwortlinie beim Generieren
rund 50–60 % weniger Zeit pro Bild. Das sind Software-Canvas-Werte auf dem Host,
keine A55-Inferenz- oder Android-GPU-Messung. [Messmethode und Einzelwerte](../../docs/validation/PERFORMANCE-UI.md).

Releasevorbereitung: Der signierte CPU/Vulkan-Build und seine konkreten Hash-,
Signatur- und Updatebelege werden nach dem Build aus dem versionierten
`main`-Stand ergänzt. Physische A55-Abnahme bleibt ausstehend.

Die Signierung erfolgt mit dem bereitgestellten Release-Schlüssel. Er entspricht
v0.5.1, v0.5 und 0.4.3/0.4.4. Direkte Updates von 0.4.5/0.3.0 sind wegen eines
anderen Zertifikats weiterhin nicht möglich. [Signierhinweise](../../SIGNIEREN.md).

Modellgewichte, RAG-/Wikidata-Datenbanken und Embedding-Gewichte sind nicht
Bestandteil der APK. Vulkan bleibt experimentell.
