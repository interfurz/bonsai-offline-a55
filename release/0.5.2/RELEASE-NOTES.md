# Bonsai Offline v0.5.2

Version **0.5.2**, Versionscode **17**, Paket `de.bonsai.offline.debug`, ARM64.
Die bisherige Release-Signieridentität und vorhandene App-Daten bleiben bei
einem kompatiblen Update erhalten. Die App benötigt keine Internetberechtigung.

- Offline-Wissenspakete über die Android-Dateiauswahl importieren; große Dateien
  werden gestreamt. Prüfsummen, SQLite-Schema/-Integrität und Vektor-/Chunk-IDs
  werden vor der Aktivierung geprüft. Platzmangel und Abbruch erhalten das alte
  Paket; neue Generationen überschreiben keine gemappten Dateien.
- Pakete einzeln aktivieren und deaktivieren. Mehrere kompatible Textkorpora
  teilen den Query-Encoder und werden gemeinsam gerankt. Paket-ID plus Chunk-ID
  verhindert verwechselte Quellen bei gleichen lokalen IDs.
- Sicherer Wörterbuchzugriff mit Wortformen und Schreibvarianten sowie typisierte
  Nährwert-, Konstanten- und Einheitenabfragen. Fehlende Nährwerte bleiben fehlend.
  Neue Tools sind in Grammatik, Argumentprüfung und Systemprompt eingebunden;
  importierte Texte erteilen keine ausführbaren Anweisungen.
- Verbesserte FTS4-Relevanz für den Android-Pfad. Wiederaufnehmbare Downloads und
  Embedding-Shards sowie direkter IVF-Bau aus verifizierten Shards auf dem Laptop.

Unter **Menü → Offline-Wissenspakete** zuerst das gepinnte EmbeddingGemma-2-
Querymodell und danach die gewünschten `.bonsai`-Pakete importieren. Wörterbuch,
Nährwerte und Einheiten benötigen keine Embeddings. Modellgewichte und große
Datenbanken sind nicht in der APK oder im Quellcode-Commit enthalten.

Fertige lokale Bestände umfassen zwei alternative Wikipedia-Piloten mit
1.000/10.000 Artikeln, den vollständigen deutschen Wiktionary-Dump, einen
OFF-Marktbestand für Deutschland aus Juni 2024 und CODATA-/SI-Tabellen.
Der Wikipedia-Vollbestand, Wikidata und weitere Textpakete werden noch
verarbeitet. **Diese App-Veröffentlichung ist keine abgeschlossene Freigabe der
gesamten Offline-Wissenssammlung.** OFF ist kein aktueller 2026-Nährwertnachweis.

Die Datenpipelines und Importpfade wurden mit echten Dumps und großen Paketen
auf dem Host geprüft. Die genauen Build-, Test-, Hash- und Signaturbelege werden
als Release-Assets beigefügt. Physische A55-Installation, Modellinferenz,
RAM-/Latenz- und Vulkan-Geräteprüfung bleiben offen; Vulkan ist experimentell.

[Pipeline und PowerShell-Befehle](../../docs/DATABASE-PIPELINE.md) ·
[Quellen und Lizenzen](../../docs/DATASET-SOURCES.md) ·
[Paketvertrag und Installation](../../docs/OFFLINE-PACKAGES.md) ·
[Gemessene Validierung](../../docs/DATABASE-VALIDATION.md) ·
[Signierhinweise](../../SIGNIEREN.md).
