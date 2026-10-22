# Bonsai · Original Icon-Set

18 neu gezeichnete Motive für die bestehende native Java-App. Die 6 vorhandenen `ic_answer_*`-Namen bleiben erhalten. SVG und VectorDrawable verwenden dieselben Pfade.

- [Kontaktbogen, Hell und Dunkel, 16 / 24 / 28 dp (PDF)](Kontaktbogen.pdf)
- [Kontaktbogen hell (native Android-Rasterung)](evidence/contact-light.png) · [dunkel](evidence/contact-dark.png)
- [Vektor-Kontaktbogen hell](contact-light.svg) · [dunkel](contact-dark.svg)
- [Eigenständige Vorschau mit Farbrollen und separat animiertem Test-Container](Vorschau.html)
- [Dateinamen und Funktionen (CSV)](Dateiuebersicht.csv)
- [SVG-Master](svg) · Android XML: `app/src/main/res/drawable/`
- [Prüfprotokoll](QA.md) · [Quellen](SOURCES.md) · [MIT-Lizenz](LICENSE)

## Konstruktion

24 × 24, 1,75 Einheiten Outline, runde Enden und Verbindungen, ungefähr 2 Einheiten Rand. Das kompakte gefüllte Stopquadrat sitzt bewusst innerhalb der Outline-Motivfläche. Offene Symbole und Diagonalen sind optisch ausgerichtet. Keine Hintergründe, Schatten, Farbverläufe, Buchstaben oder eingebetteten Bilder in den Assets. „Information“ verwendet zwei Detailzeilen im Kreis statt eines Buchstabens. Neuer Chat ist eine Chatblase mit Plus; Chatverlauf dieselbe Grundsilhouette mit Textzeilen. Leistung: Balken; Geschwindigkeit: Tacho; Tokenanzahl: Stapel; Zeit: Uhr.

Alle Motive wurden in allen drei Größen geprüft; die 16-dp-Versionen bleiben mit denselben Pfaden lesbar. Daher keine separaten, auseinanderlaufenden Kleinvarianten.

## Android-Einbindung

`BonsaiIcons` lädt und mutiert die VectorDrawables und setzt Tint und Bounds zur Laufzeit. `UiArtwork` delegiert die betreffenden bisherigen Aliasnamen an diesen Loader. Die Activity enthält keine Icon-Pfade oder Canvas-Zeichnungen mehr. `IconButton` verwendet ein ImageButton mit 24-dp-Inhalt und 48-dp-Touchfläche; der vorhandene 36-dp-Sendekreis bleibt ein separates Container-Drawable. Umschalten auf Stop tauscht nur das Asset. Ripple, Fokus, Druckfeedback und deaktivierte Deckkraft bleiben App-Verhalten. Bestehende Accessibility-Beschreibungen, Tooltips und die vollständigen Metadaten bleiben erhalten.

`icon_colors.xml` in `values` und `values-night` definiert ausschließlich Icon-Farbrollen. Sonstige DesignTokens, Texte, Navigation und Funktionen bleiben erhalten. Hauptaktionen: #1B1B20 / #F5F5F7; Metadaten: #62626E / #A6A6B0; ausgewählter Eintrag: #6540B5 / #C4ADFF; auf Action-Buttons: #FFFFFF. XML nutzt neutrales Schwarz als übertönbare Maskenfarbe, ohne Zustandsfarben oder globale festgelegte Tints. SVG nutzt `currentColor`.

Plus ist geliefert und im Loader verfügbar; es wurde kein neuer Plus-Button erfunden. Archiv und Verlauf werden an bestehenden Einträgen gezeigt. Book/Idee sitzen an den vorhandenen Vorschlägen. Die Blatt-/Bonsai-Identität sowie bestehende Schild- und Richtungspfeil-Zusatzsymbole bleiben außerhalb dieses Auftrags.

## Reproduzieren

```sh
./gradlew :app:testDebugUnitTest --tests de.bonsai.offline.IconVisualTest
```

Native PNGs werden nach `app/build/icon-captures` geschrieben. Die Screenshots stammen aus Android-Views und VectorDrawables mit Robolectric Native Graphics auf API 35. `Kontaktbogen.pdf` bildet 1 dp als 0,75 pt ab; bei 100 % drucken, keine Seitenanpassung.

## Datei- und Funktionsübersicht

| Funktion | SVG / XML Basisname | Üblicher Einsatz |
| --- | --- | --- |
| Menü | `ic_menu` | 24 dp |
| Neuer Chat | `ic_new_chat` | 24 dp |
| Plus | `ic_plus` | 24 dp |
| Senden | `ic_send` | 24 dp |
| Stop | `ic_stop` | 24 dp |
| Schließen | `ic_close` | 24 dp |
| Modell | `ic_model` | 24 dp |
| Leistung | `ic_performance` | 24 dp |
| Chatverlauf | `ic_history` | 24 dp |
| Archiv | `ic_archive` | 24 dp |
| Verstehen / Buch | `ic_book` | 24 dp |
| Idee | `ic_idea` | 24 dp |
| Kopieren | `ic_answer_copy` | 28 dp |
| Neu generieren | `ic_answer_retry` | 28 dp |
| Geschwindigkeit | `ic_answer_speed` | 16 dp |
| Tokenanzahl | `ic_answer_count` | 16 dp |
| Zeit | `ic_answer_time` | 16 dp |
| Information | `ic_answer_info` | 16 dp |
