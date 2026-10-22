# Icon-QA

Stand: 22.10.2026. Die Icon-Integration basiert auf dem UI-Stand `e607a52d63cd20cc8b8eec2053c2cc49a5cc43d4` aus PR #15. Der Icon-PR darf erst nach dem UI-PR gemergt werden.

## Visuelle Prüfung

Die PNGs in [evidence](evidence) stammen aus den tatsächlichen Android-Views und VectorDrawables, gerendert mit Robolectric Native Graphics (API 35). UI: 412 × 892 dp bei Density 2, 824 × 1784 px. Kontaktbögen: 1 dp = 1 px, je Motiv 16, 24 und 28 dp. Der PDF-Kontaktbogen enthält beide Themes bei 1 dp = 0,75 pt. Die zusätzlichen SVG-Kontaktbögen verwenden die originalen Masterpfade.

Geprüfte Oberflächen in Hell und Dunkel:

- Chatbar: Plus, Senden und Stop; der vorhandene Sendekreis bleibt ein Container.
- Kopfzeile: Menü und neuer Chat.
- Seitenleiste: neuer Chat, Modell, Leistung, Verlauf, aktiver Eintrag, Archiv und Schließen.
- Antwort: Kopieren und Neu generieren (28 dp), Geschwindigkeit, Tokenanzahl und Zeit (16 dp).
- Vorhandene Einstiegsvorschläge: Buch und Idee (24 dp).
- Modell- und Leistungs-Pop-up: Titelmotive und Schließen (24 dp); Metadatenbadge (16 dp).

Die Motive bilden eine gemeinsame Outline-Familie; Details laufen im nativen 16-dp-Raster nicht zu. Der Chip bleibt das komplexeste Motiv, unterscheidet sich aber klar vom Tokenstapel. Plus und diagonales Schließen wirken leicht; Stop bleibt absichtlich kompakt gefüllt. Kopieren hat einen offenen hinteren Rahmen, wodurch die Überlagerung nicht zu schwer wird. Information wird ohne Buchstaben als Detailkreis dargestellt. Keine getrennte Kleinvariante erforderlich.

Die bestehenden echten Java-Lichtsäume aus PR #15 wurden in den nativen Aufnahmen mitgeprüft: ruhiger Saum an Kopfaktion/Pop-up, mehrfarbiger Saum bei laufender Arbeit und Abschlussfarbe. Die Icons haben eigene statische Vektorpfade und enthalten keine Shader, Blur-Filter oder Zustandsfarben. Die HTML-Vorschau besitzt zusätzlich einen klar als synthetisch bezeichneten Container zum Vergleichen der Farbrollen; sie ersetzt die native Prüfung nicht.

## Verhalten und technische Nachweise

- 18 SVG-Master und 18 Android-VectorDrawables: identische Pfaddaten, 24er-Viewport, `currentColor` im SVG, 1,75er-Rundstrich; nur Stop gefüllt.
- Alle Assets lassen sich zur Laufzeit einfärben; Tint und Alpha wurden nativ geprüft.
- Accessibility-Texte, Tooltip-Erklärungen und vollständige Metadatentexte bleiben erhalten.
- Native UI-Tests prüfen mindestens 48 × 48 dp für Send/Stop, Antwortkopie und Schließen in Seitenleiste/Pop-ups. Vorhandene weitere Aktionstests bleiben bestehen.
- Die Activity enthält keine Canvas-Iconzeichnungen mehr. SVG/XML sind saubere Vektoren ohne Raster, Buchstaben, Schatten, Hintergründe oder Verläufe.
- Bestehende Projekt- und UI-Tests sind mit dem neuen Set gelaufen: 135 Tests, 0 Fehler. Abschließender APK-/Lint-Lauf läuft noch; das Ergebnis wird vor Freigabe ergänzt.

## Grenzen

Die Modellzustände und Antworten der Screenshots sind Testdaten; kein Modell wurde für die Aufnahmen geladen. Kein physisches A55 angeschlossen: Paneldarstellung, echte Animation während Inferenz, TalkBack und Touchgefühl auf dem Gerät bleiben zu prüfen. Die Konturen sind in beiden Themes und neben den gerenderten Lichtsäumen lesbar; eine Bildratenmessung ist keine Icon-Prüfung.
