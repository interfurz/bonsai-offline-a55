# UI-Überarbeitung: Burgunder & Pflaume

Ziel: Deutschsprachige Nutzer auf dem Galaxy A55 öffnen ein lokales Modell, stellen eine Frage und lesen die Antwort. Die Umsetzung folgt den [bereitgestellten Richtlinien](UI-UX-GUIDELINES.md) und dem ergänzten Farbwunsch Burgunderrot/Pflaume.

## Design-System

Die native Android-Oberfläche verwendet `DesignTokens.java`. System-Theme und Systemschrift werden respektiert; der Plattformdialog erhält das passende Theme aus `values/styles.xml` beziehungsweise `values-night/styles.xml`.

| Token | Hell | Dunkel |
|---|---|---|
| Hintergrund | `#FAF6F8` | `#1C151E` |
| Fläche | `#F1E8EF` | `#2D2231` |
| Text | `#30202E` | `#F6EDF4` |
| Sekundärtext | `#6D5467` | `#CBB7C8` |
| Akzenttext / Fokus | `#7B2947` | `#E6A7C1` |
| Primäre Aktion | `#7B2947` | `#8F3153` |
| Text auf primärer Aktion | `#FFF4F8` | `#FFF4F8` |

Abstände basieren auf 8 dp mit 4-dp-Zwischenschritten, Schriftgrößen auf 12/14/16/20/24/32 sp. Fließtext verwendet 16 sp und 1,4-fache Zeilenhöhe. Interaktive Ziele sind mindestens 48 dp hoch, Icon-Buttons 48 × 48 dp. Die Inhaltsbreite ist auf Tablets auf 720 dp begrenzt.

## Hauptabläufe

- **Chat:** Beim ersten Start steht der Modellimport im Vordergrund. Nach dem Laden erscheinen zwei Eingabehilfen und die beschriftete Nachrichteneingabe. Während einer Antwort bleibt das nächste Eingabefeld bearbeitbar; Abbrechen ist jederzeit erreichbar. Bei einem fehlgeschlagenen oder abgebrochenen Sendevorgang wird der gesendete Text wieder eingesetzt, sofern noch kein neuer Entwurf vorhanden ist.
- **Modell:** Import, Laden und Einstellungen öffnen sich über „Modell“ in der Seitenleiste als Pop-up. Die primäre Aktion bleibt am unteren Rand erreichbar; die Inhalte darüber scrollen. Labels sind mit den nativen Auswahlfeldern verknüpft.
- **Leistung:** Das Pop-up „Leistung“ erklärt die Messung, zeigt vorhandene echte Ergebnisse und bietet Optimierung, Benchmark und Export an. Ohne geladenes Modell führt die Hauptaktion direkt zum Modell-Pop-up.
- **Verlauf:** Die Liste öffnet sich von links. Ein leerer Verlauf erklärt seine Funktion. Löschen erfordert eine konkret beschriftete Bestätigung.
- **Antwortvarianten:** „Neu generieren“ legt einen eigenen Chat mit dem bisherigen Kontext vor der letzten Frage an. Der Originalchat und seine Antwort bleiben unverändert.

Fehler werden in der Oberfläche mit einem nächsten Schritt sichtbar. Technische Details und Diagnosen bleiben über gezielte Dialoge erreichbar. Es gibt keine erfundenen Messwerte.

## Bewegung

`UiMotion.java` bündelt drei Muster:

1. Gerichtete Navigation und neue Chatansichten: 250 ms, Translation/Deckkraft.
2. Verlauf öffnen/schließen: 250/180 ms, Translation/Deckkraft.
3. Druckfeedback auf Icon-Buttons: 150 ms, Skalierung; Textaktionen verwenden natives Ripple-Feedback.

Bei abgeschalteten Android-Animationen werden Transformationen sofort auf den Endzustand gesetzt. Die dekorative Orbit-Dauerschleife wurde entfernt. Streamender Text wird in derselben TextView aktualisiert, ohne bei jedem Token zu springen oder erneut einzublenden.

Statusmeldungen und Abbrechen erscheinen sofort. Der allgemeine Fortschrittsindikator erscheint nach 180 ms, bei längeren Vorgängen zusätzlich die verstrichene Zeit. Die Optimierung zeigt ihren bekannten Fortschritt. Importmengen werden aus dem bestehenden Importprozess übernommen. Unbekannte Restzeiten werden nicht geschätzt.

## Prüfung

`AppExperienceTest` prüft Seitenleisten-Navigation, Pop-ups, Rücknavigation, erhaltene Entwürfe, Wiederherstellung der Activity, Texteingabe, Fehlerzustand, Streaming ohne Austausch der TextView, Antwortvarianten ohne Datenverlust, Theme-Kontraste und deaktivierte Systemanimationen. Renderaufnahmen entstehen unter `app/build/ui-captures/`.

Getestete Layoutgrößen: 375 × 812 dp (Dunkel, einschließlich 200 % Schrift), 412 × 892 dp (Hell) und 1024 × 900 dp (Tablet). Die kleine Ansicht prüft auch die Erreichbarkeit der Menüpunkte, des Schließen-Buttons und der primären Aktionen.

Die Kontrasttests prüfen Text auf Hintergrund, Fläche und angehobener Fläche sowie Badge-Text und Text auf Hauptaktionen mit mindestens 4,5:1. Kontrollrahmen auf der Fläche erreichen mindestens 3:1.

Die Renderaufnahmen und UI-Tests verwenden Robolectric, keine echte Modellinferenz. Ein physischer A55-Test mit Systemtastatur, TalkBack, Displayaussparung und echter CPU-/Vulkan-Generierung bleibt separat erforderlich. Es wurden keine neuen Inferenz-Backends oder Netzwerkfunktionen ergänzt.

## Verifizierter Stand

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` erfolgreich: **52 Tests, 0 Fehler**, installierbare ARM64-CPU-Debug-APK und Lint ohne Fehler. Sieben Lint-Warnungen betreffen die SDK-abhängige Manifest-Eigenschaft, die bewusst fehlende ChromeOS-ABI sowie noch nicht in Übersetzungsressourcen ausgelagerte deutsche Texte. Die Hostprüfung von Manifest/XML, JNI-Bindung und UTF-8-Streaming ist ebenfalls erfolgreich.

Die lokale Prüfung verwendet JDK 17, Android SDK 35, NDK 28.2.13676358 und CMake 3.22.1. Die APK wurde als CPU-Build erstellt; Vulkan-Laufzeitverhalten wurde nicht getestet.

### Native Vorschauen

Die Screenshots zeigen deterministische UI-Testzustände; insbesondere „Modell bereit“ ist eine Testvorgabe und kein Nachweis für Modellinferenz auf diesem Rechner.

| Einstieg · Hell | Chat bereit · Dunkel |
|---|---|
| ![Einstieg in Burgunder und Pflaume](ui/welcome-light.png) | ![Chat im dunklen Pflaume-Theme](ui/ready-dark.png) |

Weitere Ansichten: [Modell](ui/model-light.png), [Leistung](ui/performance-light.png), [375 dp und 200 % Schrift](ui/large-text-dark.png).


## Detailreichere native Oberfläche

Die Burgunder-/Pflaume-Farbwelt erhält drei zusätzliche Flächentokens (`raised`,
`accentSurface`, `subtleBorder`) für Eingabe, Badges und feine Kartenkonturen.
`UiArtwork.java` zeichnet ein Bonsai-Motiv und ein einheitliches Icon-Set direkt
mit Android Canvas. Es gibt keine Bilddownloads oder zusätzlichen Bibliotheken.
Das dekorative Motiv ist aus dem Accessibility-Baum ausgeschlossen.

- Der Einstieg zeigt das Bonsai-Motiv, einen Datenschutzhinweis und eine
  gegliederte Importkarte. Bei geladenem Modell ist das Motiv kompakter.
- Die zwei Prompt-Vorlagen haben Icons, Kurzbeschreibungen und Richtungspfeile;
  Antippen setzt weiterhin einen bearbeitbaren Entwurf ein.
- Die Seitenleiste kombiniert Icons mit den Textlabels „Modell“ und „Leistung“.
  Fokus- und Druckfeedback bleiben erhalten; der Chat ist die Hauptansicht.
- Modellstatus, aktuelle Thread-/Kontextwerte und Antwort-Einstellungen sind
  visuell gruppiert. Die Leistungsansicht erklärt den Ablauf in drei nummerierten
  Schritten und zeigt weiterhin ausschließlich vorhandene Messwerte.
- Autoren und Antwortaktionen im Chat sowie der Verlauf erhalten passende Icons.

Die Eingabe und Hauptaktionen bleiben außerhalb der scrollenden Inhalte.
Große Systemschrift darf Karten vergrößern; Text wird nicht auf feste Höhen
beschnitten. Die neuen Farbflächen sind in die Kontrastprüfung aufgenommen.
Ein zusätzlicher UI-Test prüft beide Prompt-Vorlagen, den Sendestatus und die
sichtbare Eingabe auf einem 375-dp-Display.

Prüfung dieser Ergänzung: **52 Robolectric-/JUnit-Tests, 0 Fehler, 0 übersprungen**.
Die aktualisierten Bilder unter `docs/ui/` wurden aus diesen Tests übernommen
und visuell kontrolliert. Sie zeigen Testzustände und keine echte Modellinferenz.

Auch `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` ist
erfolgreich: ARM64-CPU-Debug-APK gebaut, Lint ohne Fehler. Die sieben verbleibenden
Warnungen betreffen die SDK-abhängige Manifest-Eigenschaft, die ChromeOS-ABI und
bereits vorhandene deutsche Texte außerhalb der Übersetzungsressourcen.
Geprüft mit JDK 17, SDK 35 und NDK 28.2.13676358. Ein physischer A55-Test und
Vulkan-Laufzeitverhalten wurden nicht geprüft.


## Chat als Hauptansicht, Einstellungen als Pop-ups

Beim normalen Start erscheint ausschließlich der Chat. Die untere Tab-Leiste
entfällt. Das Menü oben links enthält neben den Gesprächen die Einträge „Modell“
und „Leistung“. Sein Inhalt scrollt gemeinsam, damit alle Aktionen auch bei großer
Systemschrift erreichbar bleiben; der Schließen-Button bleibt im Kopfbereich.

Beide Bereiche öffnen sich als native Dialogfenster mit abgerundeter Fläche,
Burgunder-/Pflaume-Tokens, Titel, Icon und Schließen-Button über dem abgedunkelten
Chat. Die Fenster nutzen 88 % der Displayhöhe und höchstens 720 dp Breite. Ihre
Inhalte scrollen; die jeweilige Hauptaktion bleibt unten erreichbar. Schließen ist
über das Kreuz, die Zurück-Taste und Antippen außerhalb möglich.

Die Chatansicht bleibt währenddessen eingehängt. Entwurf, Nachrichten und
Streaming bleiben erhalten. Bei einer Activity-Neuerstellung wird ein geöffnetes
Pop-up wiederhergestellt. Der Wechsel von „Leistung“ zu „Modell“ tauscht den Inhalt
desselben Fensters aus. Ladefortschritt, Abbrechen und Fehlermeldungen sind auch
innerhalb des Pop-ups verfügbar.

Die Dialog-Screenshots werden im Robolectric-Test aus den nativen Zeichenflächen
von Activity und Dialog in den konfigurierten Fenstergrößen zusammengesetzt, da
Robolectric keinen System-Compositor besitzt. Sie sind keine Geräteaufnahmen.


Finale Prüfung der Navigation: **52 Tests bestanden**, darunter Öffnen über die
Seitenleiste, Schließen per Kreuz/Zurück/Außentippen, Entwurferhalt und erneutes
Öffnen nach Activity-Neuerstellung, Statusmeldungen mit Abbrechen sowie 200 %
Schrift und Tablet-Breite. APK-Build und Lint sind ebenfalls erfolgreich
(0 Lint-Fehler, 7 bestehende Warnungen).

Zusätzliche Vorschau: [Seitenleiste im dunklen Theme](ui/sidebar-dark.png).
