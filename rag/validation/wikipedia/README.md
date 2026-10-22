# Host-Validierung des Wikipedia-Imports

Gemessen am 22.10.2026, Linux x86_64, Python 3.12.14.
Nur kleine, selbst verfasste MediaWiki-Fixtures; kein Wikipedia-Download.
Keine Windows-, RTX-4060-, A55- oder Voll-Dump-Messung.

## Automatisierte Tests

Integration nach EmbeddingGemma-PR #12: **39 Tests bestanden**, keine
übersprungenen Tests; [integration-tests.txt](integration-tests.txt).
Inhaltlich stabile Chunk-IDs verändern die Exportreihenfolge. Der bestehende
EmbeddingGemma-Exporttest prüft deshalb die Zuordnung von Berlin-Titel und
Berlin-Text über alle Batches, ohne Berlin als erste Zeile vorauszusetzen.
Die produktive Embedding-Implementierung wurde dafür nicht geändert.

`python -m unittest discover -s rag/tests -v`: **38 Tests erfolgreich**.
Das vollständige Protokoll steht in [tests.txt](tests.txt). Die bisherigen
RAG-/IVF-Tests wurden mit ausgeführt. Neu geprüft werden Markdown-Bereinigung,
Absatz- und Themen-Grenzen, separate Einleitung, Hierarchie, Unicode-Budgets,
stabile int64-IDs über Reihenfolge/Worker/Resume, Revisionsquellen, Originaltext-
Rundlauf, FTS5/FTS4, Weiterleitungsketten/Fragmente/Zyklen/fehlende Ziele,
Multistream-bzip2, moderne Slots, History-Ablehnung, Invalid-Record-Journal,
Crash-Rollback inklusive FTS, Resume-Guards und Dateisperre. Der vorhandene
hash-v1-Vektorschreiber wurde für die Schnittstellenprüfung benutzt; kein Modell geladen.
Der neue CI-Workflow ist für Ubuntu und Windows konfiguriert; diese lokalen
Resultate sind keine Behauptung eines bereits erfolgreichen Windows-CI-Laufs.

## Kleine Prozessmessungen

Befehl (frische Zielpfade verwenden):

```sh
python -m rag.benchmark_ingest --articles 60 300 --workers 1 4 --work-dir work/wiki-benchmark --output rag/output/host-ingest.json
```

| Artikel | Chunks | Worker | FTS4 | Artikel/s | DB MiB | RSS-Summe MiB | beobachteter Disk-Peak MiB |
| ---: | ---: | ---: | :---: | ---: | ---: | ---: | ---: |
| 60 | 400 | 1 | ja | 245.1 | 1.73 | 22.2 | 1.73 |
| 60 | 400 | 1 | nein | 244.5 | 0.57 | 20.6 | 0.57 |
| 60 | 400 | 4 | ja | 161.0 | 1.73 | 106.9 | 1.73 |
| 60 | 400 | 4 | nein | 154.9 | 0.57 | 105.8 | 0.57 |
| 300 | 2000 | 1 | ja | 324.4 | 7.71 | 29.6 | 13.29 |
| 300 | 2000 | 1 | nein | 366.1 | 2.23 | 22.9 | 6.59 |
| 300 | 2000 | 4 | ja | 343.5 | 7.71 | 114.4 | 14.57 |
| 300 | 2000 | 4 | nein | 376.5 | 2.23 | 108.1 | 6.59 |

Eingaben: ca. 0,68 und 3,38 MiB XML. Datensätze wiederholen die drei Fixture-
Artikel mit neuen IDs/Titeln. Dies ist ein technischer Durchsatztest und keine
repräsentative deutsche Wikipedia-Verteilung oder Suchqualitätsbewertung.
Vier Worker benötigen bei kleinen Mengen mehr Startzeit; sie helfen erst im
größeren Fixture-Lauf. FTS4 benötigt zusätzlichen Klartext und erheblich mehr Platz.
Default bleibt für Android-Kompatibilität eingeschaltet.

RSS: alle 20 ms Summe aus Importer und Nachkommen inkl. Spawn-Hilfsprozess,
geteilte Seiten mehrfach gezählt; kurze Spitzen können fehlen. Disk-Peak:
beobachtete Summe aus DB, WAL/SHM und kleinen Laufdateien inkl. finaler DB-Größe.
Zeit enthält Prozessstart und DB-Abschluss. Je Kombination ein Lauf, Cachezustand
unkontrolliert. Die Einzelwerte stehen in [host-ingest.json](host-ingest.json).
Der geringe Anstieg von 60 auf 300 Artikel belegt nur das Verhalten dieser
kleinen Läufe; keine Hochrechnung auf Millionen Artikel oder feste RAM-Garantie.

## Ausgelieferte Beispieldatenbank

[example.sqlite](../../data/wikipedia/example.sqlite): 184,320 Bytes, 3 Inhaltsartikel,
5 gespeicherte Weiterleitungen, 20 Chunks, FTS5 und FTS4, keine Vektoren.
Integritätsprüfung: `ok`, Journalmodus `delete`, Schema `user_version=1`.
Originaltexte/Revisionsmetadaten und FTS-Treffer werden in Tests geprüft.
Checksumme, Zeilenzahlen und Paketversionen stehen in [example.json](example.json).

Vor dem echten Laptop-Lauf: kleine reale Artikelauswahl prüfen, lokalen
Durchsatz/Speicher/Diskbedarf neu messen und vollständige Dump-Prüfsumme
kontrollieren. Befehle und bekannte Konvertierungsverluste: [WIKIPEDIA.md](../../WIKIPEDIA.md).
