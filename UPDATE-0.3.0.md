# Bonsai 0.3.0

Die APK enthält CPU und Vulkan für ARM64. Paket-ID und Signatur bleiben mit 0.2.1 kompatibel. APK über die vorhandene App installieren; nicht deinstallieren. Modelle und Chats bleiben erhalten.

## Neue Oberfläche

Graphitgrüne Flächen, warme Akzente und eine animierte native Orbit-Grafik. Startkarten helfen beim Einstieg; Chat, Leistung und Modell sind direkt erreichbar. Der Verlauf gleitet ein, Bedienelemente reagieren auf Berührung, Antworten lassen sich kopieren. Animationen berücksichtigen die Android-Einstellung für reduzierte Bewegung und pausieren im Hintergrund. Tastatur, Displayaussparung und Systemleisten werden berücksichtigt. Es gibt weiterhin keine Internetberechtigung.

## Kernel Optimizer

Modell laden, unten **Leistung** öffnen und **Dieses Backend optimieren** wählen. Optional vergleicht **CPU & Vulkan vergleichen** beide Backends. Gerät vorher abkühlen lassen; während der Messung bleibt der Bildschirm an. Abbrechen ist jederzeit möglich. Die Messung erzeugt keine Chatnachrichten.

Der native Tuner verwendet die vorhandenen optimierten PrismML/GGML-Kernel. Er misst getrennte Threadzahlen für Decode und Prefill sowie Microbatchgrößen. Er erzeugt keine neuen Shader und verändert weder Modellquantisierung noch F16-KV-Präzision, Kontext oder Sampling. Die Geräteauswahl berücksichtigt integrierte Vulkan-GPUs wie die Xclipse 530.

Jedes Profil bekommt einen Aufwärmlauf und zwei Messungen mit demselben 192-Token-Prompt und 32 fest vorgegebenen Decode-Schritten. Das verhindert Verzerrungen durch unterschiedliche Textlängen oder frühes EOS. Das Ziel ist die geschätzte Zeit für diesen Prompt plus 128 Decode-Tokens. Die gemeldete prozentuale Verbesserung bezieht sich auf diese Testlaufzeit, nicht auf jeden zukünftigen Chat.

Profile müssen endliche Logits und reproduzierbare Top-1-Fingerprints innerhalb desselben Backends liefern. Mehr als 20 % Schwankung zwischen den Messungen verwirft das Profil. Verbesserungen unter 3 % lösen keinen Wechsel aus. Eine Abschlussmessung prüft den Gewinner erneut; mehr als 25 % Verlangsamung verwirft den gesamten Versuch. Diese Prüfungen ersetzen keine umfassende Qualitätsprüfung des Modells.

Bei moderater oder stärkerer Erwärmung wird abgebrochen. Abbruch und abgefangene Fehler stellen das vorige Profil wieder her. Der bisherige Eintrag bleibt bei einem Prozessabbruch erhalten; der nächste Start zeigt einen Hinweis. Native Treiberabstürze und vom System beendete Prozesse können nicht innerhalb des laufenden Prozesses abgefangen werden.

Das Ergebnis liegt lokal in `optimizer.json` und lässt sich im Leistungsbereich exportieren. Modellgröße, Dateiänderungszeit, Kontext, Geräte-Firmware, Runtime und Einstellungen bestimmen, ob ein gespeichertes Profil als passend angezeigt wird. Die jüngste fehlgeschlagene Messung wird getrennt gespeichert. Der bisherige Benchmark bleibt verfügbar.

Das schnellste gemessene Profil ist keine Garantie für die absolute Maximalgeschwindigkeit. Andere Promptlängen, Temperatur, Hintergrundlast und Treiber können das Ergebnis verändern. Ein A55 ist in der Build-Umgebung nicht angeschlossen; der Tuner misst die entscheidenden Werte direkt auf dem Handy.

## Rechenprüfung und Diagnose

Vor dem Einsatz eines Profils berechnet die App eine CPU-Referenz mit Microbatch 1. Optimierte CPU- und Vulkan-Pfade werden mit derselben Eingabe gegen diese Referenz geprüft. Es werden endliche Logits, relative Abweichung von höchstens 2,5 % in den Stichproben und mindestens 80 % gleiche Top-1-Tokens verlangt. Der Tuner muss diese Referenz ebenfalls bestehen. Bei Abweichung schaltet das Laden auf das konservative CPU-Profil zurück. Diese differentielle Prüfung erfasst numerische Backend-Abweichungen; sie beweist keine semantische Antwortqualität und kann keinen in allen Pfaden gemeinsamen Fehler erkennen.

**Modell und Einstellungen → Modelltest ohne Chatverlauf** prüft das geladene Modell mit einer Rechenfrage und einer längeren deutschen Antwort, unabhängig von alten Chatnachrichten. Das kopierbare Protokoll enthält Modellmetadaten, Dateigröße, SHA256, Backend und Parameter. Alte fehlerhafte Antworten können einen fortgesetzten Chat beeinflussen; der unabhängige Test hilft, das von einer kaputten Modelldatei oder fehlerhafter Berechnung zu unterscheiden.
