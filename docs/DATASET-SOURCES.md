# Quellen und Rechte

Nur die sechs vereinbarten Wissensbereiche werden integriert. Testfragen sind
Evaluationsmaterial und werden nicht als zusätzliche Wissenssammlung installiert.

| Bereich | Offizielle Herkunft | Tatsächlich geprüfter Stand |
|---|---|---|
| Wikipedia DE | https://dumps.wikimedia.org/dewiki/20261001/ | Gesamtdatei vollständig geprüft: 8.272.117.613 Bytes, offizielle SHA1 `e1cab27d8f03ab528e0514d587ddba39316bf343`, SHA256 `8da775ef18aa64d0cd1fc341489b14370ad7e36c0985c75b0bd6a6e8937f7811`. Erster Pilot-Teil separat: SHA256 `54a633868cbeb610eba877c307e8134799bcd7ab55b70a4271f65e71b1138327`. |
| Wikibooks DE | https://dumps.wikimedia.org/dewikibooks/20261001/ | Voller Multistream-Dump 111.252.025 Bytes, SHA1 `f70c66b5512bf1598f3fa838ea477f39915d3867`, SHA256 `d556fde087d84f242fb33da7c79743877d90f165ab295ecbc42f1879043c51b4`. |
| Stack Exchange | https://archive.org/details/stackexchange | Alle fünf vereinbarten Communities plus Stack-Overflow-Users vollständig anhand Archiv-MD5 und lokalem SHA256 geprüft. Offizieller Uploader `team+datadump@stackexchange.com`; April 2024, kein 2026-Datenstand. Einzelbelege unten. |
| Wikidata | https://dumps.wikimedia.org/wikidatawiki/entities/20261019/ | JSON-bz2: 103.488.697.311 Bytes, Last-Modified 21.10.2026; offizielle Soll-SHA1 `a05b8b809ab80576da6d838c67138ddf401055e6`. Download läuft; daraus keinen bereits verifizierten Rohdump oder produktiven Teilbestand ableiten. |
| Wiktionary DE | https://dumps.wikimedia.org/dewiktionary/20261001/ | Voller Multistream-Dump 319.344.180 Bytes, SHA1 `37d5518d0fc62b2f501edeb978d6f031586f1501`, SHA256 `7653c684149cdc153c9ef7d42badccaec027b1614b0a6103909b5577b0b4141b`. |
| Nährwerte / Zahlen | Open Food Facts, NIST/CODATA, BIPM, IAU | NIST ALLASCII vollständig heruntergeladen: CODATA 2022, 355 Konstanten. OFF-Pin `b24d50c4d1b8fd25205cd9ea39b8c6b96d1a4d0d` vom 24.06.2024, offizielles OFF-HF-Exportrepo, 7.683.519.547 Bytes, SHA256 `f9d8a7eafec73d0b97be6fa44e988efce77b9510a462061f1f344691bec5e6e8`; vollständiger Download und Prüfsumme bestanden. |

Wikimedia-Prüfungen vergleichen MD5/SHA1 aus dem jeweiligen `dumpstatus.json`;
SHA256 wird zusätzlich lokal berechnet. JSON-Belege enthalten die SHA256 der
offiziellen Statusdatei. Ein HTTP-Header ist kein abgeschlossener Downloadbeleg.

Geprüfte Stack-Exchange-Archive; die archivierten Dateizeiten liegen am
6./7. April 2024 (UTC). Jede lokale `*.verified.json` hält zusätzlich genaue
Archivzeit, MD5, Uploader und Herkunft fest:

| Archiv | Bytes | SHA256 |
|---|---:|---|
| Electronics | 637.518.528 | `8cc0c032c3b9b97a14c3dc62c24f23b05591dd807f246b4580ba3e725cf102df` |
| Physics | 729.272.818 | `62cd9b4c8567bb5b5f8e1796afa50d0838c3a5936a2c97c459fcec097133f6aa` |
| Super User | 1.294.499.667 | `8fd7d7acff1498193f8024a6a290ca46e3730d0442655739a8bdec49fd4bf5cb` |
| Mathematics | 3.629.699.855 | `77b974d2ae31c8d26b965a14c10fee97dbe238851f57e04a69fd785bde61721f` |
| Stack Overflow Posts | 23.026.928.274 | `1fcde86b9a0d701261a96698e78f65b7436d896eece5361ec7922d7c725c41cd` |
| Stack Overflow Users | 990.180.949 | `eaa9cad506cc2680ca067afbd569ab183964837971a60d56aacf9eb80b0d8703` |

Wikimedia-Texte: CC BY-SA 4.0, ältere/GFDL-Historie und Attribution über gespeicherte
Revisions-, Artikel- und Autorenhistorien-URLs beachten. Keine Bilder werden
importiert; Medien können andere Rechte haben. Die Originaltexte bleiben erhalten.
Vor Weitergabe kompletter Pakete Lizenzhinweise und Attributionskette prüfen:
https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use und
https://de.wiktionary.org/wiki/Wiktionary:Lizenzbestimmungen.

Wikidata-Strukturdaten stehen unter CC0. Quellen/Qualifikatoren werden nicht zu
eigenen Aussagen über Vollständigkeit umgedeutet. Details: https://www.wikidata.org/wiki/Wikidata:Licensing.

Stack Exchange: `ContentLicense` pro Frage/Antwort speichern, einschließlich
älterer CC-BY-SA-Versionen; keine pauschale Umdeklaration aller alten Posts.
Urhebername, Profil und Post-URL bleiben erhalten und werden in Suchquellen
ausgegeben. Fehlende Lizenz-/Urhebermetadaten sind eine Freigabelücke, keine
Erlaubnis zur Erfindung. Quelle: https://stackoverflow.com/help/licensing.

OFF: ODbL 1.0 mit Attribution und Share-Alike der Datenbank, Einzelinhalte nach
OFF-Datenbedingungen; keine Produktbilder. OFF separat von CC-BY-SA-Korpora
paketieren. https://openfoodfacts.github.io/robotoff/explanations/interactions-product-opener/
und https://huggingface.co/datasets/openfoodfacts/openfoodfacts-jsonl-export.
Der verifizierbare HF-Bulkexport ist von 2024; aktuelle Produktangaben dürfen daraus
nicht behauptet werden. Die Aktualisierung über einen neueren offiziellen
Bulkexport bleibt vor ernährungsbezogener Produktivabnahme offen.

NIST/CODATA: https://physics.nist.gov/cuu/Constants/Table/allascii.txt.
SI-Definitionen: https://www.bipm.org/en/measurement-units/si-defining-constants.
SI-Präfixe einschließlich Quetta/Ronna/Ronto/Quecto:
https://www.bipm.org/en/measurement-units/si-prefixes.
Basiseinheiten und abgeleitete Einheiten:
https://www.nist.gov/pml/special-publication-330/sp-330-section-2.
Julianisches Jahr/Lichtjahr: https://iauarchive.eso.org/public/themes/measuring/.
Es werden Zahlen/Faktendefinitionen und Quellenlinks gespeichert, keine BIPM-
Publikationen oder Produktabbildungen weiterverbreitet. CODATA-Jahr und
Abrufdatum werden getrennt benannt. Exakte, aber nur mit `...` dargestellte
Konstanten werden nicht als vollständige endliche Dezimalwerte gespeichert.

GermanQuAD: menschlich annotierter deutscher Testbestand von deepset,
https://huggingface.co/datasets/deepset/germanquad und
https://aclanthology.org/2021.mrqa-1.4/. Der alte S3-Link lieferte 404; verwendet
wurde der Parquet-Testsplit des offiziellen HF-Repositories. Dateihash und
tatsächlich passende Titel stehen im lokalen Fragenbeleg. Keine Aufnahme dieser
alten Antworttexte in den produktiven Wissensbestand.
