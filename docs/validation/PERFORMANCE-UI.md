# Weniger UI-Last mit weiter animierter Beleuchtung

Ausgangsbasis des Zeichenvergleichs: `2dfe2c4` (`main`, Version 0.5.1).
Der aktuelle PR enthält zusätzlich die Dokumentationsänderungen aus `a2118df`.
Die Lichteffekte bleiben während Inferenz, Modellladen und Benchmarks animiert.
Ein physischer A55-Durchlauf liegt noch nicht vor; der tatsächliche Einfluss auf
Tokens/s, Energiebedarf und Thermik ist damit offen.

## Streaming und Status

Text-, Thinking- und Prefill-Callbacks riefen bisher den vollständigen
Activity-Renderpfad auf. Dieser prüfte unter anderem die Modelldatei, aktualisierte
Bedienelemente, setzte Textmodus und Textpuffer der Antwort erneut und erzeugte
Antwortmetadaten neu. Auch der Sekunden-Timer lief durch diesen Pfad.

Der eigene Streaming-Listener aktualisiert nur Antwort und Fortschritt.
Unveränderter Text behält seinen Puffer und seine Auswahl; Modus und Metadaten
werden nur bei Änderungen gesetzt. Der Sekunden-Timer aktualisiert nur den
Status. Scroll-Aufträge werden zusammengefasst. Beide Listener und ausstehende
UI-Aufträge werden beim Stoppen der Activity entfernt; beim Wiederöffnen wird
der aktuelle Zustand vollständig angezeigt.

## Effizientere Lichteffekte

- Drei statt fünf Bloom-Schichten und zwei statt drei Schichten je Highlight
  reduzieren die Kontur im stabilen Generating-Zustand von **18 auf 12
  Effekt-Zeichenaufrufe pro Bild**. Mit Kartenfüllung und Grundkontur sind es
  **14 statt 20**. Die Summe der Alpha-Gewichte bleibt erhalten; die weiche
  Abstufung ist leicht verändert.
- Jedes Highlight wird auf das Quadrat seines Radialverlaufs begrenzt. Außerhalb
  dieses Bereichs ist der Shader transparent; der Zeichenbereich wird dort
  entsprechend beschnitten. Der Umfang wird beim Größenwechsel
  berechnet. Pfade, Shader und Matrizen werden wiederverwendet.
- Ein gemeinsamer `Choreographer`-Callback synchronisiert alle sichtbaren
  Flächen mit der Bildausgabe. Jede Fläche wird nur bei Fälligkeit aktualisiert:
  Arbeitskonturen frühestens nach 42 ms (max. ca. 24 fps), Idle-Konturen nach
  50 ms (20 fps), langsame Hintergrundfelder nach 84 ms (max. ca. 12 fps).
  Kontur-Übergänge und Done verwenden 34 ms (ca. 29 fps). Die tatsächliche
  Bildrate hängt vom Display und der Auslastung ab.
- Farbpaletten, Spotanzahl, Bewegung und 650-ms-Farbwechsel bleiben erhalten.
  Hintergrund, unsichtbare/ersetzte Flächen, verlorener Fokus, deaktivierte
  Systemanimationen und Energiesparmodus pausieren die Animation weiterhin.
  Neue sichtbare Pop-ups animieren auch während der Berechnung.

## Rendervergleich auf dem Host

Verglichen wurde derselbe produktive `onDraw`-Pfad mit der Originalklasse aus
`2dfe2c4`, jeweils auf einer gleich großen nativen Skia-Software-Canvas mit
identischen Phasen und Farben. Nach 24 Aufwärmbildern wurden sieben Messpaare
mit je 24 Phasenschritten erfasst; die Reihenfolge Alt/Neu wechselte je Paar.
Die Tabelle zeigt den Median der Zeit pro Bild im Generating-Zustand.

| Fläche | Größe in Pixeln | Original | Optimiert | Änderung |
| --- | --- | --- | --- | --- |
| Kontur | 824 × 160 | 3,842 ms | 1,536 ms | −60,0 % |
| Antwortlinie | 824 × 480 | 1,120 ms | 0,559 ms | −50,1 % |
| Hintergrund | 824 × 1784 | 5,249 ms | 5,275 ms | +0,5 % |

Der Hintergrund-Zeichenpfad pro Bild ist unverändert; seine Entlastung entsteht
über die niedrigere Aktualisierungsrate. Die Einzelwerte aller Zustände stehen
in [render-cost.json](glow/render-cost.json). Diese Messung enthält keine
Android-GPU, Bildausgabe, Kindansichten, Zustandsüberblendungen oder
Modellinferenz und ist kein A55-Benchmark. Host-Zeitwerte können schwanken.

[Native Vergleichsbilder](glow/comparison.png): links Original, rechts optimiert,
jeweils Idle, Generating und Done bei gleicher Phase. Zusätzlich wurden
Antwortlinie und die bestehenden nativen App-Aufnahmen visuell geprüft.

Reproduzieren, ohne die normalen Testquellen zu verändern:

```bash
python3 scripts/profile_glow.py
```

Der Profilierer erzeugt seine zusätzlichen Testquellen nur unter `build/`.
Messwerte und Einzelbilder liegen anschließend in `app/build/glow-profile/`.
Die verwendete [Probe](glow/GlowRenderProbe.java) ist getrennt von den regulären
Tests und wird nur durch den Profilierbefehl eingebunden.

## Regressionstests und Build

[`AppExperienceTest`](../../app/src/test/java/de/bonsai/offline/AppExperienceTest.java)
prüft die begrenzten Zeichenaufrufe und Highlight-Clips, tatsächliche Bewegung
während der Inferenz, niedrigere Updatefrequenz der Hintergrundfelder, Fokus,
Hintergrund/Wiederaufnahme, neue Pop-ups sowie reduzierte Bewegung und
Energiesparmodus. 20 unveränderte Streaming-Updates verursachen weiterhin
**0 Textänderungen**; 20 geänderte Antworten verursachen genau **20** und
**0 Änderungen der Kopfzeile**.

Lokal bestanden: **154 Release-/Robolectric-Tests**, keine Fehler und keine
übersprungenen Tests. Der vollständige ARM64-CPU-Release-Build ist erfolgreich;
Release-Lint meldet **0 Fehler und 8 Warnungen**. Manifest/XML, JNI,
Runtime-Backport, UTF-8, Reasoning, Präfixabgleich, Java-Syntax und alle 37
Icon-Paare sind geprüft. Die APK ist unsigniert; ein neuer Vulkan-Build wurde
nicht durchgeführt.

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
