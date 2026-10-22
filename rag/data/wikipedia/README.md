# Kleine, selbst verfasste Testdaten

`complex.xml` ist ein synthetischer MediaWiki-pages-articles-Export mit drei
inhaltlichen Artikeln, fünf Weiterleitungen und einer Diskussionsseite. Die
Texte und Revisionsnummern sind erfunden, **kein echter Wikipedia-Snapshot**.
Die Wikipedia-URLs testen das Quellenformat und belegen keinen realen Inhalt.
Es wurden keine Wikipedia-Daten heruntergeladen.

Abgedeckt: Einleitung, verschachtelte Überschriften, kurze und lange Abschnitte,
UTF-8/Emoji, ungebrochene Wörter, Infobox/Navbox, Referenzen, Galerie, Tabelle,
Kategorie, HTML, nowiki, Inline-Vorlage, Mathematik, Markdown-Links und Listen.
Weiterleitungen enthalten Kette, Fragment, fehlendes Ziel und Zyklus.

`example.sqlite`: vollständiger Import dieses Fixtures mit FTS5 **und FTS4**,
3 Artikel + 5 gespeicherte Weiterleitungen, 20 Chunks, keine Vektoren. Die Datenbank
ist direkt mit `python -m rag search` und dem vorhandenen Android-Leser nutzbar.
Sie dient nur Funktionstests. Die Datei wurde sauber geschlossen und benötigt
keine WAL-/SHM-Begleitdateien.

Aus dem Repository-Stamm reproduzieren (neuen Zielpfad verwenden):

```sh
python -m rag ingest rag/data/wikipedia/complex.xml rag/output/example.sqlite
python -m rag search rag/output/example.sqlite Lichtreaktion
```

Die eingecheckte Datei wird in `test_wikipedia.py` mit dem aktuellen Import
inhaltlich verglichen; Laufzeit, absoluter Quellpfad und Dateizeit in den
Import-Metadaten sind naturgemäß vom Erzeugungslauf abhängig.
