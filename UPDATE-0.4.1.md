# Bonsai Offline 0.4.1

## Prüfsumme mit Fortfahren-Option

Bei einer Abweichung von einer bekannten Prüfsumme bietet das Popup **Trotzdem fortfahren** und **Abbrechen**. Das gilt beim Import und beim Laden einer bereits importierten Datei. Eine andere Prüfsumme wird nicht mehr als eindeutiger Beweis für eine beschädigte Datei dargestellt.

Beim Import bleibt die vollständig kopierte Datei bis zur Entscheidung vorgemerkt, während die bisherige Modellkopie und Engine erhalten bleiben. Fortfahren prüft den Hash erneut und übernimmt genau diese Datei. Die Freigabe wird für Dateihash, Größe und die bekannte Vergleichsfassung gespeichert; spätere Ladevorgänge derselben Datei fragen nicht erneut. Andere Bytes benötigen eine eigene Entscheidung. Abbrechen, Zurück oder Tippen außerhalb des Dialogs verwirft die vorgemerkte Kopie. Ein Activity-Neustart erhält die offene Entscheidung; ein Prozessneustart räumt den unvollendeten Import auf.

## Offener GGUF-Import

Importdialog, Speicherhinweis, Standarddateiname und Einstellungen sind allgemein für GGUF-Modelle formuliert. Das Bonsai-2-4B-Modell ist ein optionales Beispiel. Andere GGUF-Dateinamen benötigen keine bekannte Bonsai-Prüfsumme und erreichen die Runtime ohne entsprechende Freigabe. GGUF-Struktur, unterstützte Modellarchitektur/Quantisierung und verfügbarer RAM bleiben technische Voraussetzungen. Es wird keine Unterstützung jeder beliebigen Architektur behauptet.

Die 4B-spezifischen Prüfungen und Kompatibilitätskernel bleiben zusätzlich vorhanden; die laufende Fehlerbehebung für diese Modellfamilie beschränkt nicht die übrige Modellwahl.

## Prüfungen und Update

32 Android-Unit-/Robolectric-Tests, lokaler CPU-APK-Build, Lint, Java-Syntax und Projektprüfungen bestehen. Die neuen UI-/Importprüfungen verwenden synthetische NativeEngine-Testdaten und prüfen keinen tatsächlichen Modellinhalt auf dem A55. Die sechs neuen Prüffälle decken Fortfahren, Abbrechen, spätere Inhaltsänderung, Activity-Neustart, andere GGUF-Namen und ungültige Dateiköpfe ab.

Version 0.4.1 verwendet Versionscode 8. Das Update wird mit demselben neuen Schlüssel wie der veröffentlichte Release 0.4.0 signiert; gegenüber diesem Release ist es update-kompatibel. Frühere 0.3.x-Installationen verwenden ein anderes Zertifikat. Kein physischer A55-Nachtest durchgeführt.
