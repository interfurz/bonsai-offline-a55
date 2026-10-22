# Prüfnachweis 0.3.0

Datum: 18. Oktober 2026.

## Android und Oberfläche

Der ARM64-Build enthält CPU und Vulkan mit dem PrismML-Commit e3de715222b2667c54a267441159a5729f11155e. Verwendet werden SDK 35, NDK 28.2.13676358, CMake 3.22.1 und JDK 17. Die bereitgestellte APK wird mit demselben Debug-Zertifikat wie 0.2.1 signiert; Paket-ID de.bonsai.offline.debug, versionCode 4, versionName 0.3.0.

Vier Robolectric-Tests unter Android API 35 prüfen Navigation und Leistungsbereich, Chats/Entwürfe nach Activity-Neuerstellung, eindeutige Profile/Invalidierung gespeicherter Optimierungen sowie Ablehnung abweichender, unvollständiger und nicht endlicher Rechenergebnisse. Native Android-Ansichten wurden mit Robolectric Native Graphics als PNG gerendert und visuell angesehen. Das ist kein Screenshot eines physischen A55. Die Bilddateien dokumentieren Layouts; Animationsabläufe und Gesten wurden nicht auf einem Gerät geprüft.

Das Projektprüfskript kontrolliert Manifest/XML, JNI-Signaturen, Runtime-Bindung und UTF-8-Schnittgrenzen. Keine Android-Berechtigung wird angefordert, insbesondere keine Internetberechtigung.

## Echte Modellinferenz

Getestet wurde Ternary-Bonsai-2-4B-PQ2_0.gguf, 1.146.465.120 Bytes, SHA256 d406b1e912c0d2a4c124ad10bf38a76ae662fdc4d24b176451403dc7b7f30409.

Unsere JNI-Quelldatei und NativeEngine.java wurden auf dem x86_64-Build-Rechner gegen die offiziellen Linux-Bibliotheken desselben Prism-Releases kompiliert und ausgeführt. Der vollständige zusätzliche Host-Quellbuild wurde zugunsten dieser bereits gebauten, identischen Runtime beendet. Die Android-Bibliothek entsteht weiterhin durch den NDK-Quellbuild.

Bestanden:

- Fehlende Modelldatei und ungültige Microbatchkonfiguration werden abgewiesen.
- Native Kernel-Replays mit 2/4 Threads und Microbatch 32/128 liefern denselben Top-1-Fingerprint.
- Abbruch während Probe/Generierung und anschließender Reset funktionieren.
- 2 + 2 wird mit 4 beantwortet; die Folgefrage +3 mit 7.
- Microbatch 1 und 32 bei 4 Threads und Kontext 4096 liefern denselben Probe-Fingerprint.
- Die längere deutsche Frage zu Bäumen und Wasser ergibt eine lesbare, zusammenhängende Antwort. Das ist ein begrenzter Funktionstest, keine umfassende Bewertung der inhaltlichen Richtigkeit.
- Auch die offizielle llama-cli beantwortet den Rechentest mit derselben Datei korrekt.

## Grenzen und offener Fehler

Ein Galaxy A55 ist nicht angeschlossen. Der gemeldete Zeichensalat auch unter CPU ist auf der Build-CPU bisher nicht reproduziert. ARM-NEON-Kernel, Xclipse-Treiber, tatsächliche App-Modelldatei und ein fortgesetzter Chat mit früheren fehlerhaften Antworten sind deshalb noch nicht als Ursache ausgeschlossen. Eine garantierte Reparatur dieses Gerätefehlers wird nicht behauptet.

Die neue CPU-Referenzprüfung erkennt numerische Abweichungen zwischen den getesteten Rechenpfaden. Sie kann keine in allen Pfaden gemeinsame Fehlerquelle erkennen. Der unabhängige Modelltest in der App erzeugt dazu ein kopierbares Protokoll mit Antworten, Metadaten und SHA256. Auf dem A55 müssen Inferenz, Optimierer-Lauf, Abbruch/Wiederherstellung, Temperaturverhalten und Modelltest praktisch geprüft werden.

Der Autotuner sucht das beste bestandene Profil innerhalb einer begrenzten Menge von Threads und Batchgrößen. Er generiert keine neuen Kernel und beweist keine absolute Maximalgeschwindigkeit. Seine Zeitwerte sind nur für den beschriebenen Replay-Workload unter den jeweiligen Bedingungen gültig.
