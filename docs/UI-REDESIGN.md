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
| Generating | kräftigeres Cyan / Magenta / Koralle / Amber | laufende Arbeit |
| Done | kurzer grüner / cyanfarbener Saum | erfolgreicher Abschluss |

Done geht nach 1,8 Sekunden in Idle über. Fehler und Abbruch lösen keinen Erfolgssaum aus.
Modell-/Leistungs-Pop-ups teilen den Zustand der Chatbar. Neuer Chat und Import
bekommen schwächere Akzente, damit die Hauptfläche ruhig bleibt.

Die Bildrate ist auf ungefähr 30 fps begrenzt. Pfade, Shader und Matrizen werden
wiederverwendet; der Frame-Loop allokiert keine Zeichnungsobjekte. Animationen
pausieren bei verlorener Fenstersichtbarkeit/-fokussierung, abgehängten Views,
gestoppter Activity, deaktivierten Systemanimationen und Energiesparmodus.
Der Energiezustand wird höchstens einmal pro Sekunde abgefragt. Hinter Sidebar
und Pop-ups pausieren die Effekte des verdeckten Chats.

## Prüfung und Vorschauen

`AppExperienceTest` prüft weiterhin Chat, Seitenleiste, Pop-ups, Draft-Erhalt,
Streaming, Kopieren, Antwortvarianten, Fehler, Theme-Kontraste, 200 % Schrift,
schmale Displays und Tablet-Breite. Zusätzliche Prüfungen decken die drei
Effektzustände, Rückkehr zu Idle, Fehler/Abbruch, verdeckte Ansichten,
Activity-Lebenszyklus, reduzierte Bewegung und Energiesparen ab.

Native Robolectric-Renderaufnahmen; die dargestellten Inhalte und der Modellstatus
sind Testvorgaben und kein Nachweis einer Geräte-Inferenz:

| Chat bereit | Generierung |
|---|---|
| ![Chat bereit](ui/ready-dark.png) | ![Aktiver Lichtsaum](ui/glow-generating-dark.png) |

Weitere Ansichten: [Seitenleiste](ui/sidebar-dark.png), [Modell](ui/model-light.png),
[Leistung](ui/performance-light.png), [Einstieg hell](ui/welcome-light.png),
[große Schrift](ui/large-text-dark.png), [Abschluss](ui/glow-done-dark.png).

Validierung: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
erfolgreich: **132 Tests, 0 Fehler**, ARM64-CPU-Debug-APK und Lint ohne Fehler
(acht bestehende Warnungen zu Konfiguration und deutschen Inline-Texten).
Ein physischer A55-Test für Bildrate, Tastatur, TalkBack und parallele echte
Modellinferenz bleibt erforderlich. Es ist kein Gerät angeschlossen.
