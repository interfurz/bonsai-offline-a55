# Icon-QA

Stand: 22.10.2026. Die Icon-Integration basiert auf dem gemergten UI-Stand `06ed47377990f3d1fd21f24388c3aad664d555e7` aus PR #15. Der Icon-Merge folgt erst nach diesem UI-Merge.

## Visuelle Prüfung

Die PNGs in [evidence](evidence) stammen aus den tatsächlichen Android-Views und VectorDrawables, gerendert mit Robolectric Native Graphics (API 35). UI: 412 × 892 dp bei Density 2, 824 × 1784 px. Kontaktbögen: 1 dp = 1 px, je Motiv 16, 24 und 28 dp. Der PDF-Kontaktbogen enthält beide Themes bei 1 dp = 0,75 pt. Die zusätzlichen SVG-Kontaktbögen verwenden die originalen Masterpfade.

Geprüfte Oberflächen in Hell und Dunkel:

- Chatbar: Plus, Senden und Stop; der vorhandene Sendekreis bleibt ein Container.
- Kopfzeile: Menü und neuer Chat.
- Seitenleiste: neuer Chat, Modell, Leistung, Verlauf, aktiver Eintrag, Archiv und Schließen.
- Antwort: Kopieren und Neu generieren (28 dp), Geschwindigkeit, Tokenanzahl und Zeit (16 dp).
- Vorhandene Einstiegsvorschläge: Buch und Idee (24 dp).
- Modell- und Leistungs-Pop-up: Titelmotive und Schließen (24 dp); Metadatenbadge (16 dp).

Die Motive bilden eine gemeinsame Outline-Familie. Nach dem Größenreview erhielten alle 18 Motive eigene `_16`-Varianten: 2,25 Einheiten Strich im 24er-Viewport ergeben bei 16 dp echte 1,5 dp. Der native Rastertest misst für die drei Menülinien zusammen 4,5 Pixel Deckungsfläche an einer senkrechten Probe; einfaches Skalieren des 1,75er-Masters würde nur etwa 3,5 ergeben. In den tatsächlichen Metadaten wird außerdem geprüft, dass die geladenen Drawables eine intrinsische Größe von 16 dp besitzen.

Beim Chip werden die acht Pins auf vier reduziert. Die anderen 16er behalten ihre Silhouette mit angepasstem Strich. Menü hat drei gleich lange Linien; Tokenanzahl eine Raute; Verlauf eine Uhr mit Rückpfeil; neuer Chat eine Plus-Blase. Damit sind die Motive auch bei 16 dp unterscheidbar. Information verwendet auf ausdrücklichen Änderungswunsch ein kleines geometrisches i im Kreis, Leistung einen kleinen Outline-Blitz. Kopieren und Neu generieren bleiben als vorhandene Hauptaktionen bei 28 dp, damit ihre Hierarchie zu den 16-dp-Metadaten erhalten bleibt. Alle Trefferflächen bleiben unabhängig davon bei mindestens 48 dp.

Die bestehenden echten Java-Lichtsäume aus PR #15 wurden in den nativen Aufnahmen mitgeprüft: ruhiger Saum an Kopfaktion/Pop-up, mehrfarbiger Saum bei laufender Arbeit und Abschlussfarbe. Die Icons haben eigene statische Vektorpfade und enthalten keine Shader, Blur-Filter oder Zustandsfarben. Die HTML-Vorschau besitzt zusätzlich einen klar als synthetisch bezeichneten Container zum Vergleichen der Farbrollen; sie ersetzt die native Prüfung nicht.

## Verhalten und technische Nachweise

- 18 Standard-Master, 18 optische `_16`-Varianten und ein bestehender dekorativer Nachrichtenmarker, jeweils als SVG/XML-Paar: identische Pfaddaten, 24er-Viewport, `currentColor` nur im SVG; Standardstrich 1,75, Kleinstrich 2,25; nur Stop gefüllt. Android nutzt Java-`setTint()` mit den Theme-Farbrollen.
- Alle Assets lassen sich zur Laufzeit einfärben; Tint und Alpha wurden nativ geprüft.
- Accessibility-Texte, Tooltip-Erklärungen und vollständige Metadatentexte bleiben erhalten.
- Native UI-Tests prüfen mindestens 48 × 48 dp für Send/Stop, Antwortkopie und Schließen in Seitenleiste/Pop-ups. Vorhandene weitere Aktionstests bleiben bestehen.
- Die Activity enthält keine Canvas-Iconzeichnungen mehr. SVG/XML sind saubere Vektoren ohne Raster, Schatten, Hintergründe oder Verläufe; das Info-i ist die ausdrücklich gewünschte Ausnahme von der ursprünglichen Buchstabenregel.
- Abschließender Lauf mit optischen Kleinvarianten, Tokenraute, Verlauf und Menü: `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` erfolgreich, 138 Tests, 0 Fehler und 0 übersprungen; ARM64-Debug-APK gebaut; Lint ohne Fehler, acht bestehende Warnungen. Maschinenlesbare Zusammenfassung und Asset-Prüfsummen: [validation.json](validation.json).

## Grenzen

Die Modellzustände und Antworten der Screenshots sind Testdaten; kein Modell wurde für die Aufnahmen geladen. Kein physisches A55 angeschlossen: Paneldarstellung, echte Animation während Inferenz, TalkBack und Touchgefühl auf dem Gerät bleiben zu prüfen. Die Konturen sind in beiden Themes und neben den gerenderten Lichtsäumen lesbar; eine Bildratenmessung ist keine Icon-Prüfung.
