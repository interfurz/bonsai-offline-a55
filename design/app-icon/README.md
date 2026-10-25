# Bonsai · App-Icon

Ein reduzierter Bonsai mit drei Kronen, geschwungenem Stamm und flacher Schale.
Die Farben übernehmen die bestehende Oberfläche: `#161618` als Hintergrund,
`#7545D6` und `#C4ADFF` als violette Akzente. Die ruhigen Flächen und weichen
Konturen passen zu den runden Aktionen und dezenten Lichtsäumen der Chatansicht.

- `concept.png`: generierter visueller Entwurf, auf Basis von
  `docs/ui/ready-dark.png` und `docs/ui/welcome-light.png`.
- `launcher.svg` / `launcher.png`: skalierbarer Master und 1024-px-Export der
  sichtbaren Iconfläche. Keine eingebauten runden Ecken; der Launcher maskiert.
- `preview.svg` / `preview.png`: runde und abgerundete Maske, beispielhafte
  Android-Designfarbe sowie die tatsächliche 48-px-Ansicht.
- `foreground.svg`: transparente vollständige adaptive 108-dp-Ebene.
- `monochrome.svg`: identische Silhouette als Alphamaske.
- `generate.py`: gemeinsame Pfade und Farbverläufe für SVG und Android XML.

## Android

Das Manifest verweist für `icon` und `roundIcon` auf `@mipmap/ic_launcher`.
Alle unterstützten Android-Versionen (minSdk 28) erhalten ein adaptives Icon aus
einer vollflächigen Hintergrundfarbe und
einer separaten Vektorebene. Das Motiv liegt vollständig im zentralen sicheren
Bereich des 108-dp-Viewports; Kreis- und abgerundete Masken schneiden es nicht ab.
Android 13+ erhält zusätzlich die `monochrome`-Ebene für systemgefärbte Icons.
Der Launcher entscheidet, ob und mit welchen Farben er diese darstellt.
Ältere Android-Versionen ignorieren die zusätzliche monochrome Ebene.

Der Vordergrunddienst verwendet die separate transparente weiße Silhouette
`ic_notification_bonsai`, damit Android die Statusleistenfarbe korrekt anwendet.
Das Launcher-Icon und seine Verläufe werden nicht als Benachrichtigungsmaske verwendet.

## Reproduzieren

```sh
python3 design/app-icon/generate.py
# Optional: PNG-Vorschauen mit CairoSVG erzeugen.
cairosvg design/app-icon/launcher.svg -o design/app-icon/launcher.png
cairosvg design/app-icon/preview.svg -o design/app-icon/preview.png
./gradlew :app:processDebugResources :app:lintDebug
```

Die SVG-Master und Android-Ressourcen benötigen keine externe Grafikbibliothek.
Die App lädt keine Rasterdateien und keine neuen Laufzeitabhängigkeiten.

## Prüfung

Ressourcenverknüpfung, Java-Kompilierung und Android-Lint wurden erfolgreich
ausgeführt. Die Silhouette liegt einschließlich Antialiasing innerhalb der
33-dp-Sicherheitszone (gemessener Außenradius: 32,28 dp). Die Vorschau zeigt
Kreis, abgerundetes Quadrat, Designfarbe und 48-px-Darstellung.

Ein vollständiger APK-Bau war in diesem Checkout durch den fehlenden
EmbeddingGemma-2-Backport in `vendor/llama.cpp` blockiert. Die Runtime wurde
für die Iconänderung nicht verändert. Ein physischer Launcher-Test steht aus.
