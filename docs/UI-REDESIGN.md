# Native Oberfläche: ruhige Flächen, lebendiger Lichtsaum

Bonsai bleibt eine vollständig offline arbeitende Android-App. Die Kernaufgabe:
ein lokales GGUF-Modell laden, eine Frage stellen und die gestreamte Antwort lesen.
Das Redesign orientiert sich an den bereitgestellten mobilen Chat-/Sidebar-Screenshots.
Ein Chat/Work-Umschalter ist ausdrücklich nicht vorgesehen.

## Aufbau

- Zentrierter Bonsai-Titel, runder Menüknopf links und neuer Chat rechts.
- Viel freier Raum; bei geladenem Modell stehen zwei bearbeitbare Prompt-Einstiege
  direkt über der Chatbar. Das bisherige große Bonsai-Motiv und die Vorschlagskarten entfallen.
- Die Chatbar ist auch vor dem Modellimport sichtbar. Entwürfe lassen sich vorbereiten;
  Senden bleibt ohne geladenes Modell gesperrt. Das Plus öffnet Modell und Einstellungen.
- Die Seitenleiste enthält Modell, Leistung, Chats und Archiv. Ein neuer Chat ist
  am unteren Rand erreichbar. Löschen erscheint nur bei vorhandenem aktuellem Chat.
- Modell und Leistung öffnen weiterhin native Pop-ups. Chat, Entwurf, lokale Tools,
  SQLite-Verlauf, Antwortvarianten und laufende Inferenz behalten ihre Abläufe.
- Touch-Ziele bleiben mindestens 48 dp groß. Eingabe und Texte können wachsen;
  Inhaltsbreite auf Tablets höchstens 720 dp. Tastatur- und Systemleisten-Inset bleiben erhalten.

## Farben

Die App folgt dem System-Theme; `DesignTokens` und beide Android-Styles stimmen überein.

| Token | Dunkel | Hell |
|---|---|---|
| Hintergrund | `#050506` | `#FAFAFA` |
| Fläche | `#161618` | `#F0F0F3` |
| Eingabe | `#202023` | `#FFFFFF` |
| Text | `#F5F5F7` | `#1B1B20` |
| Sekundärtext | `#A6A6B0` | `#62626E` |
| Akzenttext / Fokus | `#C4ADFF` | `#6540B5` |
| Primäre Aktion | `#7545D6` | `#7545D6` |

Text-, Fokus- und Kontrollkontraste sind unabhängig vom dekorativen Lichtsaum.
Das Eingabefeld hat einen dauerhaften Accessibility-Namen. Statusmeldungen und
Abbrechen bleiben zusätzlich zum Farbwechsel sichtbar.

## PulsingBorder in Java

`PulsingBorder` ist eine native `FrameLayout`-Komponente mit Canvas, `SweepGradient`,
`RadialGradient` und einem vermessenen abgerundeten Pfad. Sie interpretiert den
bereitgestellten Paper-Shaders-Effekt in Java; sie ist kein identischer Port des
zugrunde liegenden WebGL-Shaders. Keine npm-Abhängigkeit, kein WebView, keine neue
Berechtigung und keine Änderung an der C++-/Vulkan-Modellruntime.

Die drei Presets übernehmen die gelieferten Werte für speed, intensity, bloom,
pulse, smoke, spots, spotSize und colors. Umlaufende Farbsäume, weiche Lichtlagen
und wandernde lokale Highlights erzeugen den Effekt. Farbwechsel werden über
650 ms überblendet, ohne Text oder Layout zu bewegen.

| Zustand | Darstellung | Auslöser |
|---|---|---|
| Idle | langsames Blau / Violett / Cyan | bereit oder Entwurf |
| Generating | statisches Cyan / Magenta / Koralle / Amber | laufende Arbeit |
| Done | kurzer grüner / cyanfarbener Saum | erfolgreicher Abschluss |

Done geht nach 1,8 Sekunden in Idle über. Fehler und Abbruch lösen keinen Erfolgssaum aus.
Die Beleuchtung ist in der gesamten App verteilt und teilt denselben Zustand:

- **Bildschirmränder:** Zwei weiche, langsam wandernde Farbfelder. Die Mitte der
  Lesefläche bleibt dunkel; im hellen Theme ist die Deckkraft zusätzlich reduziert.
- **Neueste Antwort:** Eine schmale Lichtlinie am linken Rand; ältere Antworten
  und der Text selbst bleiben ruhig. Das Streaming verwendet dieselbe TextView.
- **Modell und Leistung:** Sanfte Konturen um die Status-/Ergebniskarte und die
  jeweilige Hauptaktion, zusätzlich zu einem dezenten Lichtfeld im Pop-up.
- **Seitenleiste:** Eigenes Lichtfeld, zurückhaltend beleuchteter aktueller Chat
  und die Aktion für einen neuen Chat.
- **Chatbar:** Der stärkste Randakzent. Die übrigen Konturen verwenden nur
  25–60 % ihrer Stärke; Formulare und normale Menüeinträge haben keine Dauerkontur.

`PulsingBorder` bietet die Modi BORDER, AMBIENT und RAIL. Ein gemeinsamer
30-fps-Callback versorgt die sichtbaren Effekte im Leerlauf und nach Abschluss.
Während laufender Berechnungen pausiert er vollständig; die Arbeitsfarben bleiben
sichtbar. So konkurrieren dekorative CPU-/GPU-Zeichnungen nicht mit Inferenz,
Modellladen oder Benchmarks. Die Activity verwaltet getrennte
Gruppen für Hauptansicht, Chatinhalt, Pop-up und Seitenleiste. Ersetzte Inhalte
werden aus dem Taktgeber und der Zustandsverteilung entfernt. Je Hauptansicht
animieren höchstens vier Lichtflächen gleichzeitig.

Die Bildrate ist auf ungefähr 30 fps begrenzt. Pfade, Shader und Matrizen werden
wiederverwendet; der Frame-Loop allokiert keine Zeichnungsobjekte. Animationen
pausieren bei verlorener Fenstersichtbarkeit/-fokussierung, abgehängten Views,
gestoppter Activity, deaktivierten Systemanimationen und Energiesparmodus.
Der Energiezustand wird höchstens einmal pro Sekunde abgefragt. Hinter Sidebar
und Pop-ups pausieren die Effekte des verdeckten Chats.

Text-, Thinking- und Prefill-Updates aktualisieren nur die laufende Antwort und
Fortschrittsanzeigen. Kopfzeile, Modell-/Leistungskarten und Sendeaktionen werden
bei Zustandsänderungen aktualisiert. Unveränderter Antworttext behält seinen
Textpuffer und seine Auswahl; Textmodus und Metadaten werden nur bei Änderungen
neu gesetzt. Der Sekunden-Timer aktualisiert nur den Status. Scroll-Aufträge
werden zusammengefasst und beim Stoppen der Activity entfernt.

## Prüfung und Vorschauen

`AppExperienceTest` prüft weiterhin Chat, Seitenleiste, Pop-ups, Draft-Erhalt,
Streaming, Kopieren, Antwortvarianten, Fehler, Theme-Kontraste, 200 % Schrift,
schmale Displays und Tablet-Breite. Zusätzliche Prüfungen decken die drei
Effektzustände, Rückkehr zu Idle, Fehler/Abbruch, verdeckte Ansichten,
Activity-Lebenszyklus, reduzierte Bewegung und Energiesparen ab. Die Erweiterung
prüft außerdem gemeinsame Effektzustände über Chat und Pop-ups, das Pausieren
verdeckter Flächen, das Freigeben ersetzter Karten und die Navigation des
beleuchteten aktuellen Chats.

Native Robolectric-Renderaufnahmen; die dargestellten Inhalte und der Modellstatus
sind Testvorgaben und kein Nachweis einer Geräte-Inferenz:

| Chat bereit | Generierung |
|---|---|
| ![Chat bereit](ui/ready-dark.png) | ![Aktiver Lichtsaum](ui/glow-generating-dark.png) |

Weitere Ansichten: [Seitenleiste](ui/sidebar-dark.png), [Modell](ui/model-light.png),
[Leistung](ui/performance-light.png), [Einstieg hell](ui/welcome-light.png),
[große Schrift](ui/large-text-dark.png), [Abschluss](ui/glow-done-dark.png),
[Modell mit dynamischem Licht](ui/app-light-model-dark.png),
[laufende Leistungsmessung](ui/app-light-performance-dark.png) und
[aktueller Chat in der Seitenleiste](ui/app-light-sidebar-dark.png).

Validierung: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
erfolgreich: **135 Tests, 0 Fehler**, ARM64-CPU-Debug-APK und Lint ohne Fehler
(acht bestehende Warnungen zu Konfiguration und deutschen Inline-Texten).
Ein physischer A55-Test für Bildrate, Tastatur, TalkBack und parallele echte
Modellinferenz bleibt erforderlich. Es ist kein Gerät angeschlossen.
