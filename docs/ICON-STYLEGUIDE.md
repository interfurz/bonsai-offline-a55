# Briefing für den Icon-Agenten — Bonsai

## Auftrag und Grenzen

Entwickle ausschließlich ein konsistentes Icon-Set für die bestehende native
Java-Android-App `interfurz/bonsai-offline-a55`. Orientiere dich am aktuellen
minimalistischen Chat-UI. Ändere weder Layout noch Texte, Navigation oder
Funktionen. Das Bonsai-Logo benötigt einen gesonderten Auftrag.

## Formensprache

- Ruhig, klar, geometrisch und leicht gerundet; monochrome Outline-Icons.
- Wenige Linien und sofort erkennbare Silhouetten. Keine Mikrodetails oder Schrift.
- Einheitliche Rundungen, offene Räume und optisches Gewicht über alle Motive.
- Keine Emojis, 3D-Effekte, Schlagschatten, Glows, Farbverläufe oder Hintergründe.
- Effekte gehören zu den UI-Containern; Icons bleiben scharf und stabil.

## Raster und Größen

- Master: 24 × 24 Einheiten; reguläre Motivfläche etwa 20 × 20, rundum 2 Einheiten frei.
- Grundstrich: 1,75 Einheiten; runde Linienenden und Linienverbindungen.
- Standard-Icons: 24 dp. Metadaten: 16 dp. Antwortaktionen derzeit: 28 dp.
- Jedes Motiv bei 16, 24 und 28 dp prüfen; kleine Varianten optisch vereinfachen,
  wenn Details sonst zulaufen. Unterschiedliche Motive müssen gleich schwer wirken.
- Optisch zentrieren. Kreis, Quadrat und diagonale Motive brauchen gegebenenfalls
  kleine Größenkorrekturen; nicht nur die mathematischen Bounds vergleichen.
- Touch-Flächen bleiben mindestens 48 × 48 dp und gehören nicht zum Icon-Asset.
- Ausnahme: Stop darf eine kleine gefüllte Form mit sanft gerundeten Ecken sein.

## Farbe und Zustände

| Verwendung | Dunkel | Hell |
|---|---|---|
| Hauptaktionen | #F5F5F7 | #1B1B20 |
| Sekundär / Metadaten | #A6A6B0 | #62626E |
| Ausgewählter Akzent | #C4ADFF | #6540B5 |
| Auf violetter Primäraktion | #FFFFFF | #FFFFFF |

SVG-Master verwenden `currentColor`; Android-Assets sind zur Laufzeit einfärbbar.
Farben und reduzierte Deckkraft für deaktivierte Zustände nicht in Pfade einbacken.
Hover, Fokus, Ripple, Pressfeedback und animierte Ränder bleiben Aufgabe des UI.
Der Lichtsaum darf die Lesbarkeit der Icons nicht beeinflussen.

## Benötigte Funktionen

Menü, neuer Chat, Plus, Senden, Stop, Schließen, Modell, Leistung, Chatverlauf,
Archiv, Verstehen/Buch, Idee, Kopieren, Neu generieren, Geschwindigkeit,
Tokenanzahl, Zeit und Information. Gleiche Funktionen verwenden überall dasselbe
Motiv. Keine zusätzlichen Funktionen wie Spracheingabe oder Bildgenerierung.

Bestehende Integrationsstellen: `UiArtwork.java`, `MainActivity.IconButton` und
`app/src/main/res/drawable/ic_answer_*.xml`. Bestehende Ressourcen-Namen und
Accessibility-Beschriftungen möglichst beibehalten. Keine zusätzlichen Motive
mit Canvas-Code direkt in der Activity zeichnen.

## Lieferung und Abnahme

1. Eine zusammenhängende Icon-Familie; keine Mischung verschiedener Bibliotheken.
2. Pro Motiv: sauberer SVG-Master und Android VectorDrawable XML.
3. Kontaktbogen auf hellem und dunklem Hintergrund mit Varianten bei 16/24/28 dp.
4. Übersicht: Dateiname, Funktion, Standardgröße und eventuelle optische Korrektur.
5. Bei Bibliotheksgrundlagen: Quelle, Lizenz und Attribution mitliefern.
6. Icons im echten Chat, in der Chatbar, der Seitenleiste und den Pop-ups prüfen:
   Erkennbarkeit, gleiches Gewicht, Zentrierung, Kontrast und Touch-Flächen.

Liefere Vorschau und Assets gemeinsam. Beschränke Änderungen auf Icons und ihre
Einbindung; Modellruntime, Datenbank und Inferenz bleiben außerhalb des Auftrags.
