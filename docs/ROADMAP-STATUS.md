# Roadmap-Abgleich, 25. Oktober 2026

Arbeitsstand auf Basis von `ROADMAP.md`, `README.md` und den vorhandenen
Implementierungen. STT, TTS und Echtwelt-/Gerätetests sind auf Nutzerwunsch ausgeschlossen. Der Schwerpunkt liegt auf Softwarefunktionen und automatisierten Prüfungen. Historische
Hostmessungen in `DATABASE-VALIDATION.md` werden hier nicht als neue Messungen
ausgegeben. Es wurde kein Gerätelauf und kein Vollimport ausgeführt.

## Neue Produktfunktionen

| Bereich | Umsetzung | Prüfung |
|---|---|---|
| KI-Ausgabe | Native Markdown-Darstellung einschließlich Streaming, Listen, Code, Links und Tabellen; Originaltext bleibt gespeichert | `AnswerMarkdownTest`, bestehende UI-/Streamtests |
| Gedächtnis | Schlüsselübersicht, Textsuche, explizites Hinzufügen/Bearbeiten und bestätigtes Löschen; optionale semantische Suche mit persistentem lokalem Index und Textfallback | `PersonalDataActivityTest`, `PersonalBackupTest`, `MemorySearchTest` |
| Mathematik | Allgemeine lokale SymPy-Engine; alte Polynomengine entfernt. Algebra, Analysis, Systeme, ODE, Matrizen und Zahlentheorie | `test_sympy_engine.py` mit tatsächlichem SymPy, `SympyMathTest` für Android-Protokoll/Abbruch/Timeout |
| Toolintegration | SymPy-mathe mit optionalen Stringargumenten und erinnern in Dispatcher, Systemprompt und GBNF | `SympyMathTest`, `FeatureToolsTest`, native Grammatikprüfung |
| Reranking | Optionale Titel-/Abschnittsgewichtung bestehender Suchkandidaten; keine neuen Quellen oder Netzaufrufe | `FeatureToolsTest`; keine Behauptung eines gemessenen Recall-Gewinns |
| Herkunft und Datum | Manuell/Chat-Tool/Altbestand, vorhandenes Änderungsdatum bleibt bei Migration erhalten | Version-3-zu-4-Migration; ältere Migrationstests |
| Datensicherung | SAF-JSON-Export aller Chats/Archive und Erinnerungen; transaktionaler ergänzender Import, vorhandene Schlüssel gewinnen | Roundtrip mit 105 archivierten Chats; fehlerhafter Import rollt auch vorherige Einträge zurück |
| Paket-Rollback | Installierte vollständige Generation auswählen; Dateien, Schema und Index erneut prüfen; atomarer Registrywechsel, Aktivierungsstatus bleibt erhalten | Erfolgsfall, beschädigte Generation, fremder Pfad und Abbruch in `KnowledgePackagesTest` |

Backups sind lesbarer Text, auf 16 MiB begrenzt, umfassen keine Modelle,
Wissenspakete oder Einstellungen und erzeugen bei wiederholtem Import zusätzliche
Chats. Herkunft ist eine Bearbeitungsquelle, kein Link zu einem Ursprungschat.
Die semantische Suche ist standardmäßig aus. Sie benötigt das importierte lokale
Embedding-Modell. Pro Suche entstehen höchstens 8 neue Vektoren für maximal 200
aktuelle Erinnerungen; Warnungen kennzeichnen Teilindizes und Textfallback.
Der Index ist abgeleitet, nicht Teil des Backups. Änderungen invalidieren Vektoren;
Löschen entfernt sie per Fremdschlüssel. Die SymPy-Eingabe ist auf 4096 Zeichen und 512 AST-Knoten begrenzt.
Mathematische Funktionen und rationale Ausdrücke sind möglich, freie
Python-Ausführung ist ausgeschlossen. Pro Auftrag gelten 20 Sekunden Gesamtzeit. Alte Paketgenerationen bleiben standardmäßig erhalten; nur
explizit ausgewählte, nicht registrierte Generationen sind nach Bestätigung
entfernbar. Ein Trash-Verzeichnis erlaubt die spätere Bereinigung bei Dateisperren.

## Vollständiger Meilenstein-Abgleich

| Aufgabe | Vorhanden / verbleibend | Voraussetzung für Abschluss |
|---|---|---|
| M0.1 CPU auf A55 | Runtime, Modellimport, Prüfmodus und Testplan vorhanden; reale Antwortqualität offen | A55 und offizielle geprüfte Modellgewichte |
| M0.2 Vulkan | Optionaler Backendpfad vorhanden; unabhängige Gerätemessung offen | Dasselbe A55 und vergleichbare CPU-/Vulkan-Läufe |
| M0.3 Embedding-Parität | Pin, Profilvertrag und historische Pilotnachweise vorhanden | Originalmodelle, numerische Referenzen und reproduzierbarer Lauf |
| M0.4 Speicher/Lebenszyklus | Lebenszyklus implementiert, gemeinsamer Peak offen | Bonsai und Encoder auf Zielhardware |
| M1.1 Wikipedia-Pilot | Pipeline und dokumentierter echter Pilot vorhanden | Originalartefakte für erneute Sicht-/Datenprüfung |
| M1.2 Hybridpaket | FTS4, IVF, RRF und dokumentierte echte Baselines vorhanden | Paket, Modelle und Qualitätsset für erneute Messung |
| M1.3 Releaseimport | SAF, Integrität, Abbruch und atomare Aktivierung implementiert | Release-/SAF-Abnahme auf A55 |
| M1.4 End-to-End-RAG | Such-/Tool-/Quellenpfad implementiert | Vollständiger Flugmodusdurchlauf und Quellenprüfung auf A55 |
| M2.1 Speicherpilot | Preflight, Messwerkzeuge und historische Messung vorhanden | Aktuelle Datenmaschine und ausreichender Speicher |
| M2.2 Voll-Wikipedia | Resume, Shards und diskbasierter IVF-Bau vorhanden; Vollbestand nicht fertig nachgewiesen | Voll-Dump, Modellgewichte, ausreichend RAM/Disk/Rechenzeit |
| M2.3 Millionenindex | Format-/Rankingtests vorhanden; reale A55-Skalierung offen | Produktiver Index und Zielgerät |
| M3.1 Multi-Paketvertrag | Typ-/Korpuszuordnung und unabhängige Aktivierung implementiert | Gemeinsame Paket-/Geräteabnahme |
| M3.2 Wikibooks | Adapter und dokumentierter vollständiger Text-/Vektorbestand vorhanden | Artefakte, korpuseigene Retrievalabnahme und Paketfreigabe |
| M3.3 Stack Exchange | Auswahladapter, Textimport und Routing vorhanden | Vektorläufe, Qualitätsabnahme und vollständige Pakete |
| M4.1 Wikidata | Importprofil, Read-only-Tools, Schema und Tests vorhanden | Produktiver echter Teilbestand und Fach-/Geräteprüfung |
| M4.2 Wiktionary | Strukturierter Vollimport, Wörterbuchtool und dokumentiertes Paket vorhanden | Originalpaket und Geräte-/Sprachqualitätsabnahme |
| M4.3 Tabellen | Nährwerte, Konstanten und Einheiten implementiert, echte Pakete dokumentiert | Originalpakete und gemeinsame fachliche Geräteabnahme |
| M5.1 Toolqualität | Begrenzte Schleife, striktes JSON, Grammatik, neue Toolintegration und 100 Dispatcherfälle vorhanden | Echte Modellwahlqualität bleibt ungemessen; kein Zielwert behauptet. Echtwelttests ausgelassen |
| M5.2 Mathematikengine | SymPy 1.14.0 und mpmath mit eingebetteter ARM64-Python-Laufzeit; alte Java-Polynomengine entfernt | Android-Build und automatisierte CAS-/Protokolltests; keine Geräteprüfung oder Leistungsvergleiche behauptet |
| M5.3 Gedächtnis | Verwaltung, Datum/Herkunft, Migration, Textsuche und optionaler persistenter semantischer Index implementiert | Für semantische Nutzung lokales Query-Modell importieren; echte semantische Qualität ist nicht durch Fake-Encoder-Tests belegt |
| M6.1 Ressourcenoptimierung | Bestehende Optimierungen und Messinstrumente vorhanden | Gemessener A55-Engpass, Vergleichsbaseline und Qualitäts-/RAM-Gates; keine spekulative NEON-Umschreibung |
| M6.2 Paketupdates | Immutable Generationen, atomarer Import, geprüfter Rollback und bestätigte Bereinigung nicht registrierter Generationen vorhanden | Aktive/registrierte Generationen bleiben auch deaktiviert geschützt. Gerätetests ausgelassen |
| M6.3 Produktfreigabe | Signierworkflow und lokaler Backupweg vorhanden | Signierschlüssel, kompatibles Geräteupdate, Offline-Ersteinrichtung und ≥60-Minuten-Gerätetest |
| Langfristig: Reranking | Optionaler regelbasierter Titel-/Abschnittsreranker implementiert | Standardmäßig aus; kein gemessener Qualitätsgewinn zugesagt |
| Langfristig: Energie/NEON/NPU | Keine neue Freigabe oder Leistungsbehauptung | Messbarer Engpass bzw. tatsächlicher kompatibler Exynos-Graph; Roadmap behandelt NPU als Forschung |
| STT / TTS | Ausgeschlossen | Kein Teil dieses Auftrags |

## Umfang nach Nutzerpräzisierung

Echtwelt- und A55-Tests werden ausdrücklich übersprungen. Die noch historischen
Geräte-Abnahmebedingungen der Roadmap sind dadurch nicht als erfüllt markiert.
Vollständige Datenbestände/Vektorisierung sind Datenproduktionsläufe und wurden
hier nicht durchgeführt; die vorhandenen Pipelines bleiben verfügbar. Eine native NEON-Umschreibung oder NPU-Inferenz wird nicht behauptet.
Der Mathematikpfad ist inzwischen durch das allgemeine SymPy-Backend ersetzt.

Die Umgebung enthält SDK/NDK/JDK und Quelltexte, keine produktiven Modelle oder
Datenpakete. Suchfunktionen zeigen fehlende lokale Bestände kontrolliert an.
Der Runtime-Backport wurde über das vorhandene Setup-Skript anhand gepinnter
Prüfsummen eingerichtet; es wurde keine neue Runtime-Patchvariante erstellt.

## Reproduzierbare Prüfung

```sh
python3 scripts/check_project.py
python3 -m unittest discover -s tests -p 'test_*.py'
python3 -m unittest discover -s database_pipeline/tests
python3 -m unittest discover -s rag/tests
python3 -m unittest discover -s wikidata/tests
python3 scripts/fetch_runtime.py
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Für den lokalen Testlauf wurde der bereits vorhandene instrumentierte
Android-35-JAR-Cache mit einem temporären Gradle-Init-Skript ausgewählt und
der System-Truststore für den Umgebungsproxy verwendet. Diese Einstellungen
sind keine zusätzliche Projekt- oder Appabhängigkeit.

## Entscheidung zum Mathematikpfad

Auf ausdrücklichen Nutzerwunsch ersetzt echtes SymPy die frühere Java-Polynomengine
vollständig. Die zusätzliche Python-Laufzeit und Abhängigkeiten werden in der APK
mitgeliefert. Prozessgrenzen, Argumentvertrag und bekannte Grenzen stehen unter
[SYMPY.md](SYMPY.md). Der einfache numerische Rechner `calc` ist eigenständig und
bleibt erhalten. Die Funktion ist nicht mehr auf Polynome oder Grad 12 begrenzt.

## Frühere Nachweise vor dem SymPy-Ersatz (historisch)

- Gesamtlauf Android/Robolectric: 195 Fälle, 193 bestanden, 2 optionale
  Echtdatenfälle übersprungen; keine Fehler.
- Zusätzlich enthalten: 60 mathematische Aufgaben und 100 Dispatcherfälle als
  Falltabellen innerhalb der jeweiligen Tests, keine 100 Modellinferenzläufe.
- RAG: 50 Tests bestanden; Datenpipeline: 17 bestanden; Wikidata: 14 bestanden,
  2 optionale Tests übersprungen; allgemeine Python-Suite: 5 bestanden.
- Native Tool-/Final-Grammatik: neue `mathe`-/`erinnern`-Aufrufe akzeptiert,
  Final-Runde sperrt diese Tools; vorhandene Ablehnungs-/Samplerprüfungen bestanden.
- Projektprüfung für UTF-8, Reasoningfilter, Manifest/JNI und Runtime-Pin bestanden.
- Android-Lint ohne Fehler. Geräteabnahme ausdrücklich ausgelassen.
- ARM64-CPU-Debug-APK erfolgreich gebaut (`:app:assembleDebug`). Nach den letzten
  Änderungen an Sicherungsgrößenlimit und Gedächtnisansicht wurden die vier
  betroffenen Tests sowie Lint und APK-Build erneut erfolgreich ausgeführt.
  Das ist ein Debug-Build, keine signierte Releaseveröffentlichung.

## SymPy-Nachtrag

Die ältere Mathematik-/Dispatcher-Falltabellenprüfung wurde mit der entfernten
Engine abgelöst. Neue Nachweise und der aktuelle APK-Build stehen in
[SYMPY.md](SYMPY.md); historische Zählungen oben sind kein Nachweis für SymPy.
