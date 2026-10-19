# Bonsai Offline 0.4.0

## Hintergrundverarbeitung

Die Activity bricht laufende Arbeit beim App-Wechsel nicht mehr ab. Ein nicht exportierter `specialUse`-Vordergrunddienst schützt die vom Nutzer gestartete lokale Verarbeitung. Ein Partial Wake Lock hält die CPU bei ausgeschaltetem Bildschirm aktiv. Das gilt auch für Modellimport/-laden, Modelltest, Optimierer und Benchmark. Der Dienst und die Wachhaltung enden mit der Arbeit, auch nach Abbruch und behandelten Fehlern.

Die laufende Benachrichtigung öffnet die App und bietet **Abbrechen**. Sie zeigt keine Chatinhalte. Ab Android 13 wird einmal um Benachrichtigungsberechtigung gebeten; eine Ablehnung verhindert den Dienst nicht. Die Activity meldet sich im Hintergrund von UI-Updates ab und zeigt beim Wiederöffnen den aktuellen Stand. Antworten werden vom Application-Worker unabhängig von der Activity gespeichert.

Es gibt keine automatische Wiederaufnahme nach Prozessende. Zwangsstopp und Androids Prozessbeendigung bei Speichermangel können auch einen Vordergrunddienst beenden. Ein normaler App-Wechsel ist davon unabhängig.

## Bonsai 4B und Zeichensalat

Die bisherige Referenz benutzte dieselben architekturspezifischen Quantisierungskernel wie der geprüfte CPU-Pfad. Ein gemeinsamer ARM-Kernelfehler konnte damit unbemerkt bleiben.

Die neue Referenz verwendet skalare Dot-Produkte für Q1_0, Q2_0, PQ2_0 und PTQ1_0. Die Runtime bleibt auf Commit `e3de715222b2667c54a267441159a5729f11155e`; Linker-Wrapper wählen zwischen Referenz und Originalkernel. Ein atomarer Prozessschalter erreicht auch die GGML-Workerthreads. Repacking und der alternative llamafile-GEMM-Pfad sind deaktiviert, damit sie die Referenz nicht umgehen. Andere Operationen, einschließlich Hadamard- und Attention-Operationen, bleiben gemeinsame Runtime-Bestandteile. Die Prüfung ist deshalb kein vollständig unabhängiges Modellorakel.

Beim ersten Start dieser Version wird CPU mit Kompatibilitätsmodus und Microbatch 1 aktiviert. Modelle, Chats und Kontext-/Thread-Einstellungen bleiben erhalten; frühere Optimiererprofile werden invalidiert. Optimierte CPU- oder Vulkan-Ausführung lässt sich danach in den Einstellungen aktivieren. Abweichende Logits oder ungültige Zahlen führen beim Laden zum skalaren CPU-Rückfall. Die bereits vorhandene Vergleichstoleranz bleibt unverändert. Der skalare Modus kann deutlich langsamer sein.

Bei früherem Zeichensalat einen **neuen Chat** beginnen, damit beschädigte Antworten nicht erneut als Verlauf in den Prompt gelangen. Unter **Modelltest ohne Chatverlauf** stehen Dateiprüfsumme und frische Testantworten bereit. Ein A55-Hardwaretest bleibt erforderlich; eine Behebung aller Ursachen von Zeichensalat ist noch nicht bewiesen.

## NPU

Ein ausführbarer Exynos-1480-NPU-Pfad steht für diese Bonsai-GGUF-Runtime nicht zur Verfügung. Die Einstellungen zeigen den Status an. Es gibt keinen auswählbaren NPU-Modus, der tatsächlich auf CPU oder GPU weiterrechnet. Quellen und Voraussetzungen: [NPU-STATUS.md](NPU-STATUS.md).

## Bauen und Signieren

Paket-ID bleibt `de.bonsai.offline.debug`, Versionscode 5. Für ein Update einer vorhandenen Installation muss mit ihrem bisherigen Schlüssel signiert werden. Ein neuer lokaler Debug-Schlüssel oder ein neuer GitHub-Actions-Runner erzeugt nicht automatisch dieselbe Signatur. Eine vorhandene Installation nicht zur Umgehung eines Signaturfehlers löschen: Dabei würden private Modelle und Chats verloren gehen.
