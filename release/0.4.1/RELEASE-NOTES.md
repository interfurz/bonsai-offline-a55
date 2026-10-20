## Bonsai Offline 0.4.1 — freier GGUF-Import und Prüfsummen-Freigabe

Installierbares ARM64-Release mit **CPU und Vulkan**. Über die vorhandene Version **0.4.0 installieren**, ohne sie vorher zu deinstallieren: Paketname und Signierzertifikat bleiben gleich, Versionscode steigt auf 8. Frühere 0.3.x-Versionen verwenden einen anderen Schlüssel.

### Änderungen

- Das Popup bei einer abweichenden Prüfsumme bietet **Trotzdem fortfahren** und **Abbrechen**. Eine andere Prüfsumme allein wird nicht mehr als Beweis für einen Dateifehler dargestellt.
- Fortfahren gilt für genau die bestätigte Datei: SHA256, Größe und Vergleichsfassung werden gespeichert. Dieselbe Datei kann später ohne erneute Nachfrage geladen werden; geänderte Bytes benötigen eine eigene Entscheidung.
- Beim Import bleibt das bisherige Modell bis zur Bestätigung erhalten. Abbrechen verwirft die vorgemerkte Kopie. Fortfahren liest den Hash erneut, bevor die Datei übernommen wird. Die offene Entscheidung übersteht eine Activity-Neuerstellung.
- Der Import ist allgemein für **GGUF-Modelle** formuliert. Andere Modellnamen benötigen keine bekannte Bonsai-Prüfsumme. Das Bonsai-4B-Modell ist ein optionales Beispiel; Architektur, Quantisierung und freier RAM bestimmen die tatsächliche Ausführbarkeit.
- Hintergrundgenerierung, Chatverlauf, Kernel-Optimierung, CPU/Vulkan-Auswahl und die zusätzliche Diagnose für Bonsai-Quantisierung bleiben enthalten. Keine Internetberechtigung; Modellgewichte separat importieren.

### Verifikation

32 lokale Android-Unit-/Robolectric-Tests, lokaler APK-Build, Lint, Java-Syntax und Projektprüfungen bestanden. Auch der vollständige CPU/Vulkan-Release-Build mit 32 Release-Tests und Lint ist erfolgreich. Prüfnachweise:
https://github.com/interfurz/bonsai-offline-a55/actions/runs/39205659357

APK-Quellcommit: `468e462cb9fcefa23a92b244720f43606e30b09b`.

Die APK wird mit demselben privaten Schlüssel wie Release 0.4.0 lokal signiert. Private Schlüssel oder Passwörter sind nicht Teil der Release-Assets oder des CI-Laufs. Paket, Signatur, Dateiprüfsumme und 16-KB-Zipalignment werden vor Veröffentlichung geprüft. Prüfsumme und Buildinformationen liegen unter Assets.

Kein physischer A55-Nachtest durchgeführt. Die neuen Import-/Dialogtests verwenden synthetische NativeEngine-Testdaten; sie belegen keine korrekten Antworten eines bestimmten Modellexports. Die konkrete 4B-Ausgabe nach Fortfahren muss am Gerät geprüft werden. Andere Modellarchitekturen müssen von der eingebauten Runtime unterstützt sein. Ein Exynos-1480-NPU-Pfad ist weiterhin nicht verfügbar.

Signierte APK-SHA256: `53f56c4b63e172bce76d22cc2b94c292f4be6a29241e89157076a8eb00d99315`. Zertifikat-SHA256: `933bc216a40682ce964cb0a2db03fb7ab511ae1916a7ea3bd0d0328c153bc462`. Der Release-Quellstand enthält zusätzlich korrigierte Test-Synchronisation und Release-Werkzeuge; die produktiven App-Dateien stimmen mit dem APK-Quellcommit überein.
