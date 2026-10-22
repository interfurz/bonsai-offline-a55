# Weniger UI-Last während der Inferenz

Ausgangsbasis: `2dfe2c4` (`main`, Version 0.5.1). Die Untersuchung hat zwei
reproduzierbare Quellen zusätzlicher Arbeit im UI-Pfad gefunden. Ein physischer
A55-Durchlauf liegt für diese Korrektur noch nicht vor; der Anteil dieser Last
am gemeldeten Geschwindigkeitseinbruch und ein Tokens/s-Gewinn sind damit offen.

## Befunde und Korrektur

Text-, Thinking- und Prefill-Callbacks riefen bisher den vollständigen
Activity-Renderpfad auf. Dieser prüfte unter anderem die Modelldatei, aktualisierte
Bedienelemente, setzte Textmodus und Textpuffer der Antwort erneut und erzeugte
Antwortmetadaten neu. Auch der Sekunden-Timer lief durch diesen Pfad.

Der eigene Streaming-Listener aktualisiert jetzt nur Antwort und Fortschritt.
Unveränderter Text behält seinen Puffer und seine Auswahl; Modus und Metadaten
werden nur bei Änderungen gesetzt. Der Sekunden-Timer aktualisiert nur den
Status. Scroll-Aufträge werden zusammengefasst. Beide Listener und ausstehende
UI-Aufträge werden beim Stoppen der Activity entfernt; beim Wiederöffnen wird
der aktuelle Zustand vollständig angezeigt.

Parallel lief der gemeinsame 30-fps-Taktgeber für mehrschichtige Lichtkonturen und
große Hintergrundgradienten auch während der Inferenz und Benchmarks. Im Zustand
`GENERATING` plant er jetzt keine dekorativen Animationsframes. Die Arbeitsfarben
bleiben statisch sichtbar; nach Abschluss laufen Idle-/Done-Animationen wieder.
Neue Pop-ups und Fokuswechsel starten während laufender Arbeit keine Animation.

## Reproduzierbare Regressionstests

Die beiden neuen Tests in
[`AppExperienceTest`](../../app/src/test/java/de/bonsai/offline/AppExperienceTest.java)
scheiterten zunächst auf dem bisherigen Render-/Animationsverhalten:

- `streamingUpdatesOnlyChangedAnswerWithoutRebindingOtherControls`: Schon ein
  Update ohne Textänderung ersetzte den Textpuffer. Nach der Korrektur verursachen
  20 unveränderte Updates **0 Textänderungen**. 20 tatsächlich veränderte Antworten
  verursachen genau **20 Textänderungen**, während die Kopfzeile **0** erhält.
  Hintergrund und Wiederaufnahme werden ebenfalls geprüft.
- `decorativeFramesPauseForInferenceAndResumeAfterCompletion`: Der Lichttakt war
  während laufender Arbeit weiter eingeplant. Nach der Korrektur pausieren
  Chatbar und Hintergrund sofort und bleiben auch bei Fokuswechsel pausiert;
  nach Abschluss starten sie wieder. Bestehende Tests prüfen zusätzlich
  Antwortlinie, neue Pop-ups, reduzierte Bewegung und Energiesparmodus.

Lokal erfolgreich: **153 Release-/Robolectric-Tests**, keine Fehler und keine
übersprungenen Tests. Hostprüfungen für Manifest/XML, JNI, Runtime-Backport,
UTF-8, Reasoning und Präfixabgleich sowie Java-Syntax und alle 37 Icon-Paare
bestanden.

```bash
python3 scripts/fetch_runtime.py
./gradlew --max-workers=2 :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease
python3 scripts/check_project.py
java scripts/ParseJava.java
python3 scripts/check_icons.py
```

Für den Gerätevergleich denselben Modell-Hash, Systemprompt, Kontext,
Thread-/Backend-Profil und vergleichbare Starttemperatur verwenden. Der
[Fünf-Turn-Chat-Benchmark](../../benchmark/README.md) erfasst Prefill, TTFT,
Decode, Cache und Thermik getrennt. Hosttests messen keine A55-Inferenzleistung.
