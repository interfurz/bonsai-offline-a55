# Bonsai Offline 0.4.0

## Hintergrundverarbeitung

Die Activity bricht laufende Arbeit beim App-Wechsel nicht mehr ab. Ein nicht exportierter `specialUse`-Vordergrunddienst schützt die vom Nutzer gestartete lokale Verarbeitung. Ein Partial Wake Lock hält die CPU bei ausgeschaltetem Bildschirm aktiv. Das gilt auch für Modellimport/-laden, Modelltest, Optimierer und Benchmark. Der Dienst und die Wachhaltung enden mit der Arbeit, auch nach Abbruch und behandelten Fehlern.

Die laufende Benachrichtigung öffnet die App und bietet **Abbrechen**. Sie zeigt keine Chatinhalte. Ab Android 13 wird einmal um Benachrichtigungsberechtigung gebeten; eine Ablehnung verhindert den Dienst nicht. Die Activity meldet sich im Hintergrund von UI-Updates ab und zeigt beim Wiederöffnen den aktuellen Stand. Antworten werden vom Application-Worker unabhängig von der Activity gespeichert.

Es gibt keine automatische Wiederaufnahme nach Prozessende. Zwangsstopp und Androids Prozessbeendigung bei Speichermangel können auch einen Vordergrunddienst beenden. Ein normaler App-Wechsel ist davon unabhängig.

## Bonsai 4B und Zeichensalat

Die bisherige Referenz benutzte dieselben architekturspezifischen Quantisierungskernel wie der geprüfte CPU-Pfad. Ein gemeinsamer ARM-Kernelfehler konnte damit unbemerkt bleiben.

Die neue Referenz verwendet skalare Dot-Produkte für Q1_0, Q2_0, PQ2_0 und PTQ1_0. Die Runtime bleibt auf Commit `e3de715222b2667c54a267441159a5729f11155e`; Linker-Wrapper wählen zwischen Referenz und Originalkernel. Ein atomarer Prozessschalter erreicht auch die GGML-Workerthreads. Repacking und der alternative llamafile-GEMM-Pfad sind deaktiviert, damit sie die Referenz nicht umgehen. Andere Operationen, einschließlich Hadamard- und Attention-Operationen, bleiben gemeinsame Runtime-Bestandteile. Die Prüfung ist deshalb kein vollständig unabhängiges Modellorakel.

Die gewählte CPU-/Vulkan-Einstellung, Modelle, Chats und Kontext-/Thread-Einstellungen bleiben erhalten; frühere Optimiererprofile werden invalidiert. Abweichende Logits oder ungültige Zahlen führen beim Laden zum skalaren CPU-Rückfall. Der Kompatibilitätsmodus lässt sich außerdem manuell aktivieren und verwendet dann Microbatch 1. Die bereits vorhandene Vergleichstoleranz bleibt unverändert. Der skalare Modus kann deutlich langsamer sein.

Das inzwischen vorliegende A55-Protokoll identifiziert die fehlerhafte Q2_0-Kopie mit SHA256 `4e157769ecbdbdc9f555c58a0834d00bce09430a99efd53a8f3beca642042eff`. Die geprüfte offizielle Q2_0-Datei hat bei derselben Größe dagegen `64a5183b44921bdb5ac4a6f5f92007821d6ebe5c66997d3c147fd214ab9433d4`. Sie beantwortet im Host-Test mit den gemeldeten CPU-Einstellungen „1+1“ und „2+2“ korrekt. Ob die abweichende Kopie aus einem anderen Export stammt oder beschädigt ist, lässt sich allein aus dem Protokoll nicht bestimmen.

Bekannte offizielle Dateinamen (einschließlich üblicher Downloadsuffixe wie „(1)“) sind deshalb an die geprüften SHA256-/Größenwerte gebunden. Der Import hasht während des Kopierens und prüft vor Entladen/Ersetzen des bisherigen Modells. Auch schon importierte Dateien werden vor dem Laden geprüft. Andere GGUF-Modelle werden dadurch nicht pauschal als verifiziert eingestuft oder gesperrt. Die Einstellungen verlinken die geprüfte Q2_0-Datei auf der festen Hugging-Face-Revision `8d6557168168f91ea6697a4a49ed2ef622b53adb`. Das Herunterladen übernimmt der Browser; die App benötigt weiterhin kein INTERNET.

Benchmark-Schema 2 ergänzt SHA256, Metadaten und die erzeugten Antworten pro Messlauf. Die Hashberechnung liegt außerhalb der Zeitmessung. Ältere Benchmarkberichte bleiben lesbar.

Bei früherem Zeichensalat einen **neuen Chat** beginnen, damit beschädigte Antworten nicht erneut als Verlauf in den Prompt gelangen. Unter **Modelltest ohne Chatverlauf** stehen Dateiprüfsumme und frische Testantworten bereit. Ein A55-Hardwaretest bleibt erforderlich; eine Behebung aller Ursachen von Zeichensalat ist noch nicht bewiesen.

## NPU

Ein ausführbarer Exynos-1480-NPU-Pfad steht für diese Bonsai-GGUF-Runtime nicht zur Verfügung. Die Einstellungen zeigen den Status an. Es gibt keinen auswählbaren NPU-Modus, der tatsächlich auf CPU oder GPU weiterrechnet. Quellen und Voraussetzungen: [NPU-STATUS.md](NPU-STATUS.md).

## Bauen und Signieren

Die Änderungen aus dem neueren 0.3.1-Branch sind integriert: Entwürfe bleiben bei Ablehnung erhalten, die Optimierung behält ihre reine Temperaturbeobachtung, und der vollständige CPU/Vulkan-Release-Workflow bleibt erhalten. Paket-ID bleibt `de.bonsai.offline.debug`, Versionscode **7** über der 0.3.1-Version mit Code 6. Für ein Update einer vorhandenen Installation muss mit ihrem bisherigen Schlüssel signiert werden. Ein neuer lokaler Debug-Schlüssel oder ein neuer GitHub-Actions-Runner erzeugt nicht automatisch dieselbe Signatur. Der Release-Workflow baut weiterhin unsigniert für die lokale Signierung.
