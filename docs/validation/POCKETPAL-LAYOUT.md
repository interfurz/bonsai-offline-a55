# PocketPal-Layout und Liquid-Interaktionen

Stand: 25. Oktober 2026. Native Java-Android-Oberfläche.

## Umfang

Referenz: [PocketPal AI Chat](https://github.com/a-ghorbani/pocketpal-ai/blob/main/assets/images%20and%20logos/Chat.png) und [ChatInput-Anordnung](https://github.com/a-ghorbani/pocketpal-ai/blob/main/src/components/ChatInput/styles.ts).

Kompakte links ausgerichtete Kopfzeile, rechts stehende Nutzerblasen mit inhaltsabhängiger Breite, offene Antwortfläche und breiter zweizeiliger Composer. Die vorhandenen Text-/Icon-Aktionen wurden umgeordnet; der Vergleich der MainActivity vor/nach der Änderung enthält dieselben sechs benannten Icon-Aktionen und dieselben 28 Klick-Handler. Keine neue PocketPal-Funktion oder Schaltfläche. Modell-/Leistungspopups, Offline-Wissenspakete, Archiv, lokale Tools und Inferenz bleiben erhalten.

Die Chatbar gibt bei Touch nach und federt beim Loslassen zurück. Eingabefokus hebt sie leicht an. Bestehende Aktionen erhalten Press-Feedback; Panels öffnen weich. Die Seitenleiste überschreitet beim Eintritt ihren Rand nicht. Animationen überspringen deaktivierte Systemanimationen und Energiesparen; das Streaming löst keine Bewegung aus.

## Prüfungen

- Vollständige JVM-/Robolectric-Suite: 177 Tests, 0 Fehler, 2 übersprungen. Davon 2 neue LiquidComposer-Tests für durchgereichte Klicks, Touch-Abbruch und reduzierte Bewegung.
- Zusätzlicher Durchlauf der 3 Antwort-Footer-Szenarien und 2 LiquidComposer-Tests: 5 Tests, 0 Fehler. Neu-Chat bleibt im sichtbaren Bereich; Kopieren und Regenerieren behalten ihre Funktion.
- Native Robolectric-Aufnahmen: hell/dunkel, 375-dp-Display mit 200 % Schrift und Tablet; Chat, Menü, Modell, Leistung und Bereitschaftszustände visuell geprüft. Die Bilder zeigen vorgegebene Testdaten, keine echte Modellinferenz.
- Projektprüfung (`python3 scripts/check_project.py`) und Java-Syntaxprüfung erfolgreich.
- ARM64-CPU-Debug-APK (`:app:assembleDebug`) erfolgreich gebaut.
- Lint: 0 Fehler; bestehende Warnungen betreffen Plattform-/ABI-Konfiguration und deutsche Inline-Texte.

## Vorschauen

- [Chat dunkel](../ui/answer-icons-dark.png)
- [Chat bei großer Schrift](../ui/answer-icons-large-text.png)
- [Einstieg hell](../ui/welcome-light.png)
- [Schmales Display, 200 % Schrift](../ui/large-text-dark.png)
- [Seitenleiste](../ui/sidebar-dark.png)

## Grenzen

Kein physisches A55 angeschlossen. Haptik, Tastaturübergang, tatsächliches Bewegungsgefühl und Bildrate unter echter Modelllast sind auf dem Gerät nachzuprüfen. Der APK-Build benötigt wie dokumentiert zuerst `python3 scripts/fetch_runtime.py`, um den bereits vorhandenen und per Hash geprüften EmbeddingGemma-Backport im lokalen Runtime-Checkout einzurichten.
